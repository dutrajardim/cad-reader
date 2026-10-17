package br.gov.mg.sids.reader;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.w3c.dom.Element;

/** Cenários de referência inicial, histórico associado e segredos opcionais do perfil. */
public final class ReaderUpdatesTest {
    private static void check(boolean condition, String message) {
        ReaderIntegrationTest.check(condition, message);
    }
    private static Element xml(String value) throws Exception {
        return ReaderIntegrationTest.xml(
            value.replaceFirst("<([A-Za-z][A-Za-z0-9]*)", "<$1 xmlns='http://sids.mg.gov.br/cad'"));
    }
    private static String call(String history) {
        return "<idChamada>10</idChamada><numeroChamada>2026-10</numeroChamada>"
            + "<descricaoLocalizacao>Rua "
            + "A</descricaoLocalizacao><quantidadeHistoricos>2</quantidadeHistoricos>"
            + "<resumosHistoricosChamada>" + history + "</resumosHistoricosChamada>";
    }
    private static String history(String text, String date) {
        return "<i><dataHoraInclusao>" + date + "</dataHoraInclusao><descricao>" + text
            + "</descricao><nomeUsuarioInclusao>Autor não exportado</nomeUsuarioInclusao></i>";
    }
    /** Duas viaturas na mesma chamada não devem multiplicar consultas por alteração. */
    private static void verifyHistoryTriggers() throws Exception {
        List<String> queries = new ArrayList<>();
        List<CadMonitor.Event> notifications = new ArrayList<>();
        CadMonitor monitor = new CadMonitor(notifications::add);
        List<String> decisions = new ArrayList<>();
        monitor.setDiagnostics(decisions::add);
        monitor.setResourceFilter(new CadResourceFilter("1", Java8.set("7"), Java8.set("9")));
        monitor.setHistoryRequest(queries::add);
        String history = history("Registro inicial", "1700000000000");
        String summary = call(history);
        String resource
            = "<idRecurso>20</idRecurso><prefixo>AB-01</prefixo><idUnidadeServico>7</idUnidadeServico>"
              + "<idTipoRecurso>9</idTipoRecurso><situacaoRecurso>EMPENHADO</situacaoRecurso>"
              + "<resumosEmpenhosRecurso><i><idChamada>10</idChamada><situacaoEmpenhoRecurso>DESPACHADO</"
                + "situacaoEmpenhoRecurso></i></resumosEmpenhosRecurso>";
        String second = resource.replace("<idRecurso>20", "<idRecurso>21").replace("AB-01", "AB-02");
        monitor.accept(xml("<rtnRecuperarResumosChamadasAtivas><resumosChamadas><i>" + summary
                           + "</i></resumosChamadas></rtnRecuperarResumosChamadasAtivas>"));
        monitor.accept(xml("<rtnRecuperarResumosRecursosParaEmpenho><resumosRecursos><i>" + resource
                           + "</i><i>" + second
                           + "</i></resumosRecursos></rtnRecuperarResumosRecursosParaEmpenho>"));
        String changedAddress = summary.replace("Rua A", "Rua B");
        monitor.accept(xml("<envInformarAlteracaoChamada><resumoChamada>" + changedAddress
                           + ("</resumoChamada><tiposAcoesGeramNotificacao><i>CLASSIFICAR_CHAMADA</i></"
                              + "tiposAcoesGeramNotificacao></envInformarAlteracaoChamada>")));
        check(decisions.get(decisions.size() - 1).contains("eventos de atualização gerados=2"),
              "Diagnóstico informa eventos para ambas as viaturas vinculadas");
        CadMonitor.Event addressEvent = notifications.get(notifications.size() - 1);
        check(addressEvent.discordBody.contains("ENDEREÇO: Rua B")
                  && !addressEvent.discordBody.contains("UNIDADE:")
                  && addressEvent.discordLinks.contains("Google Maps"),
              "Mudança de endereço publica somente dados alterados e navegação atualizada");
        String referenceChanged = changedAddress + "<referencia>Em frente ao mercado</referencia>";
        monitor.accept(xml("<envInformarAlteracaoChamada><resumoChamada>" + referenceChanged
            + "</resumoChamada></envInformarAlteracaoChamada>"));
        check(notifications.get(notifications.size() - 1).discordLinks.isEmpty(),
            "Referência alterada não repete links de navegação");
        String coordinatesChanged = referenceChanged + "<latitude>-19.9</latitude><longitude>-44.1</longitude>";
        monitor.accept(xml("<envInformarAlteracaoChamada><resumoChamada>" + coordinatesChanged
            + "</resumoChamada></envInformarAlteracaoChamada>"));
        check(notifications.get(notifications.size() - 1).discordLinks.contains("-19.9%2C-44.1"),
            "Coordenadas alteradas publicam destino atualizado");
        monitor.accept(xml("<envInformarAlteracaoRecurso><resumoRecurso>"
                           + resource.replace("DESPACHADO", "NO_LOCAL")
                           + "</resumoRecurso></envInformarAlteracaoRecurso>"));
        CadMonitor.Event compact = notifications.get(notifications.size() - 1);
        check(compact.discordBody.contains("EMPENHO: NO LOCAL")
                  && !compact.discordBody.contains("ENDEREÇO:")
                  && !compact.discordBody.contains("VIATURA:") && compact.discordLinks.isEmpty(),
              "Estado seguinte é compacto, sem endereço, mapas ou identificação redundante");
        check(compact.body.contains("ENDEREÇO: Rua B"), "CSV conserva contexto completo");
        check(!compact.json(false).contains("\"content\":")
                  && compact.json(false).contains("CHAMADA 2026-10 •"),
              "Identificação do chamado somente no título, sem texto duplicado fora do bloco");
        check(queries.isEmpty(),
              "Endereço, classificação e estado do empenho não consultam histórico inicial conhecido");
        String[] codes = {"ED", "LI", "ER", "EC", "SU"};
        String[] labels = {"EM DIREÇÃO", "NO LOCAL INTERMEDIÁRIO", "EM RETORNO", "EM CONTROLE", "SUSPENSO"};
        for (int i = 0; i < codes.length; i++) {
            int previousCount = notifications.size();
            monitor.accept(xml("<envInformarAlteracaoRecurso><resumoRecurso>"
                + resource.replace("DESPACHADO", codes[i])
                + "</resumoRecurso></envInformarAlteracaoRecurso>"));
            CadMonitor.Event transition = notifications.get(notifications.size() - 1);
            check(notifications.size() == previousCount + 1 && transition.publishable
                && transition.title.equals("ATUALIZAÇÃO • " + labels[i])
                && transition.discordBody.contains("EMPENHO: " + labels[i]),
                "Transição abreviada do CAD é publicada com nome legível: " + codes[i]);
        }
        check(CadMonitor.label("EM_DIRECAO").equals("EM DIREÇÃO")
            && CadMonitor.label("NO_LOCAL_INTERMEDIARIO").equals("NO LOCAL INTERMEDIÁRIO")
            && CadMonitor.label("EM_RETORNO").equals("EM RETORNO"),
            "Nomes de enum também são apresentados corretamente");
        String countChanged = changedAddress.replace("<quantidadeHistoricos>2", "<quantidadeHistoricos>3");
        monitor.accept(xml("<envInformarAlteracaoChamada><resumoChamada>" + countChanged
                           + "</resumoChamada></envInformarAlteracaoChamada>"));
        check(queries.size() == 1,
              "Quantidade de históricos alterada: uma consulta compartilhada entre duas viaturas");
        monitor.accept(xml("<envInformarAlteracaoChamada><resumoChamada>" + countChanged
                           + "</resumoChamada></envInformarAlteracaoChamada>"));
        check(queries.size() == 1, "Repetição do resumo não consulta novamente");
        check(decisions.get(decisions.size() - 1).contains("sem alteração nos campos publicados"),
              "Diagnóstico distingue repetição de falha de entrega");
        monitor.accept(xml("<envInformarAlteracaoChamada><resumoChamada>" + countChanged
                           + ("</resumoChamada><tiposAcoesGeramNotificacao><i>SALVAR_HISTORICO_CHAMADA</i></"
                              + "tiposAcoesGeramNotificacao></envInformarAlteracaoChamada>")));
        check(queries.size() == 2,
              "Sinal explícito consulta mesmo sem mudança de quantidade/primeira página");
        String textChanged = countChanged.replace("Registro inicial", "Registro corrigido");
        monitor.accept(xml("<envInformarAlteracaoChamada><resumoChamada>" + textChanged
                           + "</resumoChamada></envInformarAlteracaoChamada>"));
        check(queries.size() == 3, "Edição do texto, sem mudar quantidade, consulta histórico");
        monitor.accept(xml("<envInformarAlteracaoChamada><resumoChamada>"
                           + textChanged.replace("Rua B", "Rua C")
                           + "</resumoChamada></envInformarAlteracaoChamada>"));
        check(queries.size() == 3, "Mudança geral após ativação do histórico também não consulta");
        monitor.accept(xml("<envInformarAlteracaoChamada><resumoChamada>"
            + summary.replace("<idChamada>10", "<idChamada>99")
            + "</resumoChamada></envInformarAlteracaoChamada>"));
        check(decisions.get(decisions.size() - 1).contains("sem viatura acompanhada com empenho ativo conhecido"),
              "Diagnóstico não atribui chamadas sem vínculo a uma viatura");
        monitor.accept(xml("<envInformarAlteracaoChamada><resumoChamada>"
                           + textChanged.replace("<idChamada>10", "<idChamada>99")
                           + ("</resumoChamada><tiposAcoesGeramNotificacao><i>SALVAR_HISTORICO_CHAMADA</i></"
                              + "tiposAcoesGeramNotificacao></envInformarAlteracaoChamada>")));
        check(queries.size() == 3, "Histórico de chamada sem viatura vinculada não consulta");
        monitor.accept(xml("<envInformarAlteracaoRecurso><resumoRecurso>"
                           + resource.replace("DESPACHADO", "TERMINADO") + "</resumoRecurso><resumoRecurso>"
                           + second.replace("DESPACHADO", "TERMINADO")
                           + "</resumoRecurso></envInformarAlteracaoRecurso>"));
        monitor.accept(xml("<envInformarAlteracaoChamada><resumoChamada>" + textChanged
                           + ("</resumoChamada><tiposAcoesGeramNotificacao><i>SALVAR_HISTORICO_CHAMADA</i></"
                              + "tiposAcoesGeramNotificacao></envInformarAlteracaoChamada>")));
        check(queries.size() == 3, "Sem empenhos ativos não há consulta de histórico");
    }

