package br.gov.mg.sids.reader;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Verifica janela real, agrupamento, limites e recuperação durável com transporte falso. */
public final class ReaderBatchTest {
    private static final List<String> CAPTURE = new CopyOnWriteArrayList<>();
    private static final String WEBHOOK = "https://discord.com/api/webhooks/123/ficticio";
    private static final CadResourceFilter SCOPE = new CadResourceFilter("1", Set.of("7"), Set.of("9"));
    private static void check(boolean condition, String message) {
        ReaderIntegrationTest.check(condition, message);
    }
    private static CadMonitor.Event event(String resource, String call, String state, int second) {
        return new CadMonitor.Event(resource, "VTR-" + resource, call, resource + ":" + call,
                                    AppFiles.hash(state), state, state + " — horário CAD: 12:00:0" + second,
                                    "", "2026-10-11T15:00:0" + second + "Z", "7", "9");
    }
    private static int count(String body, String text) {
        int found = 0, index = 0;
        while ((index = body.indexOf(text, index)) >= 0) {
            found++;
            index += text.length();
        }
        return found;
    }
    private static List<Path> pending(Path root) throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            return paths.filter(p -> p.toString().endsWith(".pending"))
                .sorted()
                .collect(java.util.stream.Collectors.toList());
        }
    }
    private static DiscordPublisher.Transport accepting(List<String> bodies) {
        return (url, body) -> {
            bodies.add(body);
            CAPTURE.add(body);
            return new DiscordPublisher.Result(200, "{\"channel_id\":\"999\"}");
        };
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        ReaderIntegrationTest.waitFor(condition);
    }
    private static void realWindow(Path root) throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        try (DiscordPublisher publisher
             = new DiscordPublisher(WEBHOOK, root, m -> {}, accepting(bodies), SCOPE)) {
            long started = System.nanoTime();
            publisher.publish(event("1", "10", "DESPACHADO", 0));
            Thread.sleep(500);
            publisher.retry();
            check(bodies.isEmpty(), "Nada é enviado antes da janela, inclusive por reenvio manual");
            Thread.sleep(1500);
            publisher.publish(event("1", "10", "A CAMINHO", 1));
            publisher.publish(event("1", "10", "HISTÓRICO: contexto", 2));
            await(() -> !bodies.isEmpty());
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            check(elapsed >= 2800 && elapsed < 4500,
                  "Janela fixa de três segundos não é renovada a cada chegada");
            check(bodies.size() == 1 && count(bodies.get(0), "\"timestamp\":") == 3,
                  "Três eventos, uma requisição");
            String body = bodies.get(0);
            int first = body.indexOf("\"title\":\"🚨 CHAMADA 10 • DESPACHADO");
            int second = body.indexOf("\"title\":\"🚒 CHAMADA 10 • A CAMINHO");
            int third = body.indexOf("\"title\":\"📝 CHAMADA 10 • HISTÓRICO");
            check(first >= 0 && first < second && second < third,
                  "Estados intermediários preservados em ordem");
            check(body.contains("15:00:00Z") && body.contains("15:00:01Z") && body.contains("15:00:02Z"),
                  "Cada evento mantém horário");
            check(body.contains("\"parse\":[]"), "Batch não cria menções");
            await(() -> publisher.status().endsWith("Pendentes: 0"));
        }
    }
    private static void isolationAndLimits(Path root) throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>(), urls = new CopyOnWriteArrayList<>();
        try (DiscordPublisher publisher = new DiscordPublisher(WEBHOOK, root.resolve("isolation"),
                                                               m
                                                               -> {},
                                                               (url, body)
                                                                   -> {
                                                                   urls.add(url);
                                                                   return accepting(bodies).send(url, body);
                                                               },
                                                               SCOPE, 300)) {
            publisher.publish(event("1", "10", "A", 0));
            publisher.publish(event("2", "10", "B", 1));
            publisher.publish(event("1", "11", "C", 2));
            await(() -> bodies.size() == 3);
            check(bodies.stream().allMatch(b -> count(b, "\"timestamp\":") == 1),
                  "Viaturas e chamadas diferentes não se misturam");
            check(urls.get(2).contains("thread_id=999"), "Nova chamada reutiliza tópico da viatura");
        }
        bodies.clear();
        try (DiscordPublisher publisher = new DiscordPublisher(WEBHOOK, root.resolve("embed-limit"),
                                                               m -> {}, accepting(bodies), SCOPE, 1000)) {
            for (int i = 0; i < 11; i++)
                publisher.publish(event("1", "10", "ESTADO " + i, i % 9));
            await(() -> bodies.size() == 2);
            check(count(bodies.get(0), "\"timestamp\":") == 10 && count(bodies.get(1), "\"timestamp\":") == 1,
                  "Mais de dez blocos divide a mensagem");
        }
        bodies.clear();
        try (DiscordPublisher publisher = new DiscordPublisher(WEBHOOK, root.resolve("text-limit"),
                                                               m -> {}, accepting(bodies), SCOPE, 300)) {
            for (int i = 0; i < 2; i++) {
                CadMonitor.Event e = event("1", "10", "LONGO " + i, i);
                publisher.publish(new CadMonitor.Event(e.resourceId, e.resource, e.number, e.key,
                                                       e.fingerprint, e.title, "X".repeat(3400), "",
                                                       e.observed, e.unitId, e.typeId));
            }
            await(() -> bodies.size() == 2);
            check(bodies.stream().allMatch(
                      b -> count(b, "\"timestamp\":") == 1 && b.contains("X".repeat(3400))),
                  "Limite agregado divide sem cortar o evento");
        }
    }
    private static void durability(Path root) throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        String frozen;
        try (DiscordPublisher publisher
             = new DiscordPublisher(WEBHOOK, root, m -> {}, accepting(bodies), SCOPE, 30000)) {
            CadMonitor.Event first = event("1", "10", "DESPACHADO", 0);
            publisher.publish(new CadMonitor.Event(
                first.resourceId, first.resource, first.number, first.key, first.fingerprint, first.title,
                first.body + "\nSITUAÇÃO ATUAL: contexto livre; aspas \" e barra \\ 🚒", first.links,
                first.observed, first.unitId, first.typeId));
            publisher.publish(event("1", "10", "NO LOCAL", 1));
            check(pending(root).size() == 1, "Lote já está em disco durante a janela");
            frozen = AppFiles.load(pending(root).get(0)).getProperty("create");
            check(count(frozen, "\"timestamp\":") == 2, "Arquivo atômico já contém todos os eventos");
        }
        check(bodies.isEmpty(), "Fechar durante janela não força envio");
        try (DiscordPublisher publisher
             = new DiscordPublisher(WEBHOOK, root, m -> {}, accepting(bodies), SCOPE, 100)) {
            publisher.retry();
            await(() -> bodies.size() == 1);
            check(bodies.get(0).equals(frozen), "Reabrir recupera lote completo sem alterar conteúdo");
            await(() -> publisher.status().endsWith("Pendentes: 0"));
            publisher.publish(event("1", "10", "NO LOCAL", 1));
            Thread.sleep(250);
            check(bodies.size() == 1, "Fingerprint de cada membro é confirmado para deduplicação individual");
        }
    }
    private static void legacyAndAmbiguousFailure(Path root) throws Exception {
        Path legacyRoot = root.resolve("legacy");
        Path directory = legacyRoot.resolve("discord-" + AppFiles.hash("123").substring(0, 20));
        CadMonitor.Event event = event("1", "10", "LEGADO", 0);
        Properties legacy = new Properties();
        legacy.setProperty("resource", event.resourceId);
        legacy.setProperty("topicKey", event.topicKey());
        legacy.setProperty("prefix", event.resource);
        legacy.setProperty("number", event.number);
        legacy.setProperty("unit", "7");
        legacy.setProperty("type", "9");
        legacy.setProperty("key", AppFiles.hash(event.key));
        legacy.setProperty("fingerprint", event.fingerprint);
        legacy.setProperty("create", event.json(true));
        legacy.setProperty("reuse", event.json(false));
        AppFiles.save(directory.resolve("antigo.pending"), legacy);
        List<String> bodies = new CopyOnWriteArrayList<>();
        try (DiscordPublisher publisher
             = new DiscordPublisher(WEBHOOK, legacyRoot, m -> {}, accepting(bodies), SCOPE, 100)) {
            publisher.retry();
            await(() -> bodies.size() == 1 && publisher.status().endsWith("Pendentes: 0"));
            check(bodies.get(0).contains("LEGADO"), "Fila anterior ao batch permanece compatível");
        }
        bodies.clear();
        CountDownLatch paused = new CountDownLatch(1);
        Path ambiguousRoot = root.resolve("ambiguous");
        try (DiscordPublisher publisher
             = new DiscordPublisher(WEBHOOK, ambiguousRoot,
                                    m
                                    -> {
                                        if (m.contains("PAUSADA"))
                                            paused.countDown();
                                    },
                                    (url, body)
                                        -> {
                                        bodies.add(body);
                                        CAPTURE.add(body);
                                        throw new java.io.IOException("Falha fictícia de rede");
                                    },
                                    SCOPE, 100)) {
            publisher.publish(event("1", "10", "DESPACHADO", 0));
            publisher.publish(event("1", "10", "NO LOCAL", 1));
            check(paused.await(5, TimeUnit.SECONDS), "Falha ambígua pausa lote");
            check(pending(ambiguousRoot).size() == 1, "Falha preserva um lote completo");
        }
        String attempted = bodies.get(0);
        bodies.clear();
        try (DiscordPublisher publisher
             = new DiscordPublisher(WEBHOOK, ambiguousRoot, m -> {}, accepting(bodies), SCOPE, 100)) {
            publisher.retry();
            await(() -> bodies.size() == 1 && publisher.status().endsWith("Pendentes: 0"));
            check(attempted.equals(bodies.get(0)), "Retomada manual preserva conteúdo após falha ambígua");
        }
    }

    private static void repeatTransitionsAndRateLimit(Path root) throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        try (DiscordPublisher publisher = new DiscordPublisher(WEBHOOK, root.resolve("repeat"),
                                                               m -> {}, accepting(bodies), SCOPE, 150)) {
            for (int round = 0; round < 2; round++) {
                publisher.publish(event("1", "10", "A", 0));
                publisher.publish(event("1", "10", "B", 1));
                int expected = round + 1;
                await(() -> bodies.size() == expected);
                await(() -> publisher.status().endsWith("Pendentes: 0"));
            }
            check(bodies.size() == 2,
                  "Nova sequência real A/B não é descartada por ser igual a um lote anterior");
        }
        bodies.clear();
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch limited = new CountDownLatch(1);
        List<Long> times = new CopyOnWriteArrayList<>();
        try (DiscordPublisher publisher
             = new DiscordPublisher(WEBHOOK, root.resolve("429"),
                                    m
                                    -> {
                                        if (m.contains("HTTP 429"))
                                            limited.countDown();
                                    },
                                    (url, body)
                                        -> {
                                        bodies.add(body);
                                        CAPTURE.add(body);
                                        times.add(System.nanoTime());
                                        return attempts.getAndIncrement() == 0
                                            ? new DiscordPublisher.Result(429, "{\"retry_after\":1}")
                                            : new DiscordPublisher.Result(200, "{\"channel_id\":\"999\"}");
                                    },
                                    SCOPE, 100)) {
            publisher.publish(event("1", "10", "DESPACHADO", 0));
            publisher.publish(event("1", "10", "A CAMINHO", 1));
            check(limited.await(5, TimeUnit.SECONDS), "429 em lote");
            publisher.publish(event("1", "10", "NO LOCAL", 2));
            publisher.retry();
            await(() -> bodies.size() == 3);
            check(bodies.get(0).equals(bodies.get(1)) && count(bodies.get(1), "\"timestamp\":") == 2,
                  "Reenvio preserva lote recusado inteiro");
            check(TimeUnit.NANOSECONDS.toMillis(times.get(1) - times.get(0)) >= 900,
                  "Reenvio respeita Retry-After");
            check(bodies.get(2).contains("NO LOCAL") && count(bodies.get(2), "\"timestamp\":") == 1,
                  "Atualização posterior não altera lote já enviado");
            await(() -> publisher.status().endsWith("Pendentes: 0"));
        }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("cad-batch-");
        try {
            realWindow(root.resolve("real"));
            isolationAndLimits(root);
            durability(root.resolve("durable"));
            repeatTransitionsAndRateLimit(root);
            legacyAndAmbiguousFailure(root);
            if (args.length > 0)
                Files.write(Path.of(args[0]), CAPTURE);
            System.out.println("PASS batch de 3s, ordem/horários, isolamento, limites, "
                               + "fechamento/reabertura, deduplicação e 429");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                for (Path file : (Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(file);
            }
        }
    }
}
