package br.gov.mg.sids.reader;

import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.*;

/** Publica em fórum via webhook, com fila em disco e tópicos persistidos por recurso. */
final class DiscordPublisher implements AutoCloseable {
    private final String webhook;
    private final Path directory;
    private final Properties threads;
    private final Consumer<String> log;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final FileChannel lockChannel;
    private final FileLock lock;
    private volatile boolean closed;
    private volatile boolean blocked, clearing;
    private final Set<Path> arrivalsDuringClear = new HashSet<>();
    private volatile String state = "Discord: pronto para publicar";
    private final java.util.concurrent.atomic.AtomicInteger pendingCount
        = new java.util.concurrent.atomic.AtomicInteger();
    private long retryNotBefore;
    private ScheduledFuture<?> rateRetry;
    private long sequence;
    private final Transport transport;
    private final CadResourceFilter scope;
    private final long batchWindowMillis;
    private final Map<String, OpenBatch> openBatches = new HashMap<>();
    private DiscordTopicLookup topicLookup;

    void setTopicLookup(DiscordTopicLookup lookup) { topicLookup = lookup; }

    /** Cada arquivo é uma mensagem durável; a janela não se renova a cada evento. */
    private static final class OpenBatch {
        final String group;
        final Path file;
        final long deadlineNanos;
        List<CadMonitor.Event> events = Collections.emptyList();
        Properties data;
        boolean dispatched;
        OpenBatch(String group, Path file, long deadlineNanos) {
            this.group = group;
            this.file = file;
            this.deadlineNanos = deadlineNanos;
        }
    }
    interface Transport {
        Result send(String url, String body) throws Exception;
    }
    static final class Result {
        final int status;
        final String body, retryAfter;
        Result(int status, String body) { this(status, body, ""); }
        Result(int status, String body, String retryAfter) {
            this.retryAfter = retryAfter;
            this.status = status;
            this.body = body;
        }
    }

