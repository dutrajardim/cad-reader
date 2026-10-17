package br.gov.mg.sids.reader;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Descarte da fila, incluindo concorrência com envios e preservação do limite HTTP 429. */
public final class ReaderClearPendingTest {
    static void check(boolean ok, String message) { ReaderIntegrationTest.check(ok, message); }
    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline)
            Thread.sleep(10);
        check(condition.getAsBoolean(), "Condição não concluída no prazo");
    }
    static CadMonitor.Event event(String id) {
        return new CadMonitor.Event(id, "VTR-" + id, "100", id + ":10", "fp-" + id, "DESPACHADO",
                                    "ENDEREÇO: Rua de teste", "", "2026-10-11T12:00:00Z", "7", "9", true);
    }
    static DiscordPublisher publisher(Path root, DiscordPublisher.Transport transport, List<String> logs)
        throws Exception {
        return new DiscordPublisher("https://discord.com/api/webhooks/123/fake", root, logs::add, transport,
                                    new CadResourceFilter("1", Java8.set("7"), Java8.set("9")), 0);
    }
    static void failure(Path root) throws Exception {
        List<String> logs = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        try (DiscordPublisher p = publisher(root, (url, body) -> {
                 if (calls.incrementAndGet() == 1)
                     throw new IOException("secret-url", new java.net.SocketTimeoutException("secret-token"));
                 return new DiscordPublisher.Result(200, "{\"channel_id\":\"999\"}");
             }, logs)) {
            p.publish(event("1"));
            await(() -> p.status().contains("PAUSADA"));
            p.publish(event("2"));
            await(() -> p.status().endsWith("Pendentes: 2"));
            check(p.status().contains("tempo limite") && logs.stream().noneMatch(s -> s.contains("secret-")),
                  "Diagnóstico classifica timeout sem vazar segredo");
            p.clearPending();
            await(() -> p.status().endsWith("Pendentes: 0"));
            p.retry();
            Thread.sleep(80);
            check(calls.get() == 1, "Descartados não são reenviados");
            p.publish(event("3"));
            await(() -> calls.get() == 2 && p.status().endsWith("Pendentes: 0"));
        }
        try (DiscordPublisher p = publisher(
                 root, (url, body) -> { throw new AssertionError("Fila descartada reapareceu"); }, logs)) {
            p.retry();
            Thread.sleep(80);
            check(p.status().endsWith("Pendentes: 0"), "Descarte persistido após reabrir");
        }
    }
    static void inFlight(Path root) throws Exception {
        List<String> logs = new CopyOnWriteArrayList<>(), sent = new CopyOnWriteArrayList<>();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try (DiscordPublisher p = publisher(root, (url, body) -> {
                 sent.add(body);
                 if (sent.size() == 1) {
                     started.countDown();
                     check(release.await(4, TimeUnit.SECONDS), "Liberar transporte");
                 }
                 return new DiscordPublisher.Result(200, "{\"channel_id\":\"999\"}");
             }, logs)) {
            p.publish(event("1"));
            check(started.await(2, TimeUnit.SECONDS), "Envio iniciado");
            p.publish(event("2"));
            p.clearPending();
            p.publish(event("3"));
            release.countDown();
            await(() -> sent.size() == 2 && p.status().endsWith("Pendentes: 0"));
            check(sent.get(1).contains("VTR00003") && sent.stream().noneMatch(s -> s.contains("VTR00002")),
                  "Envio em andamento conclui, antigos são descartados e novas chegadas preservadas");
        } finally {
            release.countDown();
        }
    }
    static void rateLimit(Path root) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong first = new AtomicLong(), second = new AtomicLong();
        try (DiscordPublisher p = publisher(root, (url, body) -> {
                 if (calls.incrementAndGet() == 1) {
                     first.set(System.nanoTime());
                     return new DiscordPublisher.Result(429, "{\"retry_after\":1}");
                 }
                 second.set(System.nanoTime());
                 return new DiscordPublisher.Result(200, "{\"channel_id\":\"999\"}");
             }, new CopyOnWriteArrayList<>())) {
            p.publish(event("1"));
            await(() -> p.status().contains("HTTP 429"));
            p.clearPending();
            await(() -> p.status().endsWith("Pendentes: 0"));
            p.publish(event("2"));
            p.retry();
            Thread.sleep(150);
            check(calls.get() == 1, "Limpeza não contorna espera por 429");
            await(() -> calls.get() == 2 && p.status().endsWith("Pendentes: 0"));
            check(second.get() - first.get() >= TimeUnit.MILLISECONDS.toNanos(950),
                  "Prazo original respeitado");
        }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("cad-clear-tests-");
        try {
            failure(root.resolve("failure"));
            inFlight(root.resolve("flight"));
            rateLimit(root.resolve("rate"));
            System.out.println("PASS limpar pendentes: persistência, novas chegadas, envio em andamento, "
                               + "diagnóstico e HTTP 429");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                for (Path path : (Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)
                    Files.delete(path);
            }
        }
    }
}
