package br.gov.mg.sids.reader;

import java.net.*;
import java.net.http.*;
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
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private volatile boolean closed;
    private volatile String status = "conectando…";
    private volatile WebSocket socket;
    private String url, resumeUrl, session;
    private Object sequence;
    private boolean awaitingAck;
    private ScheduledFuture<?> heartbeat, reconnect;
    private int retry;
    private CompletableFuture<Void> writes = CompletableFuture.completedFuture(null);
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
        client.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .buildAsync(URI.create(target.replaceAll("/+$", "") + "/?v=10&encoding=json"), new Listener())
            .whenComplete((ws, error) -> {
                if (error != null)
                    retry(null, 0);
                else if (closed)
                    ws.abort();
            });
    }
    private synchronized void send(WebSocket ws, Object payload) {
        if (closed || ws != socket)
            return;
        String text = BotJson.write(payload);
        writes = writes.thenCompose(ignored -> ws.sendText(text, true)).thenAccept(ignored -> {});
        writes.whenComplete((v, error) -> {
            if (error != null)
                retry(ws, 0);
        });
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
                     Map.of("op", 6, "d", Map.of("token", token, "session_id", session, "seq", sequence)));
            else
                send(ws, Map.of("op", 2, "d",
                                Map.of("token", token, "intents", 0, "properties",
                                       Map.of("os", System.getProperty("os.name"), "browser", "cad-reader",
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
            socket.abort();
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
            socket.abort();
        timer.shutdownNow();
    }
    private final class Listener implements WebSocket.Listener {
        private final StringBuilder fragments = new StringBuilder();
        public void onOpen(WebSocket ws) {
            synchronized (DiscordBotGateway.this) {
                if (closed) {
                    ws.abort();
                    return;
                }
                socket = ws;
                writes = CompletableFuture.completedFuture(null);
            }
            ws.request(1);
        }
        public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
            fragments.append(text);
            if (fragments.length() > 2_000_000) {
                retry(ws, 0);
                return null;
            }
            if (last) {
                try {
                    event(ws, BotJson.object(BotJson.parse(fragments.toString())));
                } catch (Exception e) {
                    retry(ws, 0);
                }
                fragments.setLength(0);
            }
            ws.request(1);
            return CompletableFuture.completedFuture(null);
        }
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            retry(ws, code);
            return CompletableFuture.completedFuture(null);
        }
        public void onError(WebSocket ws, Throwable error) {
            retry(ws, 0);
        }
    }
}
