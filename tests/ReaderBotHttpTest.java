package br.gov.mg.sids.reader;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;

/** Exercita serialização REST, PATCH e limites pelo cliente HTTP usado no Java 8. */
public final class ReaderBotHttpTest {
    public static void main(String[] args) throws Exception {
        List<String> methods = new ArrayList<>();
        AtomicInteger limited = new AtomicInteger(), failed = new AtomicInteger();
        OkHttpClient client = new OkHttpClient.Builder().retryOnConnectionFailure(false)
            .addInterceptor(chain -> {
                Request request = chain.request();
                methods.add(request.method());
                ReaderIntegrationTest.check("Bot token-ficticio-00000000000".equals(request.header("Authorization")), "Autenticação do bot");
                String path = request.url().encodedPath();
                if (path.endsWith("/failure")) { failed.incrementAndGet(); throw new IOException("Falha simulada"); }
                int code = 200;
                String body = "{\"ok\":true}";
                if (path.endsWith("/limited") && limited.getAndIncrement() == 0) {
                    code = 429; body = "{\"retry_after\":0.001}";
                }
                if (request.method().equals("PATCH")) {
                    okio.Buffer buffer = new okio.Buffer();
                    request.body().writeTo(buffer);
                    ReaderIntegrationTest.check(buffer.readUtf8().contains("🚒"), "PATCH preserva JSON UTF-8");
                }
                return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                    .message("simulado").body(ResponseBody.create(MediaType.parse("application/json"), body)).build();
            }).build();
        try (DiscordBotApi api = new DiscordBotApi("token-ficticio-00000000000", client)) {
            api.request("GET", "/channels/1", null);
            api.request("PATCH", "/webhooks/1/messages/@original", Java8.map("content", "🚒"));
            api.request("DELETE", "/channels/1/messages/2", null);
            api.request("POST", "/limited", Java8.map("content", "teste"));
            ReaderIntegrationTest.check(methods.equals(Arrays.asList("GET", "PATCH", "DELETE", "POST", "POST")), "Métodos REST e repetição somente após 429");
            try { api.request("POST", "/failure", Java8.map("content", "teste")); throw new AssertionError("Falha esperada"); }
            catch (IOException expected) { ReaderIntegrationTest.check(failed.get() == 1, "Falha ambígua não repete POST"); }
        }
        System.out.println("PASS REST do bot: GET, PATCH, DELETE, UTF-8, 429 e falha sem repetição");
    }
}
