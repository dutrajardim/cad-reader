package br.gov.mg.sids.reader;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** REST autenticado do bot. Só HTTP 429 é repetido; falhas ambíguas não repetem publicações. */
class DiscordBotApi implements AutoCloseable {
    /** Proxy explícito preserva as configurações HTTPS/HTTP da aplicação. */
    static Proxy discordProxy() {
        String host = System.getProperty("https.proxyHost", "").trim();
        String port = System.getProperty("https.proxyPort", "").trim();
        if (host.isEmpty()) {
            host = System.getProperty("http.proxyHost", "").trim();
            port = System.getProperty("http.proxyPort", "").trim();
        }
        if (!host.isEmpty()) {
            try {
                int value = Integer.parseInt(port);
                if (value > 0 && value <= 65535)
                    return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, value));
            } catch (NumberFormatException ignored) { }
        }
        return null;
    }
    interface Transport { Response send(HttpRequest request) throws IOException, InterruptedException; }
    static final class Response {
        final int status;
        final HttpHeaders headers;
        final String body;
        Response(int status, HttpHeaders headers, String body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }
    }
    private final String token;
    private final Transport transport;
    private volatile boolean closed;
    private volatile long blockedUntil;
    DiscordBotApi(String token) { this(token, defaultTransport()); }
    DiscordBotApi(String token, Transport transport) {
        if (!token.matches("[A-Za-z0-9._-]{20,200}"))
            throw new IllegalArgumentException(
                "Token do bot inválido; copie apenas o token, sem prefixo Bot ou espaços");
        this.token = token;
        this.transport = transport;
    }
    static Transport defaultTransport() {
        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER);
        Proxy proxy = discordProxy();
        if (proxy != null)
            builder.proxy(ProxySelector.of((InetSocketAddress)proxy.address()));
        HttpClient client = builder.build();
        return request -> {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.headers(), response.body());
        };
    }
    Object request(String method, String path, Object body) throws Exception {
        if (!path.startsWith("/") || path.contains(".."))
            throw new IllegalArgumentException("Rota inválida");
        for (int attempt = 0; attempt < 5; attempt++) {
            awaitLimit();
            byte[] payload = body == null ? null : BotJson.write(body).getBytes(StandardCharsets.UTF_8);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://discord.com/api/v10" + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bot " + token)
                .header("User-Agent", "CAD-Reader (Java, 1.0)")
                .header("Accept", "application/json");
            if (payload != null || method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
                if (payload == null) payload = new byte[0];
                builder.header("Content-Type", "application/json; charset=UTF-8");
            }
            builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                                                   : HttpRequest.BodyPublishers.ofByteArray(payload));
            Response result = transport.send(builder.build());
            int status = result.status;
            String remaining = result.headers.firstValue("X-RateLimit-Remaining").orElse(null);
            String reset = result.headers.firstValue("X-RateLimit-Reset-After").orElse(null);
            String response = result.body;
            Object data;
            try {
                data = response.trim().isEmpty() ? Java8.map() : BotJson.parse(response);
            } catch (IllegalArgumentException invalidJson) {
                if (status >= 200 && status < 300) throw invalidJson;
                data = Java8.map();
            }
            if (status == 429) {
                double seconds = Double.parseDouble(BotJson.object(data).getOrDefault("retry_after", 5).toString());
                blockedUntil = System.nanoTime() + (long)(Math.max(1, seconds) * 1_000_000_000L);
                continue;
            }
            if ("0".equals(remaining)) {
                double seconds = Double.parseDouble(reset == null ? "1" : reset);
                blockedUntil = System.nanoTime() + (long)(Math.max(0, seconds) * 1_000_000_000L);
            }
            if (status < 200 || status >= 300)
                throw failure(status, data);
            return data;
        }
        throw new IOException("Discord manteve limite de requisições; tente novamente mais tarde");
    }
    private void awaitLimit() throws InterruptedException, IOException {
        while (!closed && System.nanoTime() < blockedUntil)
            Thread.sleep(Math.min(250, Math.max(1, (blockedUntil - System.nanoTime()) / 1_000_000)));
        if (closed || Thread.currentThread().isInterrupted())
            throw new IOException("Bot encerrado");
    }
    /** Extrai apenas o código numérico; nunca inclui corpo, URL ou token no diagnóstico. */
    static ApiFailure failure(int status, Object data) {
        String code = BotJson.text(BotJson.object(data), "code");
        return new ApiFailure(status, code.matches("[0-9]{1,12}") ? code : "");
    }
    static final class ApiFailure extends IOException {
        final int status;
        final String code;
        ApiFailure(int status) { this(status, ""); }
        ApiFailure(int status, String code) {
            super("Discord HTTP " + status + (code.isEmpty() ? "" : "; código=" + code));
            this.status = status;
            this.code = code;
        }
    }
    public void close() { closed = true; }
}
