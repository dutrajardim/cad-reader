package br.gov.mg.sids.reader;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** REST autenticado do bot. Só HTTP 429 é repetido; falhas ambíguas não repetem publicações. */
class DiscordBotApi implements AutoCloseable {
    private final String token;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private volatile boolean closed;
    private volatile long blockedUntil;
    DiscordBotApi(String token) {
        if (!token.matches("[A-Za-z0-9._-]{20,200}"))
            throw new IllegalArgumentException(
                "Token do bot inválido; copie apenas o token, sem prefixo Bot ou espaços");
        this.token = token;
    }
    Object request(String method, String path, Object body) throws Exception {
        if (!path.startsWith("/") || path.contains(".."))
            throw new IllegalArgumentException("Rota inválida");
        for (int attempt = 0; attempt < 5; attempt++) {
            awaitLimit();
            HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create("https://discord.com/api/v10" + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bot " + token)
                    .header("User-Agent", "CAD-Reader (Java, 1.0)");
            HttpRequest.BodyPublisher payload =
                body == null ? HttpRequest.BodyPublishers.noBody()
                             : HttpRequest.BodyPublishers.ofString(BotJson.write(body),
                                                                   java.nio.charset.StandardCharsets.UTF_8);
            if (body != null)
                builder.header("Content-Type", "application/json");
            HttpResponse<String> response =
                client.send(builder.method(method, payload).build(), HttpResponse.BodyHandlers.ofString());
            Object data;
            try {
                data = response.body().isBlank() ? Map.of() : BotJson.parse(response.body());
            } catch (IllegalArgumentException invalidJson) {
                // Um proxy pode devolver HTML em vez de JSON. Preserve o status sem expor o corpo.
                if (response.statusCode() >= 200 && response.statusCode() < 300) throw invalidJson;
                data = Map.of();
            }
            if (response.statusCode() == 429) {
                double seconds =
                    Double.parseDouble(BotJson.object(data).getOrDefault("retry_after", 5).toString());
                blockedUntil = System.nanoTime() + (long)(Math.max(1, seconds) * 1_000_000_000L);
                continue;
            }
            if ("0".equals(response.headers().firstValue("X-RateLimit-Remaining").orElse(""))) {
                double seconds =
                    Double.parseDouble(response.headers().firstValue("X-RateLimit-Reset-After").orElse("1"));
                blockedUntil = System.nanoTime() + (long)(Math.max(0, seconds) * 1_000_000_000L);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw failure(response.statusCode(), data);
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
    public void close() {
        closed = true;
    }
}