    public static void main(String[] args) throws Exception {
        verifyHistoryTriggers();
        Path root = Files.createTempDirectory("cad-updates-");
        try {
            ProfileStore profile = new ProfileStore(root.resolve("perfil.properties"));
            String secret = "senha-CAD-ficticia-&ç", webhook
                                                     = "https://discord.com/api/webhooks/123/ficticio";
            char[] protection = "protecao-independente".toCharArray();
            profile.save(new Properties(), webhook, secret, protection);
            String disk = Java8.readString(root.resolve("perfil.properties"));
            check(!disk.contains(secret) && !disk.contains("ficticio"),
                  "Segredos ausentes do texto do perfil");
            Properties loaded = profile.load(protection);
            check(secret.equals(loaded.getProperty("password"))
                      && webhook.equals(loaded.getProperty("webhook")),
                  "Recupera ambos os segredos");
            profile.save(new Properties(), webhook, protection);
            check(profile.load(protection).getProperty("password") == null,
                  "Senha CAD é opcional e removível");

            List<CadMonitor.Event> events = new ArrayList<>();
            List<String> requests = new ArrayList<>();
            CadMonitor monitor = new CadMonitor(events::add);
            monitor.setResourceFilter(new CadResourceFilter("1", Java8.set("7"), Java8.set("9")));
            monitor.setHistoryRequest(requests::add);
            String r
                = "<idRecurso>20</idRecurso><prefixo>AB-01</prefixo><idUnidadeServico>7</idUnidadeServico>"
                  + "<idTipoRecurso>9</idTipoRecurso><situacaoRecurso>INDISPONIVEL</situacaoRecurso>";
            String h1 = history("Histórico anterior ao empenho", "1700000000000");
            monitor.accept(xml("<rtnRecuperarResumosChamadasAtivas><resumosChamadas><i>" + call(h1)
                               + "</i></resumosChamadas></rtnRecuperarResumosChamadasAtivas>"));
            monitor.accept(xml("<envInformarAlteracaoRecurso><resumoRecurso>" + r
                               + "</resumoRecurso></envInformarAlteracaoRecurso>"));
            check(events.stream().noneMatch(e -> e.publishable),
                  "Eventos anteriores à referência completa não publicam");
            monitor.accept(xml("<rtnRecuperarResumosRecursosParaEmpenho><resumosRecursos><i>" + r
                               + "</i></resumosRecursos></rtnRecuperarResumosRecursosParaEmpenho>"));
            check(events.stream().noneMatch(e -> e.publishable) && requests.isEmpty(),
                  "Carga inicial somente local");
            CadMonitor.Event baseline = events.get(events.size() - 1);
            events.clear();
            String assignment = r.replace("INDISPONIVEL", "EMPENHADO")
                                + ("<resumosEmpenhosRecurso><i><idChamada>10</"
                                   + "idChamada><numeroChamada>2026-10</numeroChamada>")
                                + ("<situacaoEmpenhoRecurso>DESPACHADO</situacaoEmpenhoRecurso></i></"
                                   + "resumosEmpenhosRecurso>");
            monitor.accept(xml("<envInformarAlteracaoRecurso><resumoRecurso>" + assignment
                               + "</resumoRecurso></envInformarAlteracaoRecurso>"));
            check(events.size() == 1, "Histórico inicial aguarda todas as páginas");
            monitor.accept(xml("<rtnRecuperarResumosHistoricosChamada readerCallId='10'><resumosHistoricosChamada>"
                + h1 + history("Segundo registro anterior", "1699999999000")
                + "</resumosHistoricosChamada></rtnRecuperarResumosHistoricosChamada>"));
            check(events.size() == 1, "Página inicial não gera publicação isolada");
            monitor.historyFinished("10", true);
            check(events.get(1).title.equals("HISTÓRICO INICIAL")
                && events.get(1).body.indexOf("Segundo registro anterior") < events.get(1).body.indexOf("Histórico anterior"),
                "Contexto reúne registros em ordem cronológica numa mensagem");
            check(events.size() == 2 && events.get(0).publishable
                      && events.get(0).title.contains("DESPACHADO"),
                  "Novo empenho publica alteração e histórico anterior");
            check(events.get(0).discordBody.contains("ENDEREÇO: Rua A")
                      && events.get(0).discordLinks.contains("Google Maps")
                      && !events.get(0).discordBody.contains("VIATURA:"),
                  "Despacho apresenta endereço e mapas sem repetir a viatura");
            check(events.get(1).body.contains("Histórico anterior")
                      && !events.get(1).body.contains("Autor não exportado"),
                  "Histórico inclui descrição sem campos de autoria");
            check(requests.equals(Java8.list("10")), "Consulta somente chamada empenhada");
            String h2 = history("Informação complementar", "1700000001000");
            monitor.accept(
                xml("<rtnRecuperarResumosHistoricosChamada readerCallId='10'><resumosHistoricosChamada>" + h1
                    + h2 + "</resumosHistoricosChamada></rtnRecuperarResumosHistoricosChamada>"));
            check(events.size() == 3 && events.get(2).body.contains("Informação complementar"),
                  "Página não repete histórico já publicado");
            monitor.accept(xml("<envInformarAlteracaoChamada><resumoChamada>" + call(h1 + h2)
                               + "</resumoChamada></envInformarAlteracaoChamada>"));
            check(events.size() == 3 && requests.size() == 2,
                  "Alteração de histórico consulta servidor sem repetir textos");
            String longHistory = history(Java8.repeat("X", 6000), "1700000002000");
            monitor.accept(
                xml("<rtnRecuperarResumosHistoricosChamada readerCallId='10'><resumosHistoricosChamada>"
                    + longHistory + "</resumosHistoricosChamada></rtnRecuperarResumosHistoricosChamada>"));
            check(events.size() == 6 && events.get(5).title.contains("3/3"),
                  "Histórico longo dividido sem perder texto");
            monitor.accept(xml("<envInformarAlteracaoRecurso><resumoRecurso>"
                               + assignment.replace("DESPACHADO", "TERMINADO")
                               + "</resumoRecurso></envInformarAlteracaoRecurso>"));
            int afterEnd = events.size();
            monitor.accept(
                xml("<rtnRecuperarResumosHistoricosChamada readerCallId='10'><resumosHistoricosChamada>"
                    + history("Depois do encerramento", "1700000003000")
                    + "</resumosHistoricosChamada></rtnRecuperarResumosHistoricosChamada>"));
            check(events.size() == afterEnd,
                  "Resposta tardia não publica histórico após encerramento do empenho");

            List<CadMonitor.Event> existingEvents = new ArrayList<>();
            List<String> existingRequests = new ArrayList<>();
            CadMonitor existing = new CadMonitor(existingEvents::add);
            existing.setResourceFilter(new CadResourceFilter("1", Java8.set("7"), Java8.set("9")));
            existing.setHistoryRequest(existingRequests::add);
            existing.accept(xml("<rtnRecuperarResumosChamadasAtivas><resumosChamadas><i>" + call(h1)
                                + "</i></resumosChamadas></rtnRecuperarResumosChamadasAtivas>"));
            existing.accept(xml("<rtnRecuperarResumosRecursosParaEmpenho><resumosRecursos><i>" + assignment
                                + "</i></resumosRecursos></rtnRecuperarResumosRecursosParaEmpenho>"));
            int initialSize = existingEvents.size();
            existing.accept(xml("<envInformarAlteracaoRecurso><resumoRecurso>" + assignment
                                + "</resumoRecurso></envInformarAlteracaoRecurso>"));
            existing.accept(xml("<envInformarAlteracaoChamada><resumoChamada>" + call(h1)
                                + "</resumoChamada></envInformarAlteracaoChamada>"));
            check(existingEvents.size() == initialSize && existingRequests.isEmpty(),
                  "Repetição de empenho inicial não publica nem consulta histórico sem mudança");
            existing.accept(xml("<envInformarAlteracaoChamada><resumoChamada>" + call(h1 + h2)
                                + "</resumoChamada></envInformarAlteracaoChamada>"));
            existing.historyFinished("10", true);
            check(existingEvents.size() == initialSize + 1 && existingRequests.equals(Java8.list("10")),
                  "Mudança somente de histórico ativa contexto de empenho que já existia na carga");

            CadMonitor incomplete = new CadMonitor(events::add);
            incomplete.setResourceFilter(new CadResourceFilter("1", Java8.set("7"), Java8.set("9")));
            incomplete.accept(xml("<rtnRecuperarResumosChamadasAtivas><resumosChamadas><i>" + call("")
                + "</i></resumosChamadas></rtnRecuperarResumosChamadasAtivas>"));
            incomplete.accept(xml("<rtnRecuperarResumosRecursosParaEmpenho><resumosRecursos><i>" + r
                + "</i></resumosRecursos></rtnRecuperarResumosRecursosParaEmpenho>"));
            incomplete.accept(xml("<envInformarAlteracaoRecurso><resumoRecurso>" + assignment
                + "</resumoRecurso></envInformarAlteracaoRecurso>"));
            incomplete.accept(xml("<rtnRecuperarResumosHistoricosChamada readerCallId='10'><resumosHistoricosChamada>"
                + longHistory + "</resumosHistoricosChamada></rtnRecuperarResumosHistoricosChamada>"));
            int beforeContext = events.size();
            incomplete.historyFinished("10", false);
            check(events.size() == beforeContext + 3
                && events.get(beforeContext).body.contains("CONSULTA INCOMPLETA")
                && events.get(events.size() - 1).title.contains("3/3"),
                "Contexto longo mantém partes e indica consulta interrompida");
            incomplete.historyFinished("10", false);
            check(events.size() == beforeContext + 3, "Conclusão repetida não republica contexto");

            AtomicInteger sent = new AtomicInteger();
            DiscordPublisher.Transport transport = (url, body) -> {
                sent.incrementAndGet();
                return new DiscordPublisher.Result(200, "{\"channel_id\":\"999\"}");
            };
            try (DiscordPublisher publisher = new DiscordPublisher(webhook, root, m -> {}, transport)) {
                publisher.publish(baseline);
                check(publisher.status().endsWith("Pendentes: 0"), "Snapshot nem entra na fila");
            }
            Path queue = root.resolve("discord-" + AppFiles.hash("123").substring(0, 20));
            Properties old = new Properties();
            old.setProperty("create", "{\"content\":\"VTR AB-01 | CHAMADA — | SITUAÇÃO ATUAL • INDISPONIVEL\"}");
            AppFiles.save(queue.resolve("antigo.pending"), old);
            try (DiscordPublisher publisher = new DiscordPublisher(webhook, root, m -> {}, transport)) {
                publisher.retry();
            }
            check(sent.get() == 0 && Files.exists(queue.resolve("antigo.pending.initial-state")),
                  "Carga inicial antiga preservada fora da fila");
            System.out.println("PASS atualizações, referência inicial, histórico incremental/longo, "
                               + "pendências antigas e senha CAD cifrada opcional");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                for (Path path : (Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }
}
