package br.gov.mg.sids.reader;

import java.net.*;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.Request;
import okhttp3.Response;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Gateway v10 somente para interações. Mantém heartbeat, ACK e retomada de sessão. */
final class DiscordBotGateway implements AutoCloseable {
    private final String token;
    private final DiscordBotApi api;
    private final Consumer<Map<String, Object>> interaction;
    private final Consumer<String> log;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final Proxy gatewayProxy = resolveGatewayProxy();
    private final okhttp3.OkHttpClient client = gatewayClient(gatewayProxy);

    /**
     * Reproduz para o WebSocket a descoberta que URLConnection faz no JDK. O OkHttp recebe
     * um único proxy já resolvido para o host do Gateway, evitando diferenças de seleção.
     */
    static Proxy resolveGatewayProxy() {
        Proxy explicit = DiscordBotApi.discordProxy();
        if (explicit != null)
            return explicit;
        ProxySelector selector = ProxySelector.getDefault();
        if (selector == null)
            return Proxy.NO_PROXY;
        try {
            List<Proxy> proxies = selector.select(URI.create("https://gateway.discord.gg/"));
            if (proxies != null)
                for (Proxy proxy : proxies)
                    if (proxy != null && proxy.type() != Proxy.Type.DIRECT)
                        return proxy;
        } catch (RuntimeException ignored) { }
        return Proxy.NO_PROXY;
    }

    private static okhttp3.OkHttpClient gatewayClient(Proxy proxy) {
        okhttp3.OkHttpClient.Builder builder = new okhttp3.OkHttpClient.Builder();
        if (proxy != null && proxy.type() != Proxy.Type.DIRECT) {
            builder.proxy(proxy);
            builder.connectTimeout(45, TimeUnit.SECONDS);
        } else {
            builder.proxy(Proxy.NO_PROXY);
            builder.connectTimeout(20, TimeUnit.SECONDS);
        }
        return builder.readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build();
    }
    private volatile boolean closed;
    private volatile String status = "conectando…";
    private volatile WebSocket socket;
    private String url, resumeUrl, session;
    private Object sequence;
    private boolean awaitingAck;
    private ScheduledFuture<?> heartbeat, reconnect;
    private int retry;
    DiscordBotGateway(String token, DiscordBotApi api, Consumer<Map<String, Object>> interaction,
                      Consumer<String> log) {
        this.token = token;
        this.api = api;
        this.interaction = interaction;
        this.log = log;
    }
    void start() throws Exception {
        url = BotJson.text(BotJson.object(api.request("GET", "/gateway/bot", null)), "url");
        connect();
    }
    private synchronized void connect() {
        if (closed)
            return;
        String target = session != null && resumeUrl != null ? resumeUrl : url;
        if (target == null || !target.startsWith("wss://") ||
            !(URI.create(target).getHost().equals("gateway.discord.gg") ||
              URI.create(target).getHost().endsWith(".discord.gg"))) {
            status = "endereço Gateway inválido; reinicie o bot";
            log.accept("Bot: endereço Gateway inválido; reinicie após conferir configuração.");
            return;
        }
        status = session == null ? "conectando…" : "retomando conexão…";
        socket = client.newWebSocket(new Request.Builder()
            .url(target.replaceAll("/+$", "") + "/?v=10&encoding=json").build(), new Listener());
    }
    private synchronized void send(WebSocket ws, Object payload) {
        if (closed || ws != socket) return;
        if (!ws.send(BotJson.write(payload))) retry(ws, 0);
    }
    private synchronized void beat(WebSocket ws) {
        if (ws != socket || closed)
            return;
        if (awaitingAck) {
            retry(ws, 0);
            return;
        }
        awaitingAck = true;
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("op", 1);
        message.put("d", sequence);
        send(ws, message);
    }
    private synchronized void event(WebSocket ws, Map<String, Object> packet) {
        if (ws != socket || closed)
            return;
        if (packet.get("s") != null)
            sequence = packet.get("s");
        String op = BotJson.text(packet, "op");
        Map<String, Object> data = BotJson.object(packet.get("d"));
        if (op.equals("10")) {
            awaitingAck = false;
            long interval = Long.parseLong(BotJson.text(data, "heartbeat_interval"));
            if (interval < 1000 || interval > 300000) {
                retry(ws, 0);
                return;
            }
            if (heartbeat != null)
                heartbeat.cancel(false);
            heartbeat = timer.scheduleAtFixedRate(()
                                                      -> beat(ws),
                                                  ThreadLocalRandom.current().nextLong(interval), interval,
                                                  TimeUnit.MILLISECONDS);
            if (session != null)
                send(ws,
                     Java8.map("op", 6, "d", Java8.map("token", token, "session_id", session, "seq", sequence)));
            else
                send(ws, Java8.map("op", 2, "d",
                                Java8.map("token", token, "intents", 0, "properties",
                                       Java8.map("os", System.getProperty("os.name"), "browser", "cad-reader",
                                              "device", "cad-reader"))));
        } else if (op.equals("11"))
            awaitingAck = false;
        else if (op.equals("1")) {
            awaitingAck = false;
            beat(ws);
        } else if (op.equals("7"))
            retry(ws, 0);
        else if (op.equals("9")) {
            if (!Boolean.TRUE.equals(packet.get("d"))) {
                session = null;
                sequence = null;
                resumeUrl = null;
            }
            retry(ws, 0);
        } else if (op.equals("0")) {
            String name = BotJson.text(packet, "t");
            if (name.equals("READY")) {
                session = BotJson.text(data, "session_id");
                resumeUrl = BotJson.text(data, "resume_gateway_url");
                retry = 0;
                status = "conectado";
                log.accept("Bot: conectado; comandos /cad-reader disponíveis no servidor configurado.");
            } else if (name.equals("RESUMED")) {
                retry = 0;
                status = "conectado";
                log.accept("Bot: conexão retomada.");
            } else if (name.equals("INTERACTION_CREATE"))
                interaction.accept(data);
        }
    }
    private synchronized void retry(WebSocket ws, int code) {
        if (closed || (ws != null && ws != socket))
            return;
        if (heartbeat != null)
            heartbeat.cancel(false);
        if (socket != null)
            socket.cancel();
        socket = null;
        if (code == 4004 || code == 4010 || code == 4011 || code == 4012 || code == 4013 || code == 4014) {
            closed = true;
            status = "conexão recusada (" + code + "); confira a configuração e reinicie o bot";
            log.accept("Bot: Gateway recusado (código " + code +
                       "); confira token/configuração e reinicie o bot.");
            return;
        }
        if (code == 4007 || code == 4009) {
            session = null;
            sequence = null;
            resumeUrl = null;
        }
        if (reconnect != null && !reconnect.isDone())
            return;
        long delay = Math.min(60, 5L << Math.min(retry++, 4));
        status = "desconectado; nova tentativa em " + delay + "s";
        log.accept("Bot: desconectado; nova tentativa em " + delay + "s.");
        reconnect = timer.schedule(() -> {
            synchronized (this) {
                reconnect = null;
            }
            connect();
        }, delay, TimeUnit.SECONDS);
    }
    String status() { return status; }

