package br.gov.mg.sids.reader;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercita serialização REST, PATCH e limites pelo transporte HttpURLConnection do Java 8. */
public final class ReaderBotHttpTest {
    static final class FakeConnection extends HttpURLConnection {
        final List<String> methods;
        final AtomicInteger limited, failed;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        int status = 200;
        String response = "{\"ok\":true}";
        FakeConnection(URL url, List<String> methods, AtomicInteger limited, AtomicInteger failed) {
            super(url); this.methods=methods; this.limited=limited; this.failed=failed;
        }
        public void connect() {}
        public void disconnect() {}
        public boolean usingProxy() { return false; }
        public void setRequestMethod(String method) throws ProtocolException { this.method=method; methods.add(method); }
        public OutputStream getOutputStream() throws IOException {
            if (url.getPath().endsWith("/failure")) { failed.incrementAndGet(); throw new IOException("Falha simulada"); }
            return sent;
        }
        public int getResponseCode() throws IOException {
            if (url.getPath().endsWith("/failure")) { failed.incrementAndGet(); throw new IOException("Falha simulada"); }
            if (url.getPath().endsWith("/limited") && limited.getAndIncrement()==0) {
                status=429; response="{\"retry_after\":0.001}";
            } else { status=200; response="{\"ok\":true}"; }
            return status;
        }
        public InputStream getInputStream() { return new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)); }
        public InputStream getErrorStream() { return new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)); }
    }
    public static void main(String[] args) throws Exception {
        List<String> methods = new ArrayList<>();
        AtomicInteger limited = new AtomicInteger(), failed = new AtomicInteger();
        DiscordBotApi.ConnectionFactory factory = url -> new FakeConnection(url, methods, limited, failed);
        try (DiscordBotApi api = new DiscordBotApi("token-ficticio-00000000000", factory)) {
            api.request("GET", "/channels/1", null);
            api.request("PATCH", "/webhooks/1/messages/@original", Java8.map("content", "🚒"));
            api.request("DELETE", "/channels/1/messages/2", null);
            api.request("POST", "/limited", Java8.map("content", "teste"));
            ReaderIntegrationTest.check(methods.equals(Arrays.asList("GET","PATCH","DELETE","POST","POST")),
                "Métodos REST e repetição somente após 429");
            try { api.request("POST", "/failure", Java8.map("content","teste")); throw new AssertionError("Falha esperada"); }
            catch (IOException expected) { ReaderIntegrationTest.check(failed.get()==1, "Falha ambígua não repete POST"); }
        }
        System.out.println("PASS REST do bot: GET, PATCH, DELETE, UTF-8, 429 e falha sem repetição");
    }
}
