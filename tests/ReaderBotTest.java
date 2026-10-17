package br.gov.mg.sids.reader;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.w3c.dom.Element;

/** Bot sem rede: agrupamentos, limites, prévia, confirmação, permissões e corte temporal. */
public final class ReaderBotTest {
    static void check(boolean condition, String message) {
        ReaderIntegrationTest.check(condition, message);
    }
    static Element xml(String value) throws Exception {
        return ReaderIntegrationTest.xml(value.replaceFirst(">", " xmlns=\"" + CadXml.CAD + "\">"));
    }
    static Element xmlUnchecked(String text) {
        try {
            return xml(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
    static final String GUILD = "123456", FORUM = "234567", CHANNEL = "345678";
    static class FakeApi extends DiscordBotApi {
        final List<String> deletes = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> callbacks = new CopyOnWriteArrayList<>(),
                                        edits = new CopyOnWriteArrayList<>();
        final List<Object> messages = new ArrayList<>();
        final List<Map<String, Object>> publications = new CopyOnWriteArrayList<>();
        volatile int status;
        FakeApi() {
            super(Java8.repeat("a", 30));
        }
        @SuppressWarnings("unchecked")
        @Override
        Object request(String method, String path, Object body) throws Exception {
            if (path.equals("/channels/" + CHANNEL))
                return Java8.map("guild_id", GUILD, "parent_id", FORUM, "type", 11);
            if (path.contains("/messages?"))
                return new ArrayList<>(messages);
            if (method.equals("DELETE")) {
                deletes.add(path.substring(path.lastIndexOf('/') + 1));
                if (status != 0)
                    throw new ApiFailure(status);
                return Java8.map();
            }
            if (path.endsWith("bulk-delete")) {
                deletes.addAll((List<String>)BotJson.object(body).get("messages"));
                return Java8.map();
            }
            if (method.equals("POST") && path.endsWith("/messages"))
                publications.add(BotJson.object(body));
            if (path.endsWith("/callback"))
                callbacks.add(BotJson.object(body));
            if (method.equals("PATCH"))
                edits.add(BotJson.object(body));
            return Java8.map();
        }
    }
    static Map<String, Object> interaction(String id, int type, String permissions,
                                           Map<String, Object> data) {
        return Java8.map("app_permissions", "8", "id", id, "type", type, "guild_id", GUILD, "channel_id",
                      CHANNEL, "application_id", "456789", "token", "fake", "member",
                      Java8.map("permissions", permissions, "roles", Java8.list(), "user", Java8.map("id", "567890")),
                      "data", data);
    }
    static Map<String, Object> cleanup(String date) {
        return Java8.map(
            "name", "cad-reader", "options",
            Java8.list(Java8.map("name", "limpar", "options", Java8.list(Java8.map("name", "antes-de", "value", date)))));
    }
    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline)
            Thread.sleep(10);
        check(condition.getAsBoolean(), "Operação assíncrona concluiu");
    }
    public static void main(String[] args) throws Exception {
        Object json = BotJson.parse("{\"a\":[1,true,null,\"ação 🚒\\n\"],\"b\":\"\\u00e7\"}");
        check(BotJson.parse(BotJson.write(json)).equals(json), "JSON unicode e estruturas preservados");
        check(BotJson.write(DiscordBot.commandDefinition()).contains("totais-por-natureza"),
              "Registro Discord anuncia o novo nome do comando");
        String call =
            "<i><idChamada>1</idChamada><resumosNaturezasChamada><i><codigo>S01</codigo><descricao>Animal</" +
            "descricao></i><i><codigo>S02</codigo><descricao>Árvore</descricao></i></" +
            "resumosNaturezasChamada><resumosAtendimentosChamada><i><orgao>BM</orgao><idUnidadeArea>7</" +
            "idUnidadeArea><idUnidadeResponsavel>99</idUnidadeResponsavel></i><i><orgao>PM</" +
            "orgao><idUnidadeArea>8</idUnidadeArea></i></resumosAtendimentosChamada></i>";
        String report = CadSummary.format("Demandas",
                                          xml("<rtn><resumosChamadas>" + call + call +
                                              "<i><idChamada>2</idChamada></i></resumosChamadas></rtn>"),
                                          Java8.map("7", "Unidade A"));
        check(report.contains("TOTAL: 2 chamadas") && report.contains("Unidade A (7) — 1 chamadas") &&
                  report.contains("Área BM não informada") && !report.contains("99") &&
                  !report.contains("Unidade ID 8"),
              "Deduplica chamadas e usa área BM, não unidade responsável nem PM");
        check(report.contains("S01 Animal: 1") && report.contains("S02 Árvore: 1"),
              "Naturezas contadas sem duplicar chamados");
        String longText = Java8.repeat("linha 🚒\n", 1000);
        check(String.join("", DiscordBot.pages(longText)).equals(longText) &&
                  DiscordBot.pages(longText).stream().allMatch(p -> p.length() <= 1800),
              "Relatórios longos divididos sem perda");
        FakeApi api = new FakeApi();
        LocalDate day = LocalDate.now(DiscordBot.ZONE).minusDays(3);
        Instant cutoff = day.atStartOfDay(DiscordBot.ZONE).toInstant();
        String old = DiscordBot.snowflake(Instant.now().minus(Duration.ofDays(20)));
        String recent = DiscordBot.snowflake(cutoff.minusSeconds(30));
        String newer = DiscordBot.snowflake(cutoff.plusSeconds(30));
        api.messages.add(Java8.map("id", recent));
        api.messages.add(Java8.map("id", old));
        api.messages.add(Java8.map("id", newer));
        try (DiscordBot bot = new DiscordBot(
                 api, GUILD, FORUM, "",
                 new CadSummary(id -> CompletableFuture.completedFuture(xmlUnchecked("<rtn/>"))), m -> {})) {
            bot.accept(interaction("1", 2, "8200", cleanup(day.toString())));
            await(() -> !api.edits.isEmpty());
            check(api.deletes.isEmpty(), "Prévia não apaga mensagens");
            Map<String, Object> preview = api.edits.get(0);
            check(BotJson.text(preview, "content").contains("Apagar 2 mensagens"),
                  "Prévia respeita corte Brasília");
            Map<String, Object> row = BotJson.object(BotJson.array(preview.get("components")).get(0));
            String button =
                BotJson.text(BotJson.object(BotJson.array(row.get("components")).get(0)), "custom_id");
            bot.accept(interaction("2", 3, "8200", Java8.map("custom_id", button)));
            await(() -> api.deletes.size() == 2);
            check(api.deletes.containsAll(Java8.list(old, recent)) && !api.deletes.contains(newer),
                  "Apaga mensagens de todos autores somente anteriores ao corte");
            bot.accept(interaction("3", 3, "8200", Java8.map("custom_id", button)));
            await(() -> api.callbacks.size() >= 3);
            check(api.deletes.size() == 2, "Confirmação de uso único");
            int edits = api.edits.size();
            bot.accept(interaction("4", 2, "0", cleanup(day.toString())));
            await(() -> api.callbacks.size() >= 4);
            check(api.edits.size() == edits, "Usuário sem permissão não inicia limpeza");
        }
        java.util.concurrent.atomic.AtomicInteger queries = new java.util.concurrent.atomic.AtomicInteger();
        CompletableFuture<Element> source = new CompletableFuture<>();
        CadSummary service = new CadSummary(id -> {
            queries.incrementAndGet();
            return source;
        });
        service.catalog(xml("<cache><filtrosChamada><i id='12'><descricao>2BBM - Demandas " +
                            "reprimidas</descricao></i></filtrosChamada></cache>"));
        CompletableFuture<String> first = service.query("12"), second = service.query("12");
        check(first == second && queries.get() == 1, "Consultas simultâneas compartilham requisição");
        source.complete(xml("<rtn><resumosChamadas>" + call + "</resumosChamadas></rtn>"));
        check(service.query("12").get().equals(first.get()) && queries.get() == 1,
              "Cache curto evita nova requisição");
        FakeApi sumApi = new FakeApi();
        try (DiscordBot bot = new DiscordBot(sumApi, GUILD, FORUM, "", service, m -> {})) {
            bot.accept(interaction(
                "10", 4, "8",
                Java8.map("options",
                       Java8.list(Java8.map("options", Java8.list(Java8.map("focused", true, "value", "reprimidas")))))));
            await(() -> sumApi.callbacks.size() == 1);
            check(BotJson.write(sumApi.callbacks.get(0)).contains("Demandas reprimidas (12)"),
                  "Autocomplete apresenta nome e ID");
            bot.accept(
                interaction("11", 2, "8",
                            Java8.map("name", "cad-reader", "options",
                                   Java8.list(Java8.map("name", "totais-por-natureza", "options",
                                                  Java8.list(Java8.map("name", "filtro", "value", "12")))))));
            await(() -> !sumApi.edits.isEmpty());
            check(sumApi.publications.isEmpty(), "Relatório inicialmente privado");
            Map<String, Object> row =
                BotJson.object(BotJson.array(sumApi.edits.get(0).get("components")).get(0));
            String publish =
                BotJson.text(BotJson.object(BotJson.array(row.get("components")).get(0)), "custom_id");
            bot.accept(interaction("12", 3, "8", Java8.map("custom_id", publish)));
            await(() -> sumApi.publications.size() == 1);
            check(BotJson.text(sumApi.publications.get(0), "content").contains("Totais de ocorrências por natureza — ")
                      && BotJson.text(sumApi.publications.get(0), "content").contains("TOTAL: 1 chamadas") &&
                      queries.get() == 1,
                  "Publicar aqui usa resultado existente e não consulta CAD novamente");
        }
        java.nio.file.Path profile = java.nio.file.Files.createTempFile("cad-bot-profile", ".properties");
        try {
            Properties settings = new Properties();
            settings.setProperty("botToken", "segredo-bot-teste");
            ProfileStore store = new ProfileStore(profile);
            store.save(settings, "", "protecao-teste".toCharArray());
            check(!Java8.readString(profile).contains("segredo-bot-teste") &&
                      store.load("protecao-teste".toCharArray())
                          .getProperty("botToken")
                          .equals("segredo-bot-teste"),
                  "Token do bot cifrado no perfil");
        } finally {
            java.nio.file.Files.deleteIfExists(profile);
        }
        System.out.println("PASS bot: JSON, relatório por natureza/área BM, deduplicação, paginação textual, " +
                           "confirmação, permissões e corte por data");
    }
}
