package br.gov.mg.sids.reader;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class ReaderTopicLookupTest {
    static final String WEBHOOK = "https://discord.com/api/webhooks/123/ficticio";
    static Map<String, Object> thread(String id, String parent, String name) {
        return Java8.map("id", id, "parent_id", parent, "name", name,
                      "thread_metadata", Java8.map("archive_timestamp", "2026-10-10T10:00:00Z"));
    }
    static class Api extends DiscordBotApi {
        final List<String> paths = new ArrayList<>();
        int mode;
        Api(int mode) { super("test-token-000000000000000"); this.mode = mode; }
        @Override Object request(String method, String path, Object body) throws Exception {
            paths.add(path);
            if (mode == 3) throw new ApiFailure(403);
            if (path.startsWith("/webhooks/")) return Java8.map("channel_id", "456");
            if (path.equals("/channels/456")) return Java8.map("type", 15, "guild_id", "789");
            if (path.contains("/threads/active")) return Java8.map("threads", mode == 0
                ? Java8.list(thread("999", "456", "VTR ur5471 (JUATUB)"), thread("888", "321", "VTR UR05471"))
                : Java8.list());
            if (path.contains("before=")) return Java8.map("threads", mode == 1
                ? Java8.list(thread("777", "456", "VTR UR05471")) : Java8.list(), "has_more", false);
            return Java8.map("threads", Java8.list(thread("666", "456", "VTR ABT00054")), "has_more", true);
        }
    }
    static void check(boolean ok, String message) { ReaderIntegrationTest.check(ok, message); }
    public static void main(String[] args) throws Exception {
        Api active = new Api(0);
        try (DiscordTopicLookup lookup = new DiscordTopicLookup(active, WEBHOOK)) {
            check("999".equals(lookup.find("UR05471 (2BBM)")), "Reutiliza nome antigo no fórum correto");
            check(active.paths.size() == 3, "Encontrado ativo dispensa arquivos");
        }
        Api archived = new Api(1);
        try (DiscordTopicLookup lookup = new DiscordTopicLookup(archived, WEBHOOK)) {
            check("777".equals(lookup.find("ur5471")), "Busca todas as páginas arquivadas");
            check(archived.paths.stream().anyMatch(p -> p.contains("before=")), "Paginação por timestamp");
        }
        Path root = Files.createTempDirectory("cad-topic-lookup-");
        CadMonitor.Event event = new CadMonitor.Event("21", "UR05471 (2BBM)", "100", "21:100", "fp",
            "ATUALIZAÇÃO", "Atualização", "", "2026-10-16T12:00:00Z", "7", "9");
        List<String> urls = new CopyOnWriteArrayList<>(), bodies = new CopyOnWriteArrayList<>();
        try (DiscordPublisher p = new DiscordPublisher(WEBHOOK, root, s -> {}, (url, body) -> {
            urls.add(url); bodies.add(body); return new DiscordPublisher.Result(200, "{\"channel_id\":\"999\"}");
        }, null, 0)) {
            p.setTopicLookup(new DiscordTopicLookup(new Api(0), WEBHOOK));
            p.publish(event);
            ReaderIntegrationTest.waitFor(() -> p.status().endsWith("Pendentes: 0"));
            check(urls.size() == 1 && urls.get(0).endsWith("thread_id=999")
                && !bodies.get(0).contains("thread_name"), "PC sem associações reutiliza tópico remoto");
        }
        Properties topics = AppFiles.load(root.resolve("discord-" + AppFiles.hash("123").substring(0, 20)).resolve("topics.properties"));
        check("999".equals(topics.getProperty(event.topicKey())), "Persiste associação descoberta");
        Path failureRoot = Files.createTempDirectory("cad-topic-failure-");
        try (DiscordPublisher p = new DiscordPublisher(WEBHOOK, failureRoot, s -> {}, (u, b) -> {
            throw new AssertionError("Falha de busca não pode criar tópico");
        }, null, 0)) {
            p.setTopicLookup(new DiscordTopicLookup(new Api(3), WEBHOOK));
            p.publish(event);
            ReaderIntegrationTest.waitFor(() -> p.status().contains("PAUSADA"));
            check(p.status().endsWith("Pendentes: 1"), "Falha mantém envio pendente");
        }
        try (DiscordTopicLookup lookup = new DiscordTopicLookup(new Api(2), WEBHOOK)) {
            check(lookup.find("UR5471") == null, "Ausência após busca completa permite criação");
        }
        System.out.println("PASS tópicos remotos: ativos, arquivados, nomes antigos, escopo, persistência e falha sem criação");
    }
}