    public synchronized void close() {
        status = "parado";
        closed = true;
        if (socket != null)
            socket.cancel();
        timer.shutdownNow();
        client.dispatcher().cancelAll();
        client.dispatcher().executorService().shutdownNow();
        client.connectionPool().evictAll();
    }
    private final class Listener extends WebSocketListener {
        @Override public void onOpen(WebSocket ws, Response response) {
            synchronized (DiscordBotGateway.this) {
                if (closed || ws != socket) ws.cancel();
            }
        }
        @Override public void onMessage(WebSocket ws, String text) {
            if (text.length() > 2_000_000) { retry(ws, 0); return; }
            try { event(ws, BotJson.object(BotJson.parse(text))); }
            catch (Exception e) { retry(ws, 0); }
        }
        @Override public void onClosing(WebSocket ws, int code, String reason) {
            ws.close(code, "");
        }
        @Override public void onClosed(WebSocket ws, int code, String reason) { retry(ws, code); }
        @Override public void onFailure(WebSocket ws, Throwable error, Response response) {
            // Não registrar URLs, headers nem tokens: somente a categoria da falha.
            String kind = error instanceof java.net.UnknownHostException ? "DNS"
                : error instanceof java.net.ConnectException ? "conexão TCP/proxy"
                : error instanceof java.net.SocketTimeoutException ? "timeout"
                : error instanceof javax.net.ssl.SSLException ? "TLS"
                : error instanceof java.net.ProtocolException ? "protocolo"
                : "transporte (" + error.getClass().getSimpleName() + ")";
            String route = gatewayProxy != null && gatewayProxy.type() != Proxy.Type.DIRECT
                ? "proxy selecionado pelo Java" : "conexão direta";
            log.accept("Bot: falha no WebSocket Discord: " + kind
                + (response == null ? "" : "; HTTP " + response.code())
                + "; " + route
                + ". Confira se o proxy permite CONNECT/WebSocket para gateway.discord.gg:443.");
            retry(ws, 0);
        }
    }
}
