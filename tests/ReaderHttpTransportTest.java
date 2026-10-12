package br.gov.mg.sids.reader;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** HTTP real somente em localhost, sem CAD/Discord ou credenciais operacionais. */
public final class ReaderHttpTransportTest {
    static void check(boolean value, String message) { ReaderIntegrationTest.check(value, message); }
    static void verifyHttp() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        Set<Integer> ports = ConcurrentHashMap.newKeySet();
        List<String> requestBodies = new CopyOnWriteArrayList<>();
        AtomicInteger dropped = new AtomicInteger(), redirected = new AtomicInteger();
        server.createContext("/send", exchange -> {
            try {
                check(exchange.getProtocol().equals("HTTP/1.1"), "HTTP/1.1");
                check(exchange.getRequestMethod().equals("POST"), "POST");
                check("close".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Connection")),
                      "Conexão encerrada por envio");
                byte[] data = exchange.getRequestBody().readAllBytes();
                check(Integer.parseInt(exchange.getRequestHeaders().getFirst("Content-Length"))
                          == data.length,
                      "Content-Length em bytes UTF-8");
                requestBodies.add(new String(data, StandardCharsets.UTF_8));
                ports.add(exchange.getRemoteAddress().getPort());
                byte[] response = "{\"channel_id\":\"999\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location", "/unexpected");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/unexpected", exchange -> {
            redirected.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/rate", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"retry_after\":1.5}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Retry-After", "1.5");
            exchange.sendResponseHeaders(429, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/drop", exchange -> {
            exchange.getRequestBody().readAllBytes();
            dropped.incrementAndGet();
            exchange.close();
        });
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        try (DiscordHttpTransport transport = new DiscordHttpTransport()) {
            String body = "{\"texto\":\"Ocorrência 🚒\"}";
            check(transport.send(base + "/send", body).status == 200, "Primeiro envio");
            Thread.sleep(200);
            check(transport.send(base + "/send", body).body.contains("999"), "Novo envio após ociosidade");
            check(ports.size() == 2 && requestBodies.equals(List.of(body, body)),
                  "Conexões diferentes sem alteração do payload");
            check(transport.send(base + "/redirect", body).status == 302 && redirected.get() == 0,
                  "Redirecionamento não seguido");
            DiscordPublisher.Result limited = transport.send(base + "/rate", body);
            check(limited.status == 429 && limited.retryAfter.equals("1.5")
                      && limited.body.contains("retry_after"),
                  "Preserva resposta HTTP 429");
            try {
                transport.send(base + "/drop", body);
                throw new AssertionError("Esperada interrupção HTTP");
            } catch (DiscordHttpTransport.Failure failure) {
                check(failure.stage.equals("aguardando resposta HTTP") && dropped.get() == 1,
                      "Interrupção após POST informa etapa e não repete publicação");
            }
            check(transport.send(base + "/send", body).status == 200, "Próximo envio usa conexão nova");
        } finally {
            server.stop(0);
        }
    }
    static final class Connection extends HttpURLConnection {
        String failAt;
        boolean disconnected;
        Connection(String failAt) throws Exception {
            super(new URL("https://example.invalid/secret-token"));
            this.failAt = failAt;
        }
        @Override
        public void connect() throws IOException {
            if (failAt.equals("connect"))
                throw new SocketTimeoutException("secret-token");
        }
        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }
        @Override
        public int getResponseCode() throws IOException {
            if (failAt.equals("headers"))
                throw new IOException("secret-token");
            return 200;
        }
        @Override
        public InputStream getInputStream() throws IOException {
            throw new IOException("secret-token");
        }
        @Override
        public String getHeaderField(String key) {
            return null;
        }
        @Override
        public void disconnect() {
            disconnected = true;
        }
        @Override
        public boolean usingProxy() {
            return false;
        }
    }
    static void verifyStagesAndPause() throws Exception {
        for (String point : List.of("connect", "headers", "body")) {
            Connection connection = new Connection(point);
            try (DiscordHttpTransport transport = new DiscordHttpTransport(url -> connection)) {
                try {
                    transport.send("https://example.invalid/secret-token", "{}");
                    throw new AssertionError("Falha esperada");
                } catch (DiscordHttpTransport.Failure failure) {
                    String expected = point.equals("connect")   ? "conexão TCP/TLS"
                                      : point.equals("headers") ? "aguardando resposta HTTP"
                                                                : "leitura da resposta HTTP";
                    check(failure.stage.equals(expected) && connection.disconnected,
                          "Etapa e liberação da conexão");
                }
            }
        }
        Path root = Files.createTempDirectory("cad-http-test-");
        List<String> logs = new CopyOnWriteArrayList<>();
        Connection connection = new Connection("headers");
        AtomicInteger calls = new AtomicInteger();
        try (DiscordPublisher publisher
             = new DiscordPublisher("https://discord.com/api/webhooks/123/fake", root, logs::add,
                                    new DiscordHttpTransport(url -> {
                                        calls.incrementAndGet();
                                        return connection;
                                    }),
                                    null, 0)) {
            publisher.publish(ReaderClearPendingTest.event("1"));
            ReaderIntegrationTest.waitFor(() -> publisher.status().contains("PAUSADA"));
            check(publisher.status().contains("etapa=aguardando resposta HTTP")
                      && logs.stream().noneMatch(s -> s.contains("secret-token")),
                  "Diagnóstico sem token");
            publisher.publish(ReaderClearPendingTest.event("2"));
            Thread.sleep(80);
            check(calls.get() == 1 && publisher.status().endsWith("Pendentes: 2"),
                  "Ambiguidade mantém fila durável sem novo POST automático");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                for (Path path : (Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)
                    Files.delete(path);
            }
        }
    }
    public static void main(String[] args) throws Exception {
        verifyStagesAndPause();
        if (args.length == 0 || !args[0].equals("--no-sockets"))
            verifyHttp();
        System.out.println("PASS transporte HTTP: etapas seguras, pausa durável, conexões novas, UTF-8, 429 "
                           + "e POST sem repetição");
    }
}
