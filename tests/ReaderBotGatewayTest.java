package br.gov.mg.sids.reader;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

/** Gateway sem rede: Identify, heartbeat, ACK, sequência e dispatch de interações. */
public final class ReaderBotGatewayTest {
    static final class Socket implements WebSocket {
        final List<Map<String, Object>> sent = new CopyOnWriteArrayList<>();
        boolean aborted;
        public CompletableFuture<WebSocket> sendText(CharSequence text, boolean last) {
            sent.add(BotJson.object(BotJson.parse(text.toString())));
            return CompletableFuture.completedFuture(this);
        }
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            return CompletableFuture.completedFuture(this);
        }
        public void request(long n) { }
        public String getSubprotocol() { return ""; }
        public boolean isOutputClosed() { return aborted; }
        public boolean isInputClosed() { return aborted; }
        public void abort() { aborted = true; }
    }
    static void check(boolean condition, String message) {
        ReaderIntegrationTest.check(condition, message);
    }
    public static void main(String[] args) throws Exception {
        List<Map<String, Object>> interactions = new ArrayList<>();
        DiscordBotApi api = new DiscordBotApi(Java8.repeat("a", 30));
        Socket ws = new Socket();
        try (DiscordBotGateway gateway =
                 new DiscordBotGateway(Java8.repeat("a", 30), api, interactions::add, m -> {})) {
            java.lang.reflect.Field socket = DiscordBotGateway.class.getDeclaredField("socket");
            socket.setAccessible(true);
            socket.set(gateway, ws);
            java.lang.reflect.Method event =
                DiscordBotGateway.class.getDeclaredMethod("event", WebSocket.class, Map.class);
            event.setAccessible(true);
            java.lang.reflect.Method beat =
                DiscordBotGateway.class.getDeclaredMethod("beat", WebSocket.class);
            beat.setAccessible(true);
            event.invoke(gateway, ws, Java8.map("op", 10, "d", Java8.map("heartbeat_interval", 300000)));
            check(ws.sent.stream().anyMatch(
                      m
                      -> BotJson.text(m, "op").equals("2") &&
                             BotJson.text(BotJson.object(m.get("d")), "intents").equals("0")),
                  "Identify sem intent de conteúdo");
            event.invoke(gateway, ws,
                         Java8.map("op", 0, "s", 42, "t", "READY", "d",
                                Java8.map("session_id", "test-session", "resume_gateway_url",
                                       "wss://gateway.discord.gg")));
            event.invoke(gateway, ws, Java8.map("op", 11));
            beat.invoke(gateway, ws);
            Map<String, Object> last = ws.sent.get(ws.sent.size() - 1);
            check(BotJson.text(last, "op").equals("1") && BotJson.text(last, "d").equals("42"),
                  "Heartbeat carrega sequência");
            event.invoke(gateway, ws, Java8.map("op", 11));
            event.invoke(gateway, ws,
                         Java8.map("op", 0, "s", 43, "t", "INTERACTION_CREATE", "d", Java8.map("id", "example")));
            check(interactions.size() == 1 && BotJson.text(interactions.get(0), "id").equals("example"),
                  "Interação despachada");
            event.invoke(gateway, ws, Java8.map("op", 10, "d", Java8.map("heartbeat_interval", 300000)));
            check(ws.sent.stream().anyMatch(
                      m
                      -> BotJson.text(m, "op").equals("6") &&
                             BotJson.text(BotJson.object(m.get("d")), "session_id").equals("test-session")),
                  "Sessão existente usa Resume");
        } finally {
            api.close();
        }
        check(ws.aborted, "Fechar bot encerra WebSocket");
        System.out.println("PASS Gateway: Identify mínimo, sequência, heartbeat/ACK, Resume, interações e " +
                           "encerramento sem rede");
    }
}
