package br.gov.mg.sids.reader;

import java.io.*;
import java.net.*;
import java.util.*;

/** REST autenticado do bot. Só HTTP 429 é repetido; falhas ambíguas não repetem publicações. */
class DiscordBotApi implements AutoCloseable {
    /** O Discord usa somente HTTPS; proxy explícito evita depender da descoberta automática do OkHttp. */
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
    static okhttp3.OkHttpClient.Builder networkBuilder() {
        okhttp3.OkHttpClient.Builder builder = new okhttp3.OkHttpClient.Builder();
        Proxy proxy = discordProxy();
        if (proxy != null) builder.proxy(proxy);
        else builder.proxySelector(ProxySelector.getDefault());
        return builder;
    }
    interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }
    private final String token;
    private final ConnectionFactory factory;
    private volatile boolean closed;
    private volatile long blockedUntil;
    DiscordBotApi(String token) { this(token, DiscordBotApi::openDefault); }
    DiscordBotApi(String token, ConnectionFactory factory) {
        if (!token.matches("[A-Za-z0-9._-]{20,200}"))
            throw new IllegalArgumentException(
                "Token do bot inválido; copie apenas o token, sem prefixo Bot ou espaços");
        this.token = token;
        this.factory = factory;
    }
    private static HttpURLConnection openDefault(URL target) throws IOException {
        Proxy proxy = discordProxy();
        return (HttpURLConnection)(proxy == null ? target.openConnection() : target.openConnection(proxy));
    }
    Object request(String method, String path, Object body) throws Exception {
        if (!path.startsWith("/") || path.contains(".."))
            throw new IllegalArgumentException("Rota inválida");
        for (int attempt = 0; attempt < 5; attempt++) {
            awaitLimit();
            HttpURLConnection connection = null;
            try {
                URL target = URI.create("https://discord.com/api/v10" + path).toURL();
                connection = factory.open(target);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestMethod(method);
                connection.setRequestProperty("Authorization", "Bot " + token);
                connection.setRequestProperty("User-Agent", "CAD-Reader (Java, 1.0)");
                connection.setRequestProperty("Accept", "application/json");
                byte[] payload = body == null ? null
                    : BotJson.write(body).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (payload != null || method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
                    if (payload == null) payload = new byte[0];
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    connection.setFixedLengthStreamingMode(payload.length);
                    try (OutputStream output = connection.getOutputStream()) { output.write(payload); }
                }
                int status = connection.getResponseCode();
                String remaining = connection.getHeaderField("X-RateLimit-Remaining");
                String reset = connection.getHeaderField("X-RateLimit-Reset-After");
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String response = "";
                if (stream != null)
                    try (InputStream input = stream) {
                        response = new String(Java8.readAllBytes(input), java.nio.charset.StandardCharsets.UTF_8);
                    }
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
            } finally {
                if (connection != null) connection.disconnect();
            }
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
