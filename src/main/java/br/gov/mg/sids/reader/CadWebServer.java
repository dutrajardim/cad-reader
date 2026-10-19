package br.gov.mg.sids.reader;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Interface Web; host/origem e token protegem as operações de escrita contra páginas externas. */
final class CadWebServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private final String token = UUID.randomUUID().toString();
    private final CadOperationalState state;
    private final OperationalSettings settings;
    private final ValhallaRouting routing;
    private final java.util.function.Supplier<CadSummary> reports;
    private final java.util.function.Consumer<String> historyRequest;
    private final Semaphore reportGate=new Semaphore(1);
    CadWebServer(CadOperationalState state, Path root, int port) throws IOException {
        this(state, root, port, new ValhallaRouting());
    }
    CadWebServer(CadOperationalState state, Path root, int port, ValhallaRouting routing) throws IOException {
        this(state,root,port,routing,()->null);
    }
    CadWebServer(CadOperationalState state,Path root,int port,ValhallaRouting routing,java.util.function.Supplier<CadSummary> reports)throws IOException{
        this(state,root,port,routing,reports,null);
    }
    CadWebServer(CadOperationalState state,Path root,int port,ValhallaRouting routing,java.util.function.Supplier<CadSummary> reports,java.util.function.Consumer<String> historyRequest)throws IOException{
        this.historyRequest=historyRequest;
        this.state = state; this.settings = new OperationalSettings(root); this.routing = routing;this.reports=reports;
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 16);
        server.setExecutor(workers); server.createContext("/", this::handle); server.start();
    }
    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            String host = exchange.getRequestHeaders().getFirst("Host");
            String authority = "127.0.0.1:" + server.getAddress().getPort();
            String local = "localhost:" + server.getAddress().getPort();
            String network = exchange.getLocalAddress().getAddress().getHostAddress() + ":" + server.getAddress().getPort();
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (!(authority.equals(host) || local.equals(host) || network.equals(host)) || (origin != null && !origin.equals("http://" + host))) {
                json(exchange, 403, Java8.map("error", "Origem não autorizada.")); return;
            }
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
            exchange.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            String path = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
            if (path.startsWith("/api/")) {
                if (method.equals("GET") && path.equals("/api/events")) {
                    long after=0; String query=exchange.getRequestURI().getRawQuery();
                    if(query!=null && query.startsWith("after=")) try{after=Long.parseLong(query.substring(6));}catch(NumberFormatException error){throw new IllegalArgumentException("Cursor de eventos inválido.");}
                    if(notModified(exchange,"events-"+token+"-"+state.activityVersion()+"-"+after)) return;
                    json(exchange, 200, state.activities(after)); return;
                }
                if(method.equals("GET")&&path.equals("/api/communication")){
                    if(notModified(exchange,"communication-"+token+"-"+state.communication.version()))return;
                    json(exchange,200,state.communication.snapshot());return;
                }
                if(method.equals("GET")&&path.equals("/api/call")){
                    String query=exchange.getRequestURI().getRawQuery();
                    String id=query!=null&&query.startsWith("id=")?URLDecoder.decode(query.substring(3),StandardCharsets.UTF_8):"";
                    if(!id.matches("[1-9][0-9]*"))throw new IllegalArgumentException("Identificador da ocorrência inválido.");
                    if(notModified(exchange,"call-"+token+"-"+id+"-"+state.version()+"-"+state.details.version()))return;
                    json(exchange,200,state.callDetails(id));return;
                }
                if(method.equals("GET")&&path.equals("/api/report-filters")){
                    CadSummary report=reports.get();Map<String,String> catalog=report==null?Collections.emptyMap():report.filters();
                    if(notModified(exchange,"report-filters-"+token+"-"+System.identityHashCode(report)+"-"+BotJson.write(catalog).hashCode()))return;
                    List<Object> rows=new ArrayList<>();catalog.forEach((id,name)->rows.add(Java8.map("value",id,"label",name+" · "+id)));
                    json(exchange,200,Java8.map("available",report!=null&&report.hasFilters(),"filters",rows));return;
                }
                if (method.equals("GET") && path.equals("/api/state")) {
                    String version = "state-"+token+"-"+state.version()+"-"+settings.version()+"-"+BotJson.write(routing.status()).hashCode();
                    if(notModified(exchange,version)) return;
                    json(exchange, 200, Java8.map("state", state.snapshot(), "settings", settings.get(), "routing", routing.status(), "token", token)); return;
                }
                if (!method.equals("POST")) { json(exchange, 405, Java8.map("error", "Método não permitido.")); return; }
                if (!token.equals(exchange.getRequestHeaders().getFirst("X-CAD-Token"))) {
                    json(exchange, 403, Java8.map("error", "Reabra a página para atualizar a sessão local.")); return;
                }
                byte[] bytes = exchange.getRequestBody().readNBytes(262145);
                if (bytes.length > 262144) { json(exchange, 413, Java8.map("error", "Cadastro muito grande.")); return; }
                Map<String, Object> body = BotJson.object(BotJson.parse(new String(bytes, StandardCharsets.UTF_8)));
                switch (path) {
                case "/api/call/history":
                    String historyId=BotJson.text(body,"callId");
                    if(!historyId.matches("[1-9][0-9]*"))throw new IllegalArgumentException("Identificador da ocorrência inválido.");
                    state.call(historyId);
                    if(historyRequest==null)throw new IllegalArgumentException("Consulta ao CAD indisponível nesta sessão.");
                    historyRequest.accept(historyId);
                    json(exchange,202,Java8.map("requested",true));return;
                case "/api/report":
                    CadSummary service=reports.get();
                    if(service==null||!service.hasFilters())throw new IllegalArgumentException("Conecte ao CAD e aguarde o catálogo de filtros.");
                    if(!reportGate.tryAcquire()){json(exchange,409,Java8.map("error","Consulta de somatório já em andamento; aguarde."));return;}
                    try {
                        Map<String,Object> result;
                        try {result=service.queryData(BotJson.text(body,"filterId")).get(90,TimeUnit.SECONDS);}
                        catch(ExecutionException failure){if(failure.getCause() instanceof IllegalArgumentException)throw (IllegalArgumentException)failure.getCause();throw failure;}
                        if(reports.get()!=service)throw new IllegalArgumentException("A sessão CAD mudou durante a consulta. Conecte e consulte novamente.");
                        json(exchange,200,result);return;
                    }finally{reportGate.release();}
                case "/api/settings":
                    String oldServer = BotJson.text(settings.get(), "server");
                    settings.save(body);
                    if (!oldServer.equals(BotJson.text(settings.get(), "server"))) routing.disconnect();
                    json(exchange, 200, settings.get()); return;
                case "/api/routing/connect":
                    routing.connect(BotJson.text(settings.get(), "server")); json(exchange, 200, routing.status()); return;
                case "/api/routing/disconnect":
                    routing.disconnect(); json(exchange, 200, routing.status()); return;
                case "/api/matrix":
                    Map<String,Object> matrixConfig = settings.get();
                    json(exchange, 200, routing.matrix(BotJson.text(matrixConfig,"server"), state.call(BotJson.text(body,"callId")),
                        BotJson.array(matrixConfig.get("bases")), find(matrixConfig,"profiles",BotJson.text(body,"profileId")))); return;
                case "/api/route":
                    Map<String, Object> config = settings.get();
                    Map<String, Object> call = state.call(BotJson.text(body, "callId"));
                    Map<String, Object> base = find(config, "bases", BotJson.text(body, "baseId"));
                    Map<String, Object> profile = find(config, "profiles", BotJson.text(body, "profileId"));
                    json(exchange, 200, routing.calculate(BotJson.text(config, "server"), call, base, profile)); return;
                default: json(exchange, 404, Java8.map("error", "Operação não encontrada.")); return;
                }
            }
            if (!method.equals("GET")) { json(exchange, 405, Java8.map("error", "Método não permitido.")); return; }
            String asset = path.equals("/") ? "/index.html" : path;
            if (asset.contains("..") || !(asset.equals("/index.html") || asset.startsWith("/assets/"))) {
                json(exchange, 404, Java8.map("error", "Arquivo não encontrado.")); return;
            }
            try (InputStream input = CadWebServer.class.getResourceAsStream("/web" + asset)) {
                if (input == null) { json(exchange, 404, Java8.map("error", "Frontend ausente. Execute npm ci e npm run build em frontend antes de mvn package.")); return; }
                String type = asset.endsWith(".html") ? "text/html; charset=UTF-8" : asset.endsWith(".js") ? "text/javascript; charset=UTF-8"
                    : asset.endsWith(".css") ? "text/css; charset=UTF-8" : asset.endsWith(".csv") ? "text/csv; charset=UTF-8" : asset.endsWith(".png") ? "image/png" : "application/octet-stream";
                send(exchange, 200, type, input.readAllBytes());
            }
        } catch (IllegalArgumentException e) { json(exchange, 400, Java8.map("error", e.getMessage())); }
        catch (LinkageError e) {
            json(exchange,503,Java8.map("error","As classes da aplicação estão indisponíveis ou incompatíveis. Feche completamente o CAD-Reader e reabra o JAR atualizado. Se persistir, confira se está usando o JAR completo e correto."));
        }
        catch (Exception e) {
            // Não devolve URL, respostas externas, dados CAD ou segredos de exceções.
            json(exchange, 502, Java8.map("error", "Não foi possível concluir (" + e.getClass().getSimpleName() + "). Verifique endereço, proxy, rede e disponibilidade do serviço."));
        } finally { exchange.close(); }
    }
    private static Map<String, Object> find(Map<String, Object> config, String collection, String id) {
        for (Object value : BotJson.array(config.get(collection))) {
            Map<String, Object> item = BotJson.object(value);
            if (id.equals(BotJson.text(item, "id"))) return item;
        }
        throw new IllegalArgumentException("Base ou perfil não encontrado. Atualize a página.");
    }
    private static boolean notModified(HttpExchange exchange,String version) throws IOException {
        String etag = "\""+version+"\"";
        exchange.getResponseHeaders().set("ETag",etag);
        if(etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
            exchange.sendResponseHeaders(304,-1); return true;
        }
        return false;
    }
    private static void json(HttpExchange exchange, int status, Object value) throws IOException {
        send(exchange, status, "application/json; charset=UTF-8", BotJson.write(value).getBytes(StandardCharsets.UTF_8));
    }
    private static void send(HttpExchange exchange, int status, String type, byte[] data) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, data.length);
        exchange.getResponseBody().write(data);
    }
    public void close() { server.stop(0); workers.shutdownNow(); routing.disconnect(); }
}
