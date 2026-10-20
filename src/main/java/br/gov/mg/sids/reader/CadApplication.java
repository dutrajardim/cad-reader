package br.gov.mg.sids.reader;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.w3c.dom.Element;

/** Ciclo de vida CAD/Discord independente de Swing e HTTP. Uma sessão compartilhada. */
final class CadApplication implements AutoCloseable {
    final CadOperationalState operational;
    final Path root;
    private final ExecutorService tasks = Executors.newCachedThreadPool();
    private final ScheduledExecutorService statusTask = Executors.newSingleThreadScheduledExecutor();
    private volatile CadXmppReader reader;
    private volatile CadMonitor activeMonitor;
    private volatile CadSummary summary;
    private volatile DiscordPublisher discord;
    private volatile DiscordBot bot;
    private volatile boolean monitoring, botStarting, botStopping, closed;
    private volatile long generation;
    private volatile Map<String,CadResourceFilter> resourceFilters = Collections.emptyMap();
    private volatile List<Object> callFilters = Collections.emptyList(), resourceCatalog = Collections.emptyList();
    private Properties config = defaults();
    private long revision;
    private volatile Consumer<String> logListener = text -> {};
    private volatile Consumer<CadMonitor.Event> eventListener = event -> {};
    CadApplication(Path root) { this(root, new CadOperationalState()); }
    CadApplication(Path root, CadOperationalState state) {
        this.root = root; this.operational = state;
        statusTask.scheduleWithFixedDelay(() -> {
            DiscordPublisher p = discord; DiscordBot b = bot;
            if (reader == null && p == null && b == null && !botStopping) return;
            operational.publicationStatus(p == null ? "Discord: publicação desativada nesta sessão" : p.status());
            operational.botStatus(botStopping ? "encerrando…" : b == null ? "parado" : b.status());
        }, 0, 300, TimeUnit.MILLISECONDS);
    }
    static Properties defaults() {
        Properties p = new Properties(); p.setProperty("host", "cad.sids.mg.gov.br");
        p.setProperty("port", "7001"); p.setProperty("insecure", "true"); return p;
    }
    synchronized void listeners(Consumer<String> logs, Consumer<CadMonitor.Event> events) {
        logListener = logs; eventListener = events;
    }
    private void log(String text) { operational.activity("log", text, ""); logListener.accept(text); }
    synchronized Properties configuration() { Properties copy = new Properties(); copy.putAll(config); return copy; }
    synchronized long revision() { return revision; }
    synchronized void configure(Properties next) {
        if (reader != null || bot != null) throw new IllegalArgumentException("Desconecte do CAD e pare o bot antes de alterar a configuração.");
        Properties copy = defaults(); copy.putAll(next);
        config = copy; revision++;
    }
    synchronized Map<String,Object> status() {
        return Java8.map("revision", revision, "config", new LinkedHashMap<Object,Object>(config),
            "connected", reader != null, "monitoring", monitoring, "botRunning", bot != null,
            "botStarting", botStarting, "botStopping", botStopping,
            "callFilters", callFilters, "resourceFilters", resourceCatalog);
    }
    CadSummary summary() { return summary; }
    void requestHistory(String id) {
        CadXmppReader session = reader;
        if (session == null) throw new IllegalArgumentException("Conecte ao CAD para carregar o histórico.");
        try { session.requestWebHistory(id); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("Não foi possível solicitar o histórico ao CAD."); }
    }
    synchronized void connect() {
        if (closed) throw new IllegalArgumentException("Aplicação encerrada.");
        if (reader != null) throw new IllegalArgumentException("Já existe uma conexão. Desconecte para iniciar outra.");
        Map<String, String> options = new HashMap<>();
        options.put("host", config.getProperty("host", "cad.sids.mg.gov.br"));
        options.put("to", config.getProperty("host", "cad.sids.mg.gov.br"));
        options.put("port", config.getProperty("port", "7001"));
        options.put("user", config.getProperty("user", ""));
        options.put("password", config.getProperty("password", ""));
        if (Boolean.parseBoolean(config.getProperty("insecure", "true")))
            options.put("insecure", "true");
        ReaderOptions parsed = ReaderOptions.from(options);
        if (!parsed.username.equals(config.getProperty("user", ""))) { config.setProperty("user", parsed.username); revision++; }
        operational.reset();
        operational.sessionConfig(parsed.host,parsed.port,parsed.insecure);
        callFilters = resourceCatalog = Collections.emptyList();
        long connectionGeneration = ++generation;
        operational.communication.beginSession();
        CadMonitor monitor = new CadMonitor(e -> {
            if (connectionGeneration == generation)
                event(e);
        });
        monitor.setDiagnostics(message -> {
            if (connectionGeneration == generation)
                log("[PUBLICACAO] " + message);
        });
        activeMonitor = monitor;
        resourceFilters = Collections.emptyMap();
        CadXmppReader session = new CadXmppReader(parsed, new CadListener() {
            public void onCadRequest(String id,String operation,String callId,int page,String outcome,long elapsedMillis){
                if(connectionGeneration!=generation)return;
                operational.communication.request(connectionGeneration+":"+id,operation,callId,page,outcome,elapsedMillis);
                operational.details.request(callId,operation,page,outcome);
            }
            public void onCadNotification(String operation){if(connectionGeneration==generation)operational.communication.notification();}
            public void onParticipantsFinished(String callId,boolean complete){if(connectionGeneration==generation)operational.details.finished(callId,true,complete);}

            public void onLog(String category, String text) {
                if(connectionGeneration != generation) return;
                if ("AUTH".equals(category) && "Autenticação confirmada".equals(text) && connectionGeneration==generation)
                    operational.authenticated();
                log("[" + category + "] " + text);
            }
            public void onFilters(Element cache) {
                if (connectionGeneration != generation)
                    return;
                Map<String, CadResourceFilter> parsedFilters = new HashMap<>();
                for (Element f : CadXml.collectionItems(cache, "filtrosRecurso", "filtroRecurso")) {
                    CadResourceFilter filter = CadResourceFilter.from(f);
                    parsedFilters.put(filter.id, filter);
                }
                resourceFilters = Collections.unmodifiableMap(parsedFilters);
                CadSummary report = summary;
                if (report != null) report.catalog(cache);
                callFilters = filters(cache, "filtrosChamada", "filtroChamada");
                resourceCatalog = filters(cache, "filtrosRecurso", "filtroRecurso");
                log("Selecione os filtros e inicie o monitoramento.");
            }
            public void onHistoryFinished(String callId, boolean complete) {
                if(connectionGeneration!=generation)return;
                operational.details.finished(callId,false,complete);
                monitor.historyFinished(callId, complete);
            }
            public void onMessage(Element message) {
                if (connectionGeneration != generation) return;
                operational.accept(message);
                CadSummary report = summary;
                if (report != null && connectionGeneration == generation) report.accept(message);
                monitor.accept(message);
            }
        });
        summary = new CadSummary(session);
        monitor.setHistoryRequest(id -> {
            if (connectionGeneration != generation)
                return;
            try {
                session.requestHistory(id);
            } catch (Exception e) {
                monitor.historyFinished(id, false);
                log("[HISTORICO] Não foi possível consultar a chamada " + id + ".");
            }
        });
        monitor.setParticipantsRequest(id -> {
            if (connectionGeneration != generation)
                return;
            try {
                session.requestParticipants(id);
            } catch (Exception e) {
                log("[ENVOLVIDOS] Não foi possível consultar a chamada " + id + ".");
            }
        });
        reader = session;
        tasks.submit(() -> {
            try {
                session.start();
            } catch (Exception e) {
                if(connectionGeneration == generation)
                    log(e.getMessage()!=null&&e.getMessage().contains("CREDENCIAIS_INVALIDAS")
                        ? "[AUTH] O servidor recusou usuário/senha. Confira as credenciais CAD."
                        : "Conexão encerrada (" + e.getClass().getSimpleName() + "). Confira servidor, porta e rede.");
            } finally {
                session.close();
                synchronized (CadApplication.this) {
                    if (reader == session) {
                        operational.disconnected(); reader = null; summary = null;
                        stopBot(); stopPublisher(); monitoring = false;
                        log("Desconectado. Conecte e selecione os filtros novamente.");
                    }
                }
            }
        });
    }
    private void event(CadMonitor.Event event) {
        try {
            Path journal = root.resolve("historico.csv");
            synchronized (this) {
                Files.createDirectories(root);
                if (!Files.exists(journal))
                    AppFiles.write(journal, "recebido_utc;viatura;chamada;evento;detalhes\n");
                Java8.writeString(journal,
                                  csv(event.observed) + ";" + csv(event.resource) + ";" + csv(event.number)
                                      + ";" + csv(event.title) + ";" + csv(event.body) + "\n",
                                  StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            log("Falha ao gravar histórico local.");
        }
        DiscordPublisher p = discord;
        operational.activity("evento", event.title + " · " + event.resource + " · " + event.number,
            event.body + "\n\nPublicação: " + (!event.publishable || event.resourceId.isEmpty() ? "somente local (regra do evento)." : p == null ? "não enviada: Discord desativado." : "encaminhada à fila Discord; acompanhe a confirmação ou falha nos registros de publicação."));
        if (event.publishable && !event.resourceId.isEmpty()) {
            if (p != null)
                p.publish(event);
            else
                log("[PUBLICACAO] viatura=" + event.resource + "; chamada=" + event.number
                    + "; evento somente local: publicação Discord desativada nesta sessão.");
        }
        eventListener.accept(event);
    }
    static String csv(String s) {
        if (s.matches("^[=+@-].*"))
            s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
    private static List<Object> filters(Element cache, String collection, String tag) {
        List<Object> rows = new ArrayList<>();
        for (Element f : CadXml.collectionItems(cache, collection, tag))
            rows.add(Java8.map("value", f.getAttribute("id"), "label", CadXml.text(f, "descricao")));
        return Collections.unmodifiableList(rows);
    }
    synchronized void monitor(String calls, String resources, boolean publishing) {
        if (reader == null) throw new IllegalArgumentException("Conecte primeiro.");
        if (monitoring) throw new IllegalArgumentException("Desconecte antes de trocar filtros ou publicação.");
        if (callFilters.stream().noneMatch(f -> calls.equals(BotJson.text(BotJson.object(f), "value"))))
            throw new IllegalArgumentException("Selecione um filtro de chamadas retornado pelo CAD.");
        CadResourceFilter scope = resourceFilters.get(resources);
        if (scope == null || scope.units.isEmpty()) throw new IllegalArgumentException("Selecione um filtro de recursos com unidades válidas.");
        String token = config.getProperty("botToken", ""), url = config.getProperty("webhook", "");
        if (publishing && token.isEmpty()) throw new IllegalArgumentException("Configure o token do bot antes de publicar.");
        CadXmppReader session = reader; CadMonitor monitor = activeMonitor; long current = generation;
        config.setProperty("calls", calls); config.setProperty("resources", resources);
        config.setProperty("publish", Boolean.toString(publishing)); revision++; monitoring = true;
        tasks.submit(() -> {
            DiscordPublisher created = null;
            try {
                if (publishing) {
                    created = new DiscordPublisher(url, root, this::log, null, scope);
                    created.setTopicLookup(new DiscordTopicLookup(token, url));
                }
                synchronized (CadApplication.this) {
                    if (reader != session || generation != current) { if (created != null) created.close(); return; }
                    monitor.setResourceFilter(scope); operational.start(scope); operational.filterConfig(calls, resources);
                    discord = created;
                }
                session.applyFilters(calls, resources);
                log("Monitoramento iniciado" + (publishing ? " com publicação no Discord após a carga inicial." : " somente local."));
            } catch (Exception e) {
                synchronized (CadApplication.this) {
                    if (reader == session && generation == current) {
                        monitoring = false; operational.disconnected(); stopPublisher();
                        log("Não foi possível iniciar monitoramento (" + e.getClass().getSimpleName() + ").");
                    } else if (created != null) created.close();
                }
            }
        });
    }
    synchronized void configureBot(Properties next) {
        if(bot != null || botStarting || botStopping)throw new IllegalArgumentException("Pare o bot antes de alterar sua configuração.");
        for(String field:Java8.list("botToken","botGuild","botForum","botRole"))config.setProperty(field,next.getProperty(field,""));
        revision++;
    }
    synchronized void startBot() {
        if (bot != null || botStarting || botStopping) throw new IllegalArgumentException("Bot já iniciado ou encerrando.");
        CadSummary report = summary;
        if (reader == null || report == null || !report.hasFilters()) throw new IllegalArgumentException("Conecte ao CAD e aguarde os filtros.");
        String token = config.getProperty("botToken", "");
        DiscordBot candidate = new DiscordBot(token, config.getProperty("botGuild", ""), config.getProperty("botForum", ""), config.getProperty("botRole", ""), report, this::log);
        bot = candidate; botStarting = true;
        tasks.submit(() -> {
            boolean success = false;
            try { candidate.start(token, root); success = true; log("Bot iniciado."); }
            catch (Exception | LinkageError e) { log("Não foi possível iniciar o bot (" + e.getClass().getSimpleName() + "). Confira token, IDs e permissões."); }
            finally {
                if (!success) candidate.close();
                synchronized (CadApplication.this) { if (bot == candidate) { botStarting = false; if (!success) bot = null; } }
            }
        });
    }
    synchronized void stopBot() {
        DiscordBot running = bot;
        if (running == null || botStopping) return;
        botStopping = true; operational.botStatus("encerrando…");
        tasks.submit(() -> {
            try { running.close(); }
            finally { synchronized (CadApplication.this) { if (bot == running) { bot = null; botStarting = botStopping = false; operational.botStatus("parado"); } } }
        });
    }
    synchronized void retry() { if (discord == null) throw new IllegalArgumentException("Publicação desativada."); discord.retry(); }
    synchronized void clearPending() { if (discord == null) throw new IllegalArgumentException("Publicação desativada."); discord.clearPending(); }
    private void stopPublisher() { DiscordPublisher p = discord; discord = null; if (p != null) tasks.submit(p::close); }
    synchronized void disconnect() {
        generation++; CadXmppReader session = reader; reader = null; summary = null; monitoring = false;
        operational.disconnected(); stopBot(); stopPublisher();
        operational.publicationStatus("Discord: publicação desativada nesta sessão");
        if (session != null) tasks.submit(session::close);
        log("Desconectando; publicações não confirmadas permanecem em disco.");
    }
    public synchronized void close() {
        if (closed) return; closed = true;
        disconnect(); statusTask.shutdownNow(); tasks.shutdown();
    }
}
