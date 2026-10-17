package br.gov.mg.sids.reader;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.xml.parsers.*;
import org.w3c.dom.Element;

/** Verificações locais; o transporte Discord é falso e não envia mensagens. */
public final class ReaderIntegrationTest {
    // Estes cenários isolam entrega/recuperação; a janela real é verificada em ReaderBatchTest.
    static DiscordPublisher immediate(String webhook, Path root, java.util.function.Consumer<String> log,
                                      DiscordPublisher.Transport transport) throws Exception {
        return new DiscordPublisher(webhook, root, log, transport, null, 0);
    }
    static DiscordPublisher immediate(String webhook, Path root, java.util.function.Consumer<String> log,
                                      DiscordPublisher.Transport transport, CadResourceFilter scope)
        throws Exception {
        return new DiscordPublisher(webhook, root, log, transport, scope, 0);
    }
    static void check(boolean ok, String message) {
        if (!ok)
            throw new AssertionError(message);
    }
    static Element xml(String s) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder()
            .parse(new ByteArrayInputStream(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .getDocumentElement();
    }
    static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end)
            Thread.sleep(20);
        check(condition.getAsBoolean(), "Tempo de espera excedido");
    }
    private static void verifyResourceScope(Path root) throws Exception {
        String ns = " xmlns='http://sids.mg.gov.br/cad'";
        CadResourceFilter scope = CadResourceFilter.from(
            xml("<i" + ns
                + (" id='34'><idsUnidades><i>7</i></idsUnidades><idsTiposRecurso><i>9</i></idsTiposRecurso></"
                   + "i>")));
        check(scope.matches("7", "9") && !scope.matches("8", "9") && !scope.matches("7", "10"),
              "Critérios de unidade e tipo do CAD original");
        check(new CadResourceFilter("35", Java8.set("7"), Java8.set()).matches("7", "10"),
              "Sem restrição de tipos inclui todos os tipos da unidade");
        List<CadMonitor.Event> records = new ArrayList<>();
        CadMonitor monitor = new CadMonitor(records::add);
        String resource
            = "<resumoRecurso><idRecurso>200</idRecurso><prefixo>AB-02</prefixo><idUnidadeServico>7</"
              + "idUnidadeServico><idTipoRecurso>9</idTipoRecurso><situacaoRecurso>DISPONIVEL</"
              + "situacaoRecurso><resumosEmpenhosRecurso><i><idChamada>10</idChamada><numeroChamada>10</"
              + "numeroChamada><situacaoEmpenhoRecurso>NO_LOCAL</situacaoEmpenhoRecurso></i></"
              + "resumosEmpenhosRecurso></resumoRecurso>";
        monitor.accept(
            xml("<envInformarAlteracaoRecurso" + ns + ">" + resource + "</envInformarAlteracaoRecurso>"));
        check(records.isEmpty(), "Sem filtro não publica recursos");
        monitor.setResourceFilter(scope);
        monitor.accept(xml("<rtnRecuperarResumosRecursosParaEmpenho" + ns + "><resumosRecursos>"
                           + resource.replace("<resumoRecurso>", "<i>").replace("</resumoRecurso>", "</i>")
                           + resource.replace("<idRecurso>200", "<idRecurso>201")
                                 .replace("<idUnidadeServico>7", "<idUnidadeServico>8")
                           + resource.replace("<idRecurso>200", "<idRecurso>202")
                                 .replace("<idTipoRecurso>9", "<idTipoRecurso>10")
                           + "</resumosRecursos></rtnRecuperarResumosRecursosParaEmpenho>"));
        check(records.size() == 1 && records.get(0).resourceId.equals("200"),
              "Snapshot amplo respeita filtro local");
        monitor.accept(xml("<envInformarAlteracaoRecurso" + ns + ">"
                           + resource.replace("<idUnidadeServico>7", "<idUnidadeServico>8")
                           + "</envInformarAlteracaoRecurso>"));
        monitor.accept(xml(
            "<envInformarAlteracaoChamada" + ns
            + ("><resumoChamada><idChamada>10</idChamada><numeroChamada>10</numeroChamada></resumoChamada></"
               + "envInformarAlteracaoChamada>")));
        check(records.size() == 2 && records.get(1).resourceId.isEmpty(),
              "Recurso que saiu do filtro deixa de receber mensagens de chamadas");
        monitor.accept(
            xml("<envInformarAlteracaoRecurso" + ns + ">" + resource + "</envInformarAlteracaoRecurso>"));
        check(records.size() == 3, "Recurso que volta ao filtro é atualizado");
        CadMonitor.Event baseline = records.get(0);
        CadMonitor.Event allowed
            = new CadMonitor.Event(baseline.resourceId, baseline.resource, baseline.number, baseline.key,
                                   baseline.fingerprint, "ATUALIZAÇÃO", baseline.body, baseline.links,
                                   baseline.observed, baseline.unitId, baseline.typeId);
        Path queueRoot = root.resolve("scoped");
        CountDownLatch limited = new CountDownLatch(1);
        try (DiscordPublisher publisher
             = immediate("https://discord.com/api/webhooks/456/fake", queueRoot, m -> {
                   if (m.contains("limite"))
                       limited.countDown();
               }, (u, b) -> new DiscordPublisher.Result(429, "{}"), scope)) {
            publisher.publish(allowed);
            check(limited.await(5, TimeUnit.SECONDS), "Pendência com escopo registrada");
        }
        java.util.concurrent.atomic.AtomicInteger sent = new java.util.concurrent.atomic.AtomicInteger();
        try (DiscordPublisher publisher
             = immediate("https://discord.com/api/webhooks/456/fake", queueRoot,
                         m
                         -> {},
                         (u, b)
                             -> {
                             sent.incrementAndGet();
                             return new DiscordPublisher.Result(200, "{\"channel_id\":\"999\"}");
                         },
                         new CadResourceFilter("99", Java8.set("8"), Java8.set()))) {
            publisher.retry();
        }
        check(sent.get() == 0, "Não reenvia pendências de outro filtro");
        Path queueDir = queueRoot.resolve("discord-" + AppFiles.hash("456").substring(0, 20));
        Properties legacy = new Properties();
        legacy.setProperty("resource", "200");
        AppFiles.save(queueDir.resolve("legacy.pending"), legacy);
        CountDownLatch resumed = new CountDownLatch(1);
        try (DiscordPublisher publisher
             = immediate("https://discord.com/api/webhooks/456/fake", queueRoot, m -> {
                   if (m.contains("confirmada"))
                       resumed.countDown();
               }, (u, b) -> new DiscordPublisher.Result(200, "{\"channel_id\":\"999\"}"), scope)) {
            publisher.publish(allowed);
            check(resumed.await(5, TimeUnit.SECONDS), "Fila legada isolada não impede publicações válidas");
        }
        check(Files.exists(queueDir.resolve("legacy.pending.outside-filter")),
              "Pendência antiga sem unidade/tipo não é publicada");
        try (java.util.stream.Stream<Path> files = Files.walk(queueRoot)) {
            check(files.anyMatch(p -> p.toString().endsWith(".outside-filter")),
                  "Pendência incompatível preservada fora da fila");
        }
    }

    private static void verifyDiscordRecovery(Path root, CadMonitor.Event first, CadMonitor.Event next)
        throws Exception {
        String webhook = "https://discord.com/api/webhooks/789/fake-token";
        Path base = root.resolve("recovery");
        Path dir = base.resolve("discord-" + AppFiles.hash("789").substring(0, 20));
        Properties oldTopics = new Properties();
        oldTopics.setProperty("vtr." + AppFiles.hash(first.resource.trim().toUpperCase(Locale.ROOT)), "888");
        AppFiles.save(dir.resolve("topics.properties"), oldTopics);
        List<String> urls = new CopyOnWriteArrayList<>();
        CountDownLatch recovered = new CountDownLatch(2);
        try (DiscordPublisher publisher
             = immediate(webhook, base,
                         m
                         -> {
                             if (m.contains("publicação confirmada"))
                                 recovered.countDown();
                         },
                         (u, b) -> {
                             urls.add(u);
                             return u.contains("thread_id=888")
                                 ? new DiscordPublisher.Result(404, "{\"code\":10003}")
                                 : new DiscordPublisher.Result(200, "{\"channel_id\":\"999\"}");
                         })) {
            publisher.publish(first);
            publisher.publish(next);
            check(recovered.await(5, TimeUnit.SECONDS), "Tópico excluído recuperado");
            check(urls.size() == 3 && urls.get(0).contains("thread_id=888")
                      && !urls.get(1).contains("thread_id") && urls.get(2).contains("thread_id=999"),
                  "Recriação só após erro explícito de tópico inexistente");
            check(publisher.status().contains("Pendentes: 0"), "Contagem de entregas pendentes");
        }
        for (boolean network : new boolean[] {false, true}) {
            java.util.concurrent.atomic.AtomicInteger attempts
                = new java.util.concurrent.atomic.AtomicInteger();
            List<String> logs = new CopyOnWriteArrayList<>();
            CountDownLatch paused = new CountDownLatch(1);
            try (DiscordPublisher publisher = immediate(
                     webhook, root.resolve("fail-" + network),
                     m
                     -> {
                         logs.add(m);
                         if (m.contains("PAUSADA"))
                             paused.countDown();
                     },
                     (u, b) -> {
                         attempts.incrementAndGet();
                         if (network)
                             throw new IOException("https://discord.com/api/webhooks/789/LEAK-ME");
                         return new DiscordPublisher.Result(404, "{\"code\":10015,\"message\":\"LEAK-ME\"}");
                     })) {
                publisher.publish(first);
                check(paused.await(5, TimeUnit.SECONDS), "Falha de publicação visível");
                publisher.publish(next);
                check(attempts.get() == 1,
                      "Não recria tópico em webhook inválido nem reenvia falha ambígua automaticamente");
                check(publisher.status().contains("Pendentes: 2"), "Fila preservada após falha");
                check(!String.join("\n", logs).contains("LEAK-ME"), "Erros não vazam URL nem corpo HTTP");
                if (!network)
                    check(String.join("\n", logs).contains("10015"), "Código de erro Discord diagnosticado");
            }
        }
        CountDownLatch limited = new CountDownLatch(1), confirmed = new CountDownLatch(2);
        List<Long> times = new CopyOnWriteArrayList<>();
        List<String> requests = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        try (DiscordPublisher publisher
             = immediate(webhook, root.resolve("auto-rate"),
                         m
                         -> {
                             if (m.contains("HTTP 429"))
                                 limited.countDown();
                             if (m.contains("publicação confirmada"))
                                 confirmed.countDown();
                         },
                         (u, b) -> {
                             times.add(System.nanoTime());
                             requests.add(b);
                             return attempts.incrementAndGet() == 1
                                 ? new DiscordPublisher.Result(429, "{\"retry_after\":0.1}")
                                 : new DiscordPublisher.Result(200, "{\"channel_id\":\"999\"}");
                         })) {
            publisher.publish(first);
            check(limited.await(5, TimeUnit.SECONDS), "Limite detectado");
            publisher.publish(next);
            publisher.retry();
            check(confirmed.await(5, TimeUnit.SECONDS), "Retomada automática após 429");
            check(attempts.get() == 3 && requests.get(0).equals(requests.get(1))
                      && !requests.get(2).contains("thread_name"),
                  "Retoma na ordem e reutiliza tópico");
            check(TimeUnit.NANOSECONDS.toMillis(times.get(1) - times.get(0)) >= 900,
                  "Reenvio manual não antecipa prazo de limite");
        }
        check(DiscordPublisher.retryDelayMillis(new DiscordPublisher.Result(429, "{\"retry_after\":1.25}"))
                  == 1250,
              "retry_after em segundos fracionários");
        check(DiscordPublisher.retryDelayMillis(new DiscordPublisher.Result(429, "{}", "2.5")) == 2500,
              "Cabeçalho Retry-After");
    }

    public static void main(String[] args) throws Exception {
        check(CadMonitor.Event.normalizedPrefix("UR05471 (2BBM)").equals("UR05471"), "Remove unidade do prefixo");
        check(CadMonitor.Event.normalizedPrefix("ur5471 (JUATUB)").equals("UR05471"), "Normaliza caixa e zeros");
        check(CadMonitor.Event.normalizedPrefix("ABT054").equals("ABT00054"), "Completa cinco dígitos");
        check(CadMonitor.Event.normalizedPrefix("ABCD000123456").equals("ABCD123456"), "Preserva números longos");
        check(CadMonitor.Event.normalizedPrefix("RECURSO ESPECIAL").equals("RECURSO ESPECIAL"), "Preserva nomes sem padrão");
        check(!CadMonitor.Event.topicKey("UR5471", "1").equals(CadMonitor.Event.topicKey("AB5471", "1")), "Letras distinguem viaturas");
        InputStream misleadingAvailable = new ByteArrayInputStream(new byte[] {1, 2, 3}) {
            @Override
            public synchronized int available() {
                return 1;
            }
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                if (pos == count)
                    throw new AssertionError("Tentou outra leitura antes de devolver o bloco disponível");
                return super.read(b, off, len);
            }
        };
        byte[] block = new byte[8192];
        try (CadInputBuffer buffer = new CadInputBuffer(misleadingAvailable)) {
            check(buffer.read(block) == 3 && block[2] == 3, "Buffer devolve bloco sem esperar outra leitura");
        }
        ReaderOptions credentials = ReaderOptions.from(Java8.map("user", " bm123 ", "password", "aA&' "));
        check(credentials.username.equals("BM123"), "Usuário normalizado como na tela original");
        check(credentials.password.equals("aA&' "), "Senha deve ser preservada literalmente");
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            FilterSelector selector = new FilterSelector();
            selector.setOptions(Arrays.asList(new FilterSelector.Option("385", "São João"),
                                              new FilterSelector.Option("214", "2BBM"),
                                              new FilterSelector.Option("999", "São João")),
                                "385");
            check(selector.selectedId().equals("385"), "Restauração de filtro salvo");
            check(selector.choices.getSelectedItem().toString().equals("São João (385)"), "Nome (ID)");
            ((javax.swing.JTextField) selector.choices.getEditor().getEditorComponent()).setText("sao jo");
            check(selector.choices.getItemCount() == 2, "Busca por nome sem acento");
            ((javax.swing.JTextField) selector.choices.getEditor().getEditorComponent()).setText("214");
            check(selector.choices.getItemCount() == 1 && selector.selectedId().isEmpty(),
                  "Busca por ID aguarda confirmação");
            ((javax.swing.JTextField) selector.choices.getEditor().getEditorComponent()).postActionEvent();
            check(selector.selectedId().equals("214"), "Enter confirma resultado único");
            ((javax.swing.JTextField) selector.choices.getEditor().getEditorComponent()).setText("inexistente");
            check(selector.selectedId().isEmpty(), "Não envia texto livre como filtro");
            ((javax.swing.JTextField) selector.choices.getEditor().getEditorComponent()).setText("");
            check(selector.choices.getItemCount() == 3 && selector.selectedId().isEmpty(),
                  "Restaura catálogo ao apagar busca sem selecionar texto vazio");
            selector.choices.setSelectedIndex(1);
            check(!selector.selectedId().isEmpty(), "Seleção na lista confirma opção");
        });
        Path root = Files.createTempDirectory("cad-reader-test-");
        try {
            ProfileStore store = new ProfileStore(root.resolve("profile.properties"));
            try {
                store.load("qualquer-senha".toCharArray());
                throw new AssertionError("Perfil inexistente não deve abrir como se existisse");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("Perfil não encontrado"),
                      "Diagnóstico de perfil inexistente");
            }
            Properties props = new Properties();
            props.setProperty("user", "teste");
            props.setProperty("password", "never-store");
            String webhook = "https://discord.com/api/webhooks/123/fake-token";
            store.save(props, webhook, "senha-local-forte".toCharArray());
            String disk = Java8.readString(root.resolve("profile.properties"));
            check(!disk.contains("fake-token") && !disk.contains("never-store"), "Segredo em texto simples");
            check(store.load("senha-local-forte".toCharArray()).getProperty("webhook").equals(webhook),
                  "Criptografia");
            try {
                store.load("senha-errada".toCharArray());
                throw new AssertionError("Aceitou senha incorreta");
            } catch (IllegalArgumentException expected) {
            }
            Properties portable = new Properties();
            portable.setProperty("host", "cad.example");
            portable.setProperty("user", "BM123");
            portable.setProperty("calls", "12");
            portable.setProperty("botToken", "bot-secret-portable");
            Path original = root.resolve("pc1/profile.properties");
            Path exported = root.resolve("transfer/profile.properties");
            Path imported = root.resolve("pc2/profile.properties");
            char[] protection = "senha-local-forte".toCharArray();
            new ProfileStore(original).save(portable, webhook, "cad-secret-portable", protection);
            new ProfileStore(original).copyTo(exported, protection);
            new ProfileStore(exported).copyTo(imported, protection);
            Properties restored = new ProfileStore(imported).load(protection);
            check(restored.getProperty("password").equals("cad-secret-portable")
                      && restored.getProperty("botToken").equals("bot-secret-portable")
                      && restored.getProperty("webhook").equals(webhook)
                      && restored.getProperty("calls").equals("12"), "Perfil portável preserva configurações e segredos");
            String exportedText = Java8.readString(exported);
            check(!exportedText.contains("cad-secret-portable") && !exportedText.contains("bot-secret-portable")
                      && !exportedText.contains("fake-token"), "Exportação mantém segredos cifrados");
            String previous = Java8.readString(imported);
            try {
                new ProfileStore(exported).copyTo(imported, "senha-incorreta".toCharArray());
                throw new AssertionError("Importação aceitou senha incorreta");
            } catch (IllegalArgumentException expected) {
                check(Java8.readString(imported).equals(previous), "Falha de importação preserva destino");
            }
            List<CadMonitor.Event> events = new ArrayList<>();
            CadMonitor monitor = new CadMonitor(events::add);
            monitor.setResourceFilter(new CadResourceFilter("34", Java8.set("7"), Java8.set("9")));
            String ns = " xmlns='http://sids.mg.gov.br/cad'";
            monitor.accept(xml(
                "<rtnRecuperarResumosChamadasAtivas" + ns
                + ("><resumosChamadas><i><idChamada>10</idChamada><numeroChamada>2026-10</"
                   + "numeroChamada><situacaoChamada>EM_ATENDIMENTO</"
                   + "situacaoChamada><descricaoLocalizacao>Rua A, "
                   + "123</descricaoLocalizacao><latitude>-19.9</latitude><longitude>-43.9</longitude></i></"
                   + "resumosChamadas></rtnRecuperarResumosChamadasAtivas>")));
            check(events.size() == 1 && events.get(0).resourceId.isEmpty(),
                  "Chamada sem recurso no histórico");
            events.clear();
            String resource
                = "<idRecurso>20</idRecurso><idUnidadeServico>7</idUnidadeServico><idTipoRecurso>9</"
                  + "idTipoRecurso><prefixo>AB-01</prefixo><situacaoRecurso>EMPENHADO</"
                  + "situacaoRecurso><resumosEmpenhosRecurso><i><idChamada>10</"
                  + "idChamada><numeroChamada>2026-10</numeroChamada><situacaoEmpenhoRecurso>NO_LOCAL</"
                  + "situacaoEmpenhoRecurso><dataHoraSituacaoEmpenho>123456</dataHoraSituacaoEmpenho></i></"
                  + "resumosEmpenhosRecurso>";
            Element snapshot
                = xml("<rtnRecuperarResumosRecursosParaEmpenho" + ns + "><resumosRecursos><i>" + resource
                      + "</i></resumosRecursos></rtnRecuperarResumosRecursosParaEmpenho>");
            monitor.accept(snapshot);
            monitor.accept(snapshot);
            check(events.size() == 1, "Snapshot duplicado");
            CadMonitor.Event first = events.get(0);
            check(first.body.contains("NO LOCAL") && first.body.contains("2026-10")
                      && first.body.contains("Rua A, 123"),
                  "Relacionamento do empenho");
            check(first.links.contains("Google Maps") && first.links.contains("Waze")
                      && !first.links.contains("#lat="),
                  "Links");
            monitor.accept(xml("<envInformarAlteracaoRecurso" + ns + "><resumoRecurso>"
                               + resource.replace("NO_LOCAL", "TERMINADO")
                               + "</resumoRecurso></envInformarAlteracaoRecurso>"));
            check(events.get(1).title.contains("ENCERRADO"), "Encerramento explícito");
            monitor.accept(xml("<envInformarAlteracaoRecurso" + ns + "><resumoRecurso>" + resource
                               + "</resumoRecurso></envInformarAlteracaoRecurso>"));
            int beforeCall = events.size();
            monitor.accept(xml("<envInformarAlteracaoChamada" + ns
                               + ("><resumoChamada><idChamada>10</idChamada><numeroChamada>2026-10</"
                                  + "numeroChamada><descricaoLocalizacao>Rua B, "
                                  + "456</descricaoLocalizacao><situacaoChamada>ENCERRADA</situacaoChamada></"
                                  + "resumoChamada></envInformarAlteracaoChamada>")));
            check(events.size() == beforeCall + 2
                      && events.get(events.size() - 1).body.contains("Rua B, 456"),
                  "Chamada atualiza empenho relacionado");
            int beforeUnrelated = events.size();
            monitor.accept(
                xml("<envInformarAlteracaoChamada" + ns
                    + ("><resumoChamada><idChamada>99</idChamada><numeroChamada>99</numeroChamada></"
                       + "resumoChamada></envInformarAlteracaoChamada>")));
            check(events.size() == beforeUnrelated + 1,
                  "Chamada sem vínculo não gera mensagens de outros recursos");
            java.lang.reflect.Field cacheField = CadMonitor.class.getDeclaredField("resources");
            cacheField.setAccessible(true);
            Element cached=(Element)((Map<?,?>)cacheField.get(monitor)).get("20");
            check(cached.getParentNode() == null && cached.getOwnerDocument().getDocumentElement() == null,
                  "Cache não retém o documento do snapshot");
            check(snapshot.getElementsByTagNameNS("http://sids.mg.gov.br/cad", "idRecurso").getLength() == 1,
                  "Snapshot original preservado para outros listeners");
            check(!first.publishable && events.get(1).publishable, "Snapshot local; alteração publicável");
            CadMonitor.Event baseline = first;
            first = new CadMonitor.Event(first.resourceId, first.resource, first.number, first.key,
                                         first.fingerprint, "ATUALIZAÇÃO", first.body, first.links,
                                         first.observed, first.unitId, first.typeId);
            check(first.json(true).contains("\"parse\":[]"), "Menções desativadas");
            check(CadReaderWindow.csv("=1+2").startsWith("\"'"), "CSV fórmula");
            List<String> urls = new CopyOnWriteArrayList<>(), bodies = new CopyOnWriteArrayList<>();
            CountDownLatch confirmed = new CountDownLatch(3);
            DiscordPublisher.Transport fake = (url, body) -> {
                urls.add(url);
                bodies.add(body);
                return new DiscordPublisher.Result(200, "{\"channel_id\":\"999\"}");
            };
            try (DiscordPublisher publisher = immediate(webhook, root, s -> {
                     if (s.contains("confirmada"))
                         confirmed.countDown();
                 }, fake)) {
                publisher.publish(baseline);
                publisher.publish(first);
                publisher.publish(events.get(1));
                publisher.publish(new CadMonitor.Event("21", "ab00001 (JUATUB)", "nova chamada", "21:x", "new",
                                                       "ATUALIZAÇÃO", "nova ativação", "", first.observed,
                                                       "7", "9"));
                check(confirmed.await(5, TimeUnit.SECONDS), "Envios simulados");
                check(urls.get(0).endsWith("wait=true") && urls.get(1).endsWith("thread_id=999"),
                      "Reutilização do tópico");
                check(urls.get(2).endsWith("thread_id=999"), "Mesmo prefixo com novo ID usa o mesmo tópico");
                check(!bodies.get(0).contains("\"content\":") && bodies.get(0).contains("\"thread_name\":\"VTR AB00001\""),
                      "Prévia legível no fórum");
                check(bodies.get(0).contains("thread_name") && !bodies.get(1).contains("thread_name"),
                      "Criação única");
                try {
                    immediate(webhook, root, s -> {}, fake);
                    throw new AssertionError("Dois publicadores locais");
                } catch (IllegalStateException expected) {
                }
            }
            CountDownLatch replay = new CountDownLatch(1);
            try (DiscordPublisher publisher
                 = immediate(webhook, root,
                             s
                             -> {},
                             (u, b) -> {
                                 replay.countDown();
                                 return new DiscordPublisher.Result(200, "{\"channel_id\":\"999\"}");
                             })) {
                publisher.publish(events.get(1));
                check(!replay.await(300, TimeUnit.MILLISECONDS), "Deduplicação persistente");
            }
            Path rateRoot = root.resolve("rate");
            CountDownLatch limited = new CountDownLatch(1);
            try (DiscordPublisher publisher = immediate(webhook, rateRoot, s -> {
                     if (s.contains("limite"))
                         limited.countDown();
                 }, (u, b) -> new DiscordPublisher.Result(429, "{}"))) {
                publisher.publish(first);
                check(limited.await(5, TimeUnit.SECONDS), "Tratamento 429");
            }
            check(Files.walk(rateRoot).anyMatch(p -> p.toString().endsWith(".pending")),
                  "Pendência preservada");
            CountDownLatch retried = new CountDownLatch(1);
            try (DiscordPublisher publisher = immediate(webhook, rateRoot, s -> {
                     if (s.contains("confirmada"))
                         retried.countDown();
                 }, fake)) {
                publisher.retry();
                check(retried.await(5, TimeUnit.SECONDS), "Retomada da fila");
            }
            verifyResourceScope(root);
            verifyDiscordRecovery(root, first, events.get(1));
            System.out.println("PASS criptografia, estados CAD, deduplicação, links, CSV, tópicos, bloqueio "
                               + "local, 429 e retomada");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }
}
