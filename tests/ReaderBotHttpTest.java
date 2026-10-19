package br.gov.mg.sids.reader;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;

/** Exercita o REST do bot e o envio real de PATCH pelo cliente HTTP do Java 11. */
public final class ReaderBotHttpTest {
    static void check(boolean condition, String message) {
        ReaderIntegrationTest.check(condition, message);
    }
    static String body(HttpRequest request) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompletableFuture<String> result = new CompletableFuture<>();
        request.bodyPublisher().get().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) {
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.write(part, 0, part.length);
            }
            public void onError(Throwable error) { result.completeExceptionally(error); }
            public void onComplete() { result.complete(new String(bytes.toByteArray(), StandardCharsets.UTF_8)); }
        });
        return result.get(5, TimeUnit.SECONDS);
    }
    public static void main(String[] args) throws Exception {
        List<String> methods = new ArrayList<>();
        AtomicInteger limited = new AtomicInteger(), failed = new AtomicInteger();
        DiscordBotApi.Transport transport = request -> {
            methods.add(request.method());
            check(request.headers().firstValue("Authorization").get().startsWith("Bot "), "Autenticação preservada");
            check(request.timeout().get().getSeconds() == 30, "Timeout configurado");
            String path = request.uri().getPath();
            if (path.endsWith("/failure")) {
                failed.incrementAndGet();
                throw new IOException("Falha simulada");
            }
            if (request.method().equals("PATCH")) {
                try { check(body(request).contains("🚒"), "Corpo PATCH preserva UTF-8"); }
                catch (Exception e) { throw new IOException(e); }
            }
            int status = path.endsWith("/limited") && limited.getAndIncrement() == 0 ? 429 : 200;
            if (path.endsWith("/forbidden")) status = 403;
            return new DiscordBotApi.Response(status, HttpHeaders.of(Collections.emptyMap(), (k,v) -> true),
                status == 429 ? "{\"retry_after\":0.001}" : status == 403 ? "{\"code\":50013}" : "{\"ok\":true}");
        };
        try (DiscordBotApi api = new DiscordBotApi("token-ficticio-00000000000", transport)) {
            api.request("GET", "/channels/1", null);
            api.request("PATCH", "/webhooks/1/messages/@original", Java8.map("content", "🚒"));
            api.request("DELETE", "/channels/1/messages/2", null);
            api.request("POST", "/limited", Java8.map("content", "teste"));
            check(methods.equals(Arrays.asList("GET","PATCH","DELETE","POST","POST")),
                "Métodos REST e repetição somente após 429");
            try { api.request("POST", "/failure", Java8.map("content","teste")); throw new AssertionError("Falha esperada"); }
            catch (IOException expected) { check(failed.get()==1, "Falha ambígua não repete POST"); }
            try { api.request("GET", "/forbidden", null); throw new AssertionError("403 esperado"); }
            catch (DiscordBotApi.ApiFailure expected) { check(expected.status == 403 && expected.code.equals("50013"), "Diagnóstico HTTP preservado"); }
        }
        if (!Arrays.asList(args).contains("--no-sockets")) realPatch();
        System.out.println("PASS REST do bot: GET, PATCH, DELETE, UTF-8, 429 e falha sem repetição");
    }
    static void realPatch() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CompletableFuture<String> received = new CompletableFuture<>();
        server.createContext("/", exchange -> {
            try {
                String text = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                received.complete(exchange.getRequestMethod() + " " + text);
                exchange.sendResponseHeaders(204, -1);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))
                .method("PATCH", HttpRequest.BodyPublishers.ofString("relatório 🚒")).build();
            DiscordBotApi.Response response = DiscordBotApi.defaultTransport().send(request);
            check(response.status == 204 && received.get(5, TimeUnit.SECONDS).equals("PATCH relatório 🚒"),
                "Transporte real envia PATCH e UTF-8 sem simular setRequestMethod");
        } finally { server.stop(0); }
    }
}
