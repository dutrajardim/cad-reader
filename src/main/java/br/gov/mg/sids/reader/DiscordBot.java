package br.gov.mg.sids.reader;

import java.math.BigInteger;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Comandos restritos a um servidor e fórum. Toda exclusão exige prévia e confirmação do autor. */
final class DiscordBot implements AutoCloseable {
    private final DiscordBotApi api;
    private final CadSummary summary;
    private final String guild, forum, role;
    private final Consumer<String> log;
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final ExecutorService responses = Executors.newFixedThreadPool(2);
    private final Map<String, Action> actions = new ConcurrentHashMap<>();
    private final Set<String> handled = Collections.synchronizedSet(new LinkedHashSet<>());
    private volatile boolean closed;
    private volatile String startupStage = "preparando início…";
    private volatile DiscordBotGateway gateway;
    private String application;
    private java.nio.channels.FileChannel lockFile;
    private java.nio.channels.FileLock lock;
    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    DiscordBot(String token, String guild, String forum, String role, CadSummary summary,
               Consumer<String> log) {
        this(new DiscordBotApi(token), guild, forum, role, summary, log);
    }
    DiscordBot(DiscordBotApi api, String guild, String forum, String role, CadSummary summary,
               Consumer<String> log) {
        if (!guild.matches("[0-9]{5,20}") || !forum.matches("[0-9]{5,20}") ||
            (!role.isBlank() && !role.matches("[0-9]{5,20}")))
            throw new IllegalArgumentException("Informe IDs válidos do servidor, fórum e cargo (opcional)");
        this.api = api;
        this.guild = guild;
        this.forum = forum;
        this.role = role;
        this.summary = summary;
        this.log = log;
    }
    /** Registra apenas o comando deste bot, sem substituir outros comandos da aplicação Discord. */
    synchronized void start(String token, java.nio.file.Path root) throws Exception {
        Map<String, Object> app = BotJson.object(startupRequest("identificar a aplicação do token", "GET", "/oauth2/applications/@me", null));
        application = BotJson.text(app, "id");
        if (!application.matches("[0-9]+"))
            throw new IllegalStateException("Aplicação Discord não identificada");
        java.nio.file.Files.createDirectories(root);
        lockFile = java.nio.channels.FileChannel.open(root.resolve("bot-" + application + ".lock"),
                                                      java.nio.file.StandardOpenOption.CREATE,
                                                      java.nio.file.StandardOpenOption.WRITE);
        lock = lockFile.tryLock();
        if (lock == null)
            throw new IllegalStateException("Este bot já está em execução neste computador");
        Map<String, Object> channel = BotJson.object(startupRequest("acessar o fórum configurado", "GET", "/channels/" + forum, null));
        if (!guild.equals(BotJson.text(channel, "guild_id")) || !"15".equals(BotJson.text(channel, "type")))
            throw new IllegalArgumentException("O fórum não pertence ao servidor configurado ou não é fórum");
        startupRequest("registrar /cad-reader no servidor", "POST",
                       "/applications/" + application + "/guilds/" + guild + "/commands", commandDefinition());
        gateway = new DiscordBotGateway(token, api, this::accept, log);
        startupStage = "abrindo conexão de comandos…";
        log.accept("Bot: abrindo conexão de comandos com o Discord…");
        try { gateway.start(); }
        catch (DiscordBotApi.ApiFailure e) { throw startupFailure("abrir o Gateway", e); }
    }
    /** Cada operação de início identifica a etapa que o Discord recusou. */
    private Object startupRequest(String stage, String method, String path, Object data) throws Exception {
        startupStage = stage + "…";
        log.accept("Bot: etapa — " + stage);
        try { return api.request(method, path, data); }
        catch (DiscordBotApi.ApiFailure e) { throw startupFailure(stage, e); }
    }
    private IllegalStateException startupFailure(String stage, DiscordBotApi.ApiFailure failure) {
        String hint;
        if (failure.status == 401)
            hint = "Confira o token da seção Bot no Developer Portal; ele não é a URL do webhook.";
        else if (stage.contains("fórum"))
            hint = "Confira se o bot foi instalado neste servidor, se o ID é do canal de fórum e se o cargo do bot tem Ver canal nas permissões do fórum e da categoria.";
        else if (stage.contains("registrar"))
            hint = "Confira o ID do servidor e a instalação deste bot em Guild Install, com os escopos bot e applications.commands.";
        else
            hint = "Confira se o token pertence à aplicação correta e se a resposta veio do Discord ou de um bloqueio da rede.";
        return new IllegalStateException("Ao " + stage + ": " + failure.getMessage() + ". " + hint, failure);
    }

