package br.gov.mg.sids.reader;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** POST isolado por lote: não conserva conexão ociosa entre publicações do Discord. */
final class DiscordHttpTransport implements DiscordPublisher.Transport, AutoCloseable {
    interface ConnectionFactory {
        HttpURLConnection open(String url) throws IOException;
    }
    private final ConnectionFactory factory;
    private volatile HttpURLConnection active;
    private volatile boolean closed;

    DiscordHttpTransport() { this(url -> (HttpURLConnection)URI.create(url).toURL().openConnection()); }
    /** A fábrica substituível permite verificar o transporte sem acessar o Discord. */
    DiscordHttpTransport(ConnectionFactory factory) { this.factory = factory; }

    /** A etapa é uma descrição fixa, nunca uma mensagem que possa conter o token. */
    static final class Failure extends IOException {
        final String stage;
        Failure(String stage, IOException cause) {
            super("Falha no transporte Discord", cause);
            this.stage = stage;
        }
    }

    @Override
    public DiscordPublisher.Result send(String url, String body) throws IOException {
        String stage = "preparação da conexão";
        HttpURLConnection connection = null;
        try {
            connection = factory.open(url);
            synchronized (this) {
                if (closed)
                    throw new IOException("Transporte encerrado");
                active = connection;
            }
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(25000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Connection", "close");
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            // Streaming com tamanho explícito evita buffering/repetição do POST no transporte.
            connection.setFixedLengthStreamingMode(payload.length);
            stage = "conexão TCP/TLS";
            connection.connect();
            stage = "envio do conteúdo";
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            stage = "aguardando resposta HTTP";
            int status = connection.getResponseCode();
            if (status < 100)
                throw new IOException("Resposta HTTP ausente");
            String retryAfter = connection.getHeaderField("Retry-After");
            stage = "leitura da resposta HTTP";
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = "";
            if (stream != null) {
                try (InputStream input = stream) {
                    response = new String(Java8.readAllBytes(input), StandardCharsets.UTF_8);
                }
            }
            return new DiscordPublisher.Result(status, response, retryAfter == null ? "" : retryAfter);
        } catch (IOException error) {
            throw new Failure(stage, error);
        } finally {
            if (connection != null)
                connection.disconnect();
            synchronized (this) {
                if (active == connection)
                    active = null;
            }
        }
    }

    /** Desconectar interrompe também uma leitura que esteja aguardando a resposta. */
    @Override
    public synchronized void close() {
        closed = true;
        if (active != null)
            active.disconnect();
    }
}
