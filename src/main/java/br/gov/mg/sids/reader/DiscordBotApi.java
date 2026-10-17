package br.gov.mg.sids.reader;

import java.io.*;
import java.net.*;
import java.util.*;

/** REST autenticado do bot. Só HTTP 429 é repetido; falhas ambíguas não repetem publicações. */
class DiscordBotApi implements AutoCloseable {
    private final String token;
    private final okhttp3.OkHttpClient client;
    private static okhttp3.OkHttpClient defaultClient() { return new okhttp3.OkHttpClient.Builder()
        .proxySelector(ProxySelector.getDefault())
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(); }
    private volatile boolean closed;
    private volatile long blockedUntil;
    DiscordBotApi(String token) {
        this(token, defaultClient());
    }
    /** Cliente substituível para testar HTTP sem credenciais ou serviços reais. */
    DiscordBotApi(String token, okhttp3.OkHttpClient client) {
        if (!token.matches("[A-Za-z0-9._-]{20,200}"))
            throw new IllegalArgumentException(
                "Token do bot inválido; copie apenas o token, sem prefixo Bot ou espaços");
        this.token = token;
        this.client = client;
    }
    Object request(String method, String path, Object body) throws Exception {
        if (!path.startsWith("/") || path.contains(".."))
            throw new IllegalArgumentException("Rota inválida");
        for (int attempt = 0; attempt < 5; attempt++) {
            awaitLimit();
            okhttp3.RequestBody payload = body == null ? null
                : okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json; charset=utf-8"), BotJson.write(body));
            if (payload == null && (method.equals("POST") || method.equals("PUT") || method.equals("PATCH")))
                payload = okhttp3.RequestBody.create(null, new byte[0]);
            okhttp3.Request request = new okhttp3.Request.Builder()
                .url("https://discord.com/api/v10" + path)
                .header("Authorization", "Bot " + token)
                .header("User-Agent", "CAD-Reader (Java, 1.0)")
                .method(method, payload).build();
            int status;
            String response, remaining, reset;
            try (okhttp3.Response result = client.newCall(request).execute()) {
                status = result.code();
                response = result.body() == null ? "" : result.body().string();
                remaining = result.header("X-RateLimit-Remaining");
                reset = result.header("X-RateLimit-Reset-After");
            }
            Object data;
            try {
                data = response.trim().isEmpty() ? Java8.map() : BotJson.parse(response);
            } catch (IllegalArgumentException invalidJson) {
                // Um proxy pode devolver HTML em vez de JSON. Preserve o status sem expor o corpo.
                if (status >= 200 && status < 300) throw invalidJson;
                data = Java8.map();
            }
            if (status == 429) {
                double seconds =
                    Double.parseDouble(BotJson.object(data).getOrDefault("retry_after", 5).toString());
                blockedUntil = System.nanoTime() + (long)(Math.max(1, seconds) * 1_000_000_000L);
                continue;
            }
            if ("0".equals(remaining)) {
                double seconds =
                    Double.parseDouble(reset == null ? "1" : reset);
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
    public void close() {
        closed = true;
        client.dispatcher().cancelAll();
        client.dispatcher().executorService().shutdownNow();
        client.connectionPool().evictAll();
    }
}