    DiscordPublisher(String webhook, Path root, Consumer<String> log) throws Exception {
        this(webhook, root, log, null);
    }
    /** Transporte substituível permite testar sem publicar em serviços externos. */
    DiscordPublisher(String webhook, Path root, Consumer<String> log, Transport transport) throws Exception {
        this(webhook, root, log, transport, null);
    }
    /** O escopo atual também é aplicado às mensagens persistidas de sessões anteriores. */
    DiscordPublisher(String webhook, Path root, Consumer<String> log, Transport transport,
                     CadResourceFilter scope) throws Exception {
        this(webhook, root, log, transport, scope, 3000);
    }
    /** Janela substituível somente nos testes de pacote; a interface usa três segundos. */
    DiscordPublisher(String webhook, Path root, Consumer<String> log, Transport transport,
                     CadResourceFilter scope, long batchWindowMillis) throws Exception {
        if (batchWindowMillis < 0)
            throw new IllegalArgumentException("Janela inválida");
        this.batchWindowMillis = batchWindowMillis;
        this.scope = scope;
        String normalizedWebhook = webhook == null ? "" : webhook.trim();
        normalizedWebhook = normalizedWebhook.replaceFirst("/+$", "");
        if (normalizedWebhook.startsWith("https://discordapp.com/api/"))
            normalizedWebhook = "https://discord.com/api/" + normalizedWebhook.substring("https://discordapp.com/api/".length());
        if (!normalizedWebhook.matches("https://discord\\.com/api(?:/v[0-9]+)?/webhooks/[0-9]+/[A-Za-z0-9._-]+"))
            throw new IllegalArgumentException(
                "Informe a URL completa do webhook de um canal de fórum do Discord.");
        this.webhook = normalizedWebhook;
        this.log = log;
        // A identidade usa o ID público, sem incorporar o token ao nome da pasta.
        String id = this.webhook.split("/webhooks/")[1].split("/")[0];
        directory = root.resolve("discord-" + AppFiles.hash(id).substring(0, 20));
        Files.createDirectories(directory);
        lockChannel = FileChannel.open(directory.resolve("publisher.lock"), StandardOpenOption.CREATE,
                                       StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            acquired = lockChannel.tryLock();
        } catch (Exception e) {
            lockChannel.close();
            throw new IllegalStateException("Este webhook já está aberto neste computador.");
        }
        if (acquired == null) {
            lockChannel.close();
            throw new IllegalStateException("Este webhook já está aberto neste computador.");
        }
        lock = acquired;
        try {
            threads = AppFiles.load(directory.resolve("topics.properties"));
            this.transport = transport == null ? new DiscordHttpTransport() : transport;
            int isolated = 0;
            try (java.util.stream.Stream<Path> files = Files.list(directory)) {
                for (Path file :
                     (Iterable<Path>)files.filter(f -> f.toString().endsWith(".pending"))::iterator) {
                    Properties data = AppFiles.load(file);
                    if (initialState(data)) {
                        Files.move(file, file.resolveSibling(file.getFileName() + ".initial-state"),
                                   StandardCopyOption.REPLACE_EXISTING);
                        isolated++;
                    } else if (!inScope(data)) {
                        isolate(file);
                        isolated++;
                    } else {
                        blocked = true;
                        pendingCount.incrementAndGet();
                    }
                }
            }
            if (isolated > 0)
                report("Discord: " + isolated
                       + (" envios antigos separados da fila por serem carga inicial, não corresponderem ao "
                          + "filtro atual ou não "
                          + "terem unidade/tipo registrados."));
            if (blocked)
                report("Discord: há envios pendentes. Confira o canal e use Reenviar pendentes para "
                       + "continuar a fila.");
        } catch (Exception e) {
            lock.release();
            lockChannel.close();
            if (transport instanceof DiscordHttpTransport)
                ((DiscordHttpTransport)transport).close();
            worker.shutdownNow();
            throw e;
        }
    }
    /** Persiste o lote completo a cada chegada, antes de aguardar ou fazer HTTP. */
    synchronized void publish(CadMonitor.Event event) {
        if (closed || !event.publishable)
            return;
        try {
            Properties metadata = metadata(event);
            if (!inScope(metadata))
                return;
            String[] identity = event.key.split(":", 3);
            String call = identity.length > 1 ? identity[1] : event.number;
            String group
                = AppFiles.hash(event.topicKey() + ":" + call + ":" + event.unitId + ":" + event.typeId);
            OpenBatch batch = openBatches.get(group);
            if (batch != null && (System.nanoTime() >= batch.deadlineNanos || !fits(batch.events, event))) {
                openBatches.remove(group);
                OpenBatch sealed = batch;
                worker.execute(() -> flushBatch(sealed));
                batch = null;
            }
            boolean fresh = batch == null;
            if (fresh) {
                Path file = directory.resolve(String.format(
                    "%019d-%06d-%s.pending", System.currentTimeMillis(), sequence++, UUID.randomUUID()));
                batch = new OpenBatch(group, file,
                                      System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(batchWindowMillis));
            }
            List<CadMonitor.Event> members = new ArrayList<>(batch.events);
            members.add(event);
            Properties data = batchData(members, group, batch.file.getFileName().toString());
            AppFiles.save(batch.file, data);
            batch.events = members;
            batch.data = data;
            if (fresh) {
                pendingCount.incrementAndGet();
                if (clearing)
                    arrivalsDuringClear.add(batch.file);
                if (!blocked && batchWindowMillis > 0)
                    state = "Discord: agrupando atualizações por até três segundos";
                if (batchWindowMillis > 0)
                    openBatches.put(group, batch);
                OpenBatch scheduled = batch;
                worker.schedule(() -> flushBatch(scheduled), batchWindowMillis, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            report("Discord: não foi possível enfileirar o evento; consulte o histórico local.");
        }
    }

    private Properties metadata(CadMonitor.Event event) {
        Properties data = new Properties();
        data.setProperty("resource", event.resourceId);
        data.setProperty("topicKey", event.topicKey());
        data.setProperty("prefix", event.resource);
        data.setProperty("number", event.number);
        data.setProperty("unit", event.unitId);
        data.setProperty("type", event.typeId);
        return data;
    }

    private boolean fits(List<CadMonitor.Event> events, CadMonitor.Event next) {
        if (events.size() >= 10)
            return false;
        int total = next.embedTextLength();
        for (CadMonitor.Event event : events)
            total += event.embedTextLength();
        return total <= 6000;
    }

    private Properties batchData(List<CadMonitor.Event> events, String group, String batchId) {
        Properties data = metadata(events.get(0));
        data.setProperty("number", events.get(events.size() - 1).number);
        StringBuilder fingerprint = new StringBuilder();
        for (int i = 0; i < events.size(); i++) {
            CadMonitor.Event event = events.get(i);
            String key = AppFiles.hash(event.key);
            data.setProperty("member." + i + ".key", key);
            data.setProperty("member." + i + ".fingerprint", event.fingerprint);
            fingerprint.append(key).append(':').append(event.fingerprint).append(';');
        }
        data.setProperty("members", Integer.toString(events.size()));
        data.setProperty("key", events.size() == 1 ? AppFiles.hash(events.get(0).key)
                                                   : AppFiles.hash("batch:" + group));
        data.setProperty("fingerprint", events.size() == 1
                                            ? events.get(0).fingerprint
                                            : AppFiles.hash(batchId + ":" + fingerprint.toString()));
        data.setProperty("create", CadMonitor.Event.jsonBatch(events, true));
        data.setProperty("reuse", CadMonitor.Event.jsonBatch(events, false));
        return data;
    }

    /** Sela sob o mesmo lock da inclusão; nenhuma chegada pode modificar um lote em envio. */
    private void flushBatch(OpenBatch batch) {
        Properties data;
        synchronized (this) {
            if (batch.dispatched || closed || clearing)
                return;
            batch.dispatched = true;
            openBatches.remove(batch.group, batch);
            data = batch.data;
        }
        if (blocked)
            return;
        try {
            send(batch.file, data);
        } catch (Exception e) {
            pause(e, data);
        }
    }

    /** Reenvio manual não antecipa a janela de um lote ainda em coleta. */
    private synchronized boolean collecting(Path file) {
        for (OpenBatch batch : new ArrayList<>(openBatches.values())) {
            if (!batch.file.equals(file))
                continue;
            if (System.nanoTime() < batch.deadlineNanos)
                return true;
            batch.dispatched = true;
            openBatches.remove(batch.group, batch);
            return false;
        }
        return false;
    }
    /** Retoma explicitamente falhas ambíguas; limites de envio são retomados pelo scheduler. */
    void retry() {
        if (!closed && !clearing)
            worker.submit(this::drainQueue);
    }

    /** Descarta somente a fila existente ao clicar; novas atualizações são preservadas. */
    synchronized void clearPending() {
        if (closed || clearing)
            return;
        clearing = true;
        arrivalsDuringClear.clear();
        // Separa eventos posteriores ao clique dos lotes que serão descartados.
        for (OpenBatch batch : openBatches.values())
            batch.dispatched = true;
        openBatches.clear();
        report("Discord: limpando pendências; um envio já em andamento pode ser concluído.");
        worker.execute(this::discardPending);
    }

    private void discardPending() {
        int removed = 0;
        synchronized (this) {
            if (closed)
                return;
            try (java.util.stream.Stream<Path> files = Files.list(directory)) {
                for (Path file :
                     (Iterable<Path>)files.filter(f -> f.toString().endsWith(".pending"))::iterator) {
                    if (!arrivalsDuringClear.contains(file) && Files.deleteIfExists(file)) {
                        pendingCount.decrementAndGet();
                        removed++;
                    }
                }
                if (rateRetry != null)
                    rateRetry.cancel(false);
                clearing = false;
                blocked = false;
                report("Discord: pendências limpas; mensagens descartadas=" + removed
                       + ". Novas atualizações continuam sendo publicadas.");
                // Jobs que venceram durante a limpeza não enviaram; reagenda os novos lotes.
                for (OpenBatch batch : openBatches.values())
                    worker.schedule(()
                                        -> flushBatch(batch),
                                    Math.max(0, batch.deadlineNanos - System.nanoTime()),
                                    TimeUnit.NANOSECONDS);
                long remaining = retryNotBefore - System.nanoTime();
                if (remaining > 0) {
                    blocked = true;
                    scheduleRateRetry(remaining);
                    report("Discord: pendências limpas; aguardando limite de envio por "
                           + Math.max(1, TimeUnit.NANOSECONDS.toSeconds(remaining)) + "s.");
                } else if (!arrivalsDuringClear.isEmpty())
                    worker.execute(this::drainQueue);
            } catch (Exception error) {
                clearing = false;
                blocked = true;
                report("Discord: limpeza incompleta; mensagens descartadas=" + removed
                       + ". Fila pausada; confira acesso à pasta de dados e tente limpar novamente.");
            } finally {
                arrivalsDuringClear.clear();
            }
        }
    }

    private void drainQueue() {
        if (closed || clearing)
            return;
        long remaining = retryNotBefore - System.nanoTime();
        if (remaining > 0) {
            report("Discord: aguardando limite de envio; retomada automática em "
                   + Math.max(1, TimeUnit.NANOSECONDS.toSeconds(remaining)) + "s.");
            scheduleRateRetry(remaining);
            return;
        }
        blocked = false;
        report("Discord: processando fila de publicações.");
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            for (Path file :
                 (Iterable<Path>)files.filter(f -> f.toString().endsWith(".pending")).sorted()::iterator) {
                if (closed || clearing)
                    break;
                if (collecting(file))
                    break;
                Properties data = AppFiles.load(file);
                try {
                    send(file, data);
                    if (blocked)
                        break;
                } catch (Exception e) {
                    pause(e, data);
                    break;
                }
            }
            if (!blocked && !closed)
                report("Discord: fila processada; aguardando novas atualizações.");
        } catch (Exception e) {
            blocked = true;
            report("Discord: fila pausada; não foi possível ler os arquivos locais.");
        }
    }

    private void scheduleRateRetry(long nanos) {
        if (closed)
            return;
        if (rateRetry != null)
            rateRetry.cancel(false);
        rateRetry = worker.schedule(this::drainQueue, Math.max(1, nanos), TimeUnit.NANOSECONDS);
    }

    private void send(Path file, Properties p) throws Exception {
        if (closed || clearing || !Files.exists(file))
            return;
        if (!inScope(p)) {
            isolate(file);
            pendingCount.decrementAndGet();
            return;
        }
        String resourceId = p.getProperty("resource");
        String prefix = p.getProperty("prefix", "");
        String topicKey = CadMonitor.Event.topicKey(prefix, resourceId);
        String thread = threads.getProperty(topicKey);
        if (thread == null && !prefix.trim().isEmpty())
            thread = threads.getProperty("vtr." + AppFiles.hash(prefix.trim().toUpperCase(Locale.ROOT)));
        if (thread == null && p.getProperty("topicKey") != null)
            thread = threads.getProperty(p.getProperty("topicKey"));
        if (thread == null)
            thread = threads.getProperty(p.getProperty("resource"));
        if (thread == null && topicLookup != null)
            thread = topicLookup.find(prefix);
        if (thread != null && !thread.equals(threads.getProperty(topicKey))) {
            threads.setProperty(topicKey, thread);
            AppFiles.save(directory.resolve("topics.properties"), threads);
        }
        if (thread != null
            && p.getProperty("fingerprint").equals(threads.getProperty("last." + p.getProperty("key")))) {
            removePending(file);
            return;
        }
        String url = webhook + "?wait=true" + (thread == null ? "" : "&thread_id=" + thread);
        Result response
            = transport.send(url, readablePayload(p.getProperty(thread == null ? "create" : "reuse")));
        // Só recria após recusa explícita de canal inexistente, nunca por timeout ou 404 genérico.
        if (thread != null && (response.status == 400 || response.status == 404)
            && errorCode(response.body) == 10003) {
            String invalidThread = thread;
            threads.entrySet().removeIf(e -> invalidThread.equals(e.getValue()));
            AppFiles.save(directory.resolve("topics.properties"), threads);
            report("Discord: tópico salvo não existe mais; criando novo tópico para "
                   + p.getProperty("prefix", p.getProperty("resource")) + ".");
            String existing = topicLookup == null ? null : topicLookup.find(prefix);
            response = transport.send(webhook + "?wait=true" + (existing == null ? "" : "&thread_id=" + existing),
                                      readablePayload(p.getProperty(existing == null ? "create" : "reuse")));
        }
        if (response.status == 429) {
            blocked = true;
            long millis = retryDelayMillis(response);
            retryNotBefore = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            report("Discord: limite de envio (HTTP 429); retomada automática em "
                   + Math.max(1, (millis + 999) / 1000) + "s.");
            scheduleRateRetry(TimeUnit.MILLISECONDS.toNanos(millis));
            return;
        }
        if (response.status < 200 || response.status >= 300)
            throw new DiscordFailure(response.status, errorCode(response.body));
        Matcher match = Pattern.compile("\"channel_id\"\\s*:\\s*\"([0-9]+)\"").matcher(response.body);
        if (!match.find())
            throw new MissingConfirmation(response.status);
        threads.setProperty(topicKey, match.group(1));
        threads.setProperty(p.getProperty("resource"), match.group(1));
        threads.setProperty("last." + p.getProperty("key"), p.getProperty("fingerprint"));
        int members = Integer.parseInt(p.getProperty("members", "0"));
        for (int i = 0; i < members; i++)
            threads.setProperty("last." + p.getProperty("member." + i + ".key"),
                                p.getProperty("member." + i + ".fingerprint"));
        AppFiles.save(directory.resolve("topics.properties"), threads);
        removePending(file);
        report("Discord: publicação confirmada; viatura=" + p.getProperty("prefix", p.getProperty("resource"))
               + "; chamada=" + p.getProperty("number", "não informada")
               + "; eventos=" + p.getProperty("members", "1") + "; tópico=" + match.group(1));
    }
    /** Resposta HTTP de sucesso sem identificação suficiente não confirma a publicação localmente. */
    private static final class MissingConfirmation extends java.io.IOException {
        final int status;
        MissingConfirmation(int status) {
            super("Resposta sem ID do canal");
            this.status = status;
        }
    }

    /** Pendências antigas também recebem rodapé legível ao serem reenviadas. */
    static String readablePayload(String payload) {
        return payload.replaceAll(
            "(\"footer\"\\s*:\\s*\\{\\s*\"text\"\\s*:\\s*\")CAD-READER/1 (?:\\\\.|[^\"\\\\])*(\")",
            "$1CAD • Recebido$2");
    }

    /** Nunca registra corpo HTTP, URL do webhook ou mensagens de exceções de rede. */
    private void pause(Exception error, Properties data) {
        if (closed)
            return;
        blocked = true;
        String reason = error instanceof DiscordFailure
                            ? error.getMessage()
                            : "falha local/de rede (" + error.getClass().getSimpleName() + "; "
                                  + networkReason(error) + failureStage(error)
                                  + "); o envio pode ter sido aceito antes da interrupção";
        report("Discord: fila PAUSADA; viatura="
               + data.getProperty("prefix", data.getProperty("resource", "não informada"))
               + "; chamada=" + data.getProperty("number", "não informada") + "; " + reason
               + ". Confira o canal e use Reenviar pendentes após corrigir a causa.");
    }
    private static String failureStage(Throwable error) {
        return error instanceof DiscordHttpTransport.Failure
            ? "; etapa=" + ((DiscordHttpTransport.Failure)error).stage
            : "";
    }

    /** Classifica causas sem registrar mensagens que possam conter a URL secreta. */
    private static String networkReason(Throwable error) {
        if (error instanceof MissingConfirmation)
            return "HTTP " + ((MissingConfirmation)error).status + " recebido sem ID do canal";
        if (error instanceof DiscordBotApi.ApiFailure) {
            DiscordBotApi.ApiFailure failure = (DiscordBotApi.ApiFailure)error;
            return "busca de tópicos: HTTP " + failure.status
                + (failure.code.isEmpty() ? "" : "; código Discord=" + failure.code);
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof java.net.SocketTimeoutException)
                return "tempo limite de conexão/resposta";
            if (cause instanceof java.net.UnknownHostException
                || cause instanceof java.nio.channels.UnresolvedAddressException)
                return "falha de resolução DNS";
            if (cause instanceof javax.net.ssl.SSLException)
                return "falha na conexão TLS";
            if (cause instanceof java.net.ConnectException)
                return "não foi possível estabelecer conexão";
            if (cause instanceof java.net.SocketException || cause instanceof java.io.EOFException)
                return "conexão interrompida";
            if (cause instanceof java.nio.file.FileSystemException)
                return "falha de acesso aos arquivos locais";
        }
        return "causa não identificada";
    }