    static Map<String, Object> commandDefinition() {
        return Map.of("name", "cad-reader", "description", "Consultas CAD e limpeza de tópicos", "type", 1,
                      "default_member_permissions", "0", "options",
                      List.of(Map.of("type", 1, "name", "totais-por-natureza", "description",
                                     "Total por natureza e unidade de área BM", "options",
                                     List.of(Map.of("type", 3, "name", "filtro", "description",
                                                    "Digite parte do nome do filtro CAD", "required", true,
                                                    "autocomplete", true))),
                              Map.of("type", 1, "name", "limpar", "description",
                                     "Apagar TODAS as mensagens do tópico anteriores à data", "options",
                                     List.of(Map.of("type", 3, "name", "antes-de", "description",
                                                    "Data exclusiva AAAA-MM-DD, horário Brasília", "required",
                                                    true)))));
    }
    /** Interações não exigem intent de conteúdo de mensagens nem leitura contínua do chat. */
    void accept(Map<String, Object> interaction) {
        if (closed)
            return;
        String id = BotJson.text(interaction, "id");
        synchronized (handled) {
            if (!handled.add(id))
                return;
            if (handled.size() > 2000)
                handled.remove(handled.iterator().next());
        }
        responses.submit(() -> {
            try {
                dispatch(interaction);
            } catch (Exception e) {
                log.accept("Bot: falha ao responder interação (" + e.getClass().getSimpleName() +
                           "); confira conexão/permissões.");
            }
        });
    }
    private void dispatch(Map<String, Object> i) throws Exception {
        String type = BotJson.text(i, "type");
        Map<String, Object> data = BotJson.object(i.get("data"));
        if (!guild.equals(BotJson.text(i, "guild_id")) || !allowed(i, false)) {
            callback(i, "4".equals(type) ? Map.of("type", 8, "data", Map.of("choices", List.of()))
                                           : message("Sem autorização para usar este bot.", true));
            return;
        }
        if ("4".equals(type)) {
            autocomplete(i, data);
            return;
        }
        if ("3".equals(type)) {
            component(i, data);
            return;
        }
        if (!"2".equals(type) || !"cad-reader".equals(BotJson.text(data, "name")))
            return;
        List<Object> options = BotJson.array(data.get("options"));
        if (options.isEmpty()) {
            callback(i, message("Escolha totais-por-natureza ou limpar.", true));
            return;
        }
        Map<String, Object> sub = BotJson.object(options.get(0));
        String command = BotJson.text(sub, "name");
        if ("limpar".equals(command) && !allowed(i, true)) {
            callback(i, message("A limpeza exige Gerenciar mensagens neste tópico.", true));
            return;
        }
        callback(i, Map.of("type", 5, "data", Map.of("flags", 64)));
        work.submit(() -> {
            try {
                if ("totais-por-natureza".equals(command))
                    sum(i, option(sub, "filtro"));
                else if ("limpar".equals(command))
                    previewCleanup(i, option(sub, "antes-de"));
            } catch (Exception e) {
                safeEdit(i, "Não foi possível concluir: " + safeReason(e));
            }
        });
    }
    private boolean allowed(Map<String, Object> i, boolean cleanup) {
        Map<String, Object> member = BotJson.object(i.get("member"));
        BigInteger permissions;
        try {
            permissions = new BigInteger(BotJson.text(member, "permissions"));
        } catch (Exception e) {
            return false;
        }
        boolean admin = permissions.testBit(3);
        if (cleanup)
            return admin || permissions.testBit(13);
        if (admin || permissions.testBit(5))
            return true;
        return !role.isEmpty() && BotJson.array(member.get("roles")).contains(role);
    }
    private boolean canPublish(Map<String, Object> i) {
        try {
            BigInteger p = new BigInteger(BotJson.text(BotJson.object(i.get("member")), "permissions"));
            if (p.testBit(3))
                return true;
            String type = BotJson.text(BotJson.object(i.get("channel")), "type");
            if (!Set.of("0", "5", "10", "11", "12").contains(type)) return false;
            return p.testBit(10) &&
                p.testBit(type.equals("10") || type.equals("11") || type.equals("12") ? 38 : 11);
        } catch (Exception e) {
            return false;
        }
    }
    private void autocomplete(Map<String, Object> i, Map<String, Object> data) throws Exception {
        String query = "";
        for (Object sub : BotJson.array(data.get("options")))
            for (Object opt : BotJson.array(BotJson.object(sub).get("options")))
                if (Boolean.TRUE.equals(BotJson.object(opt).get("focused")))
                    query = BotJson.text(BotJson.object(opt), "value");
        String normalized = normalize(query);
        List<Object> choices = new ArrayList<>();
        for (Map.Entry<String, String> f : summary.filters().entrySet())
            if (normalize(f.getValue() + " " + f.getKey()).contains(normalized)) {
                choices.add(
                    Map.of("name", limit(f.getValue() + " (" + f.getKey() + ")", 100), "value", f.getKey()));
                if (choices.size() == 25)
                    break;
            }
        callback(i, Map.of("type", 8, "data", Map.of("choices", choices)));
    }
    private void sum(Map<String, Object> i, String filter) throws Exception {
        String text = summary.query(filter).get(90, TimeUnit.SECONDS);
        List<String> pages = pages(text);
        String key = remember(new Action(i, pages, null, null));
        edit(i,
             payload(pages.get(0), true, pages.size() == 1 ? buttons(key, "Publicar aqui", 1) : List.of()));
        for (int n = 1; n < pages.size(); n++)
            api.request("POST", hook(i),
                        payload(pages.get(n), true,
                                n == pages.size() - 1 ? buttons(key, "Publicar aqui", 1) : List.of()));
    }
    private void previewCleanup(Map<String, Object> i, String date) throws Exception {
        LocalDate day;
        try {
            day = LocalDate.parse(date);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Use uma data válida no formato AAAA-MM-DD");
        }
        try {
            BigInteger permissions = new BigInteger(BotJson.text(i, "app_permissions"));
            if (!permissions.testBit(3) &&
                !(permissions.testBit(10) && permissions.testBit(13) && permissions.testBit(16)))
                throw new IllegalArgumentException(
                    "O bot precisa de Ver canal, Ler histórico e Gerenciar mensagens neste tópico");
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Não foi possível confirmar as permissões do bot neste tópico");
        }
        if (day.isBefore(LocalDate.of(2015, 1, 1)))
            throw new IllegalArgumentException("Data anterior ao funcionamento do Discord");
        if (day.isAfter(LocalDate.now(ZONE)))
            throw new IllegalArgumentException("Escolha uma data até hoje; datas futuras não são aceitas");
        String channel = BotJson.text(i, "channel_id");
        validateThread(channel);
        Instant cutoff = day.atStartOfDay(ZONE).toInstant();
        List<String> ids = scan(channel, cutoff);
        if (ids.isEmpty()) {
            edit(i, payload("Nenhuma mensagem anterior a " + day + " neste tópico.", true, List.of()));
            return;
        }
        String key = remember(new Action(i, null, ids, cutoff));
        edit(i, payload("Apagar " + ids.size() + " mensagens anteriores a " + day + " 00:00 (Brasília)?\n"
                            + ("Inclui mensagens do CAD, textos, fotos e mídias de TODOS os autores. " +
                               "Exclusão irreversível.\n")
                            + ("A data não protege ocorrências antigas ainda abertas. Mensagens a partir da data " +
                               "serão preservadas."),
                        true, buttons(key, "Confirmar exclusão", 4)));
    }
    /** Só tópicos do fórum configurado; nunca apaga o fórum ou o tópico como objeto. */
    private void validateThread(String channel) throws Exception {
        Map<String, Object> c = BotJson.object(api.request("GET", "/channels/" + channel, null));
        if (!guild.equals(BotJson.text(c, "guild_id")) || !forum.equals(BotJson.text(c, "parent_id")) ||
            !"11".equals(BotJson.text(c, "type")))
            throw new IllegalArgumentException("Execute a limpeza dentro de um tópico do fórum configurado");
    }
    List<String> scan(String channel, Instant cutoff) throws Exception {
        List<String> ids = new ArrayList<>();
        String before = snowflake(cutoff);
        for (int page = 0; page < 200; page++) {
            if (closed)
                throw new InterruptedException();
            List<Object> messages = BotJson.array(
                api.request("GET", "/channels/" + channel + "/messages?limit=100&before=" + before, null));
            if (messages.isEmpty())
                return ids;
            String previous = before;
            for (Object value : messages) {
                Map<String, Object> m = BotJson.object(value);
                String id = BotJson.text(m, "id");
                if (!id.matches("[0-9]+"))
                    throw new IllegalArgumentException("Mensagem Discord sem ID");
                if (timestamp(id).isBefore(cutoff))
                    ids.add(id);
                before = id;
            }
            if (before.equals(previous))
                throw new IllegalArgumentException("Paginação Discord não avançou");
            if (messages.size() < 100)
                return ids;
        }
        throw new IllegalArgumentException(
            "Tópico excede 20.000 mensagens por prévia; nenhuma mensagem foi apagada");
    }
    private void component(Map<String, Object> i, Map<String, Object> data) throws Exception {
        String custom = BotJson.text(data, "custom_id");
        boolean cancel = custom.startsWith("cancel:");
        String key = custom.substring(custom.indexOf(':') + 1);
        Action a = actions.get(key);
        if (a == null || a.expires < System.nanoTime() || !a.user.equals(user(i)) ||
            !a.channel.equals(BotJson.text(i, "channel_id"))) {
            callback(i, message("Esta ação expirou ou pertence a outra pessoa. Execute o comando novamente.",
                                true));
            return;
        }
        if (a.ids == null && !cancel && !canPublish(i)) {
            callback(i, message("Você não tem permissão para publicar neste local.", true));
            return;
        }
        if (a.ids != null && !allowed(i, true)) {
            callback(i, message("Permissão de limpeza ausente.", true));
            return;
        }
        if (!actions.remove(key, a)) {
            callback(i, message("Ação já utilizada.", true));
            return;
        }
        callback(i, Map.of("type", 6));
        work.submit(() -> {
            int deleted = 0;
            try {
                if (cancel) {
                    edit(i, payload("Ação cancelada.", true, List.of()));
                    return;
                }
                if (a.ids == null) {
                    edit(i, payload("Publicando neste local…", true, List.of()));
                    for (String page : a.pages)
                        api.request("POST", "/channels/" + a.channel + "/messages",
                                    payload(page, false, List.of()));
                    edit(i, payload("Relatório publicado aqui.", true, List.of()));
                } else {
                    validateThread(a.channel);
                    edit(i, payload("Limpeza em andamento: " + a.ids.size() + " mensagens selecionadas.",
                                    true, List.of()));
                    List<String> recent = new ArrayList<>(), old = new ArrayList<>();
                    Instant bulkBoundary = Instant.now().minus(Duration.ofDays(14)).plusSeconds(60);
                    for (String id : a.ids) {
                        if (!timestamp(id).isBefore(a.cutoff))
                            throw new IllegalArgumentException("Mensagem fora do corte confirmado");
                        (timestamp(id).isAfter(bulkBoundary) ? recent : old).add(id);
                    }
                    for (int start = 0; start < recent.size(); start += 100) {
                        if (closed)
                            throw new InterruptedException();
                        List<String> batch = recent.subList(start, Math.min(recent.size(), start + 100));
                        if (batch.size() == 1)
                            delete(a.channel, batch.get(0));
                        else
                            api.request("POST", "/channels/" + a.channel + "/messages/bulk-delete",
                                        Map.of("messages", batch));
                        deleted += batch.size();
                    }
                    for (String id : old) {
                        if (closed)
                            throw new InterruptedException();
                        delete(a.channel, id);
                        deleted++;
                    }
                    edit(i, payload("Limpeza concluída: " + deleted +
                                        " mensagens removidas ou já ausentes. Tópico preservado.",
                                    true, List.of()));
                    log.accept("Bot: limpeza concluída; tópico=" + a.channel + "; mensagens=" + deleted);
                }
            } catch (Exception e) {
                safeEdit(i, (a.ids == null
                                 ? "Publicação interrompida; confira o canal antes de repetir. "
                                 : "Limpeza interrompida após " + deleted + " remoções confirmadas. ") +
                                safeReason(e));
            }
        });
    }
    private void delete(String channel, String id) throws Exception {
        try {
            api.request("DELETE", "/channels/" + channel + "/messages/" + id, null);
        } catch (DiscordBotApi.ApiFailure e) {
            if (e.status != 404)
                throw e;
        }
    }
    private String remember(Action a) {
        actions.entrySet().removeIf(e -> e.getValue().expires < System.nanoTime());
        if (actions.size() >= 100)
            throw new IllegalArgumentException("Muitas ações pendentes; aguarde expirar");
        String key = UUID.randomUUID().toString();
        actions.put(key, a);
        return key;
    }
    private static String option(Map<String, Object> sub, String name) {
        for (Object o : BotJson.array(sub.get("options")))
            if (name.equals(BotJson.text(BotJson.object(o), "name")))
                return BotJson.text(BotJson.object(o), "value");
        return "";
    }
    private static String user(Map<String, Object> i) {
        return BotJson.text(BotJson.object(BotJson.object(i.get("member")).get("user")), "id");
    }
    static String snowflake(Instant date) {
        return BigInteger.valueOf(date.toEpochMilli() - 1420070400000L).shiftLeft(22).toString();
    }
    static Instant timestamp(String id) {
        return Instant.ofEpochMilli(new BigInteger(id).shiftRight(22).longValueExact() + 1420070400000L);
    }
    private static String normalize(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT);
    }
    static List<String> pages(String text) {
        List<String> pages = new ArrayList<>();
        while (text.length() > 1800) {
            int end = text.lastIndexOf('\n', 1800);
            if (end < 1)
                end = 1800;
            if (Character.isHighSurrogate(text.charAt(end - 1)))
                end--;
            pages.add(text.substring(0, end));
            text = text.substring(end);
        }
        pages.add(text);
        return pages;
    }
    private static String limit(String text, int count) {
        return text.length() > count ? text.substring(0, count) : text;
    }
    private static Map<String, Object> payload(String text, boolean ephemeral, List<Object> components) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", text);
        data.put("allowed_mentions", Map.of("parse", List.of()));
        data.put("components", components);
        if (ephemeral)
            data.put("flags", 64);
        return data;
    }
    private static Map<String, Object> message(String text, boolean ephemeral) {
        return Map.of("type", 4, "data", payload(text, ephemeral, List.of()));
    }
    private static List<Object> buttons(String key, String label, int style) {
        return List.of(Map.of(
            "type", 1, "components",
            List.of(Map.of("type", 2, "style", style, "label", label, "custom_id", "action:" + key),
                    Map.of("type", 2, "style", 2, "label", "Cancelar", "custom_id", "cancel:" + key))));
    }
    private void callback(Map<String, Object> i, Object payload) throws Exception {
        api.request("POST",
                    "/interactions/" + BotJson.text(i, "id") + "/" + BotJson.text(i, "token") + "/callback",
                    payload);
    }
    private String hook(Map<String, Object> i) {
        return "/webhooks/" + BotJson.text(i, "application_id") + "/" + BotJson.text(i, "token");
    }
    private void edit(Map<String, Object> i, Object payload) throws Exception {
        api.request("PATCH", hook(i) + "/messages/@original", payload);
    }
    private void safeEdit(Map<String, Object> i, String message) {
        try {
            edit(i, payload(message, true, List.of()));
        } catch (Exception e) {
            log.accept("Bot: operação interrompida; não foi possível atualizar resposta privada.");
        }
    }
    private static String safeReason(Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null)
            cause = cause.getCause();
        return cause instanceof IllegalArgumentException || cause instanceof DiscordBotApi.ApiFailure
            ? cause.getMessage()
            : "Falha de conexão ou sessão indisponível (" + cause.getClass().getSimpleName() + ").";
    }
    /** Estado resumido para a interface; não contém token nem identificadores secretos. */
    String status() {
        DiscordBotGateway current = gateway;
        return closed ? "parado; reinicie o bot" : current == null ? startupStage : current.status();
    }

    public synchronized void close() {
        closed = true;
        if (gateway != null)
            gateway.close();
        api.close();
        work.shutdownNow();
        responses.shutdownNow();
        actions.clear();
        try {
            if (lock != null)
                lock.release();
            if (lockFile != null)
                lockFile.close();
        } catch (Exception ignored) {
        }
    }
    private static final class Action {
        final String user, channel;
        final List<String> pages, ids;
        final Instant cutoff;
        final long expires = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
        Action(Map<String, Object> i, List<String> pages, List<String> ids, Instant cutoff) {
            user = user(i);
            channel = BotJson.text(i, "channel_id");
            this.pages = pages;
            this.ids = ids;
            this.cutoff = cutoff;
        }
    }
}