    private static final class DiscordFailure extends java.io.IOException {
        DiscordFailure(int status, long code) {
            super("HTTP " + status + (code >= 0 ? "; código Discord=" + code : "") + "; "
                  + reason(status, code));
        }
        private static String reason(int status, long code) {
            if (code == 10015 || status == 401)
                return "webhook inexistente ou credencial inválida; confira a URL completa";
            if (code == 10003)
                return "canal/tópico inexistente; confira o fórum de destino";
            if (status == 403 || code == 50001 || code == 50013)
                return "acesso negado; confira as permissões do fórum";
            if (code == 50083)
                return "tópico arquivado ou bloqueado; confira suas configurações";
            if (status == 400)
                return "configuração ou mensagem recusada; confira destino de fórum e etiquetas obrigatórias";
            if (status >= 500)
                return "falha do Discord; confira o canal antes de reenviar";
            return "requisição recusada pelo Discord";
        }
    }
    static long errorCode(String body) {
        Matcher code = Pattern.compile("\"code\"\\s*:\\s*([0-9]+)").matcher(body);
        if (!code.find())
            return -1;
        try {
            return Long.parseLong(code.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
    static long retryDelayMillis(Result response) {
        double seconds = 5;
        Matcher retry
            = Pattern.compile("\"retry_after\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(response.body);
        try {
            if (!response.retryAfter.isEmpty())
                seconds = Double.parseDouble(response.retryAfter);
            else if (retry.find())
                seconds = Double.parseDouble(retry.group(1));
        } catch (NumberFormatException ignored) {
        }
        if (!Double.isFinite(seconds) || seconds < 0)
            seconds = 5;
        return Math.max(1000, Math.min(86400000, (long)Math.ceil(seconds * 1000)));
    }
    String status() { return state + " | Pendentes: " + pendingCount.get(); }
    private void report(String message) {
        state = message;
        log.accept(message);
    }
    private void removePending(Path file) throws java.io.IOException {
        if (Files.deleteIfExists(file))
            pendingCount.decrementAndGet();
    }
    private boolean inScope(Properties data) {
        return scope == null || scope.matches(data.getProperty("unit", ""), data.getProperty("type", ""));
    }
    /** Identifica cargas antigas pelo título/resumo, nunca pelo texto livre de um histórico. */
    private boolean initialState(Properties data) {
        Matcher fields = Pattern.compile("\"(?:content|title)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                             .matcher(data.getProperty("create", ""));
        while (fields.find()) {
            String value = fields.group(1);
            if (value.startsWith("SITUAÇÃO ATUAL") || value.contains("| SITUAÇÃO ATUAL")
                || value.contains("• SITUAÇÃO ATUAL"))
                return true;
        }
        return false;
    }
    /** Preserva o arquivo para consulta, mas o reenvio automático não o seleciona. */
    private void isolate(Path file) throws java.io.IOException {
        Files.move(file, file.resolveSibling(file.getFileName() + ".outside-filter"),
                   StandardCopyOption.REPLACE_EXISTING);
    }
    public void close() {
        synchronized (this) {
            if (closed)
                return;
            closed = true;
            openBatches.clear();
        }
        worker.shutdownNow();
        if (topicLookup != null)
            topicLookup.close();
        try {
            worker.awaitTermination(3, TimeUnit.SECONDS);
            lock.release();
            lockChannel.close();
        } catch (Exception ignored) {
        }
    }
}
