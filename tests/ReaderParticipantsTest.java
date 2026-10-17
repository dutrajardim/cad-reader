package br.gov.mg.sids.reader;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import org.w3c.dom.Element;

/** Consulta paginada e publicação resumida de envolvidos, sem servidor externo. */
public final class ReaderParticipantsTest {
    static Element xml(String body) throws Exception {
        return ReaderIntegrationTest.xml(
            body.replaceFirst("<([A-Za-z][A-Za-z0-9]*)", "<$1 xmlns='http://sids.mg.gov.br/cad'"));
    }
    static void check(boolean ok, String message) { ReaderIntegrationTest.check(ok, message); }
    static void field(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
    static String resource(String id, String state, boolean assigned) {
        return "<idRecurso>" + id + "</idRecurso><prefixo>VTR-" + id
            + "</prefixo><idUnidadeServico>7</idUnidadeServico><idTipoRecurso>9</idTipoRecurso>"
            + "<situacaoRecurso>EMPENHADO</situacaoRecurso>"
            + (assigned ? "<resumosEmpenhosRecurso><i><idChamada>10</idChamada>"
                              + "<numeroChamada>2026-10</numeroChamada><situacaoEmpenhoRecurso>" + state
                              + "</situacaoEmpenhoRecurso></i></resumosEmpenhosRecurso>"
                        : "");
    }
    static void update(CadMonitor monitor, String id, String state, boolean assigned) throws Exception {
        monitor.accept(xml("<envInformarAlteracaoRecurso><resumoRecurso>" + resource(id, state, assigned)
                           + "</resumoRecurso></envInformarAlteracaoRecurso>"));
    }
    static String person(String id, String name, String phone, boolean applicant) {
        return "<i id='" + id + "'><nome>" + name + "</nome><telefone>" + phone + "</telefone><solicitante>"
            + applicant + "</solicitante><cpf>NAO_EXPORTAR</cpf></i>";
    }
    static List<CadMonitor.Event> contacts(List<CadMonitor.Event> events) {
        List<CadMonitor.Event> result = new ArrayList<>();
        for (CadMonitor.Event e : events)
            if (e.title.startsWith("CONTATOS"))
                result.add(e);
        return result;
    }
    static void verifyMonitor() throws Exception {
        List<CadMonitor.Event> events = new ArrayList<>();
        List<String> queries = new ArrayList<>();
        CadMonitor monitor = new CadMonitor(events::add);
        monitor.setParticipantsRequest(queries::add);
        monitor.setResourceFilter(new CadResourceFilter("1", Java8.set("7"), Java8.set("9")));
        monitor.accept(xml("<rtnRecuperarResumosChamadasAtivas><resumosChamadas><i>"
                           + "<idChamada>10</idChamada><numeroChamada>2026-10</numeroChamada>"
                           + "</i></resumosChamadas></rtnRecuperarResumosChamadasAtivas>"));
        monitor.accept(xml("<rtnRecuperarResumosRecursosParaEmpenho><resumosRecursos><i>"
                           + resource("20", "DESPACHADO", false)
                           + "</i></resumosRecursos></rtnRecuperarResumosRecursosParaEmpenho>"));
        check(queries.isEmpty(), "Carga inicial não consulta envolvidos");
        update(monitor, "20", "DESPACHADO", true);
        update(monitor, "21", "DESPACHADO", true);
        check(queries.equals(Java8.list("10")), "Duas viaturas compartilham uma consulta");
        String people
            = person("1", "Maria", "(31) 99999-1234", true) + person("2", "João", "32101234", false);
        String response = "<rtnRecuperarEnvolvidosChamada readerCallId='10'><envolvidosChamada>" + people
                          + "</envolvidosChamada></rtnRecuperarEnvolvidosChamada>";
        monitor.accept(xml(response));
        List<CadMonitor.Event> sent = contacts(events);
        check(sent.size() == 2, "Lista publicada em ambos os tópicos vinculados");
        check(sent.get(0).body.contains("Solicitante: Maria") && sent.get(0).body.contains("Envolvido: João")
                  && sent.get(0).body.contains("https://wa.me/5531999991234")
                  && !sent.get(0).body.contains("https://wa.me/5532101234")
                  && !sent.get(0).body.contains("NAO_EXPORTAR"),
              "Lista mínima e número sem DDD não inventado");
        monitor.accept(xml(response));
        update(monitor, "20", "NO_LOCAL", true);
        check(contacts(events).size() == 2 && queries.size() == 1,
              "Repetição e estado não reconsultam contatos");
        update(monitor, "20", "ENCERRADO", true);
        monitor.accept(xml("<rtnRecuperarEnvolvidosChamada readerCallId='10'><envolvidosChamada>"
                           + person("3", "Pedro", "+55 31 3333-1234", false)
                           + "</envolvidosChamada></rtnRecuperarEnvolvidosChamada>"));
        sent = contacts(events);
        check(sent.size() == 3 && sent.get(2).resourceId.equals("21"),
              "Resposta tardia exclui empenho encerrado");
        update(monitor, "22", "DESPACHADO", true);
        sent = contacts(events);
        check(queries.size() == 1 && sent.get(sent.size() - 1).resourceId.equals("22")
                  && sent.get(sent.size() - 1).body.contains("Pedro"),
              "Nova viatura reutiliza contatos em cache");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 100; i++)
            many.append(person("p" + i, "Pessoa " + i, "31999991234", false));
        monitor.accept(xml("<rtnRecuperarEnvolvidosChamada readerCallId='10'><envolvidosChamada>" + many
                           + "</envolvidosChamada></rtnRecuperarEnvolvidosChamada>"));
        int count = 0;
        for (CadMonitor.Event e : contacts(events)) {
            check(e.discordBody.length() < 3500, "Blocos cabem no Discord sem truncamento");
            if (e.resourceId.equals("21"))
                count += e.body.split("Envolvido: Pessoa ", -1).length - 1;
        }
        check(count == 100, "Todos os envolvidos preservados em múltiplos blocos");
        update(monitor, "99", "DESPACHADO", true); // Fora do filtro por unidade.
        monitor.accept(
            xml("<envInformarAlteracaoRecurso><resumoRecurso>"
                + resource("99", "DESPACHADO", true).replace("<idUnidadeServico>7", "<idUnidadeServico>8")
                + "</resumoRecurso></envInformarAlteracaoRecurso>"));
        int before = contacts(events).size();
        monitor.accept(xml(response));
        check(contacts(events).size() == before, "Recurso removido do filtro não recebe resposta");
    }
    static void verifySession() throws Exception {
        List<Element> received = new ArrayList<>();
        List<String> logs = new ArrayList<>();
        CadXmppReader reader = new CadXmppReader(
            ReaderOptions.parse(new String[] {"--user", "TEST", "--password", "fake"}), new CadListener() {
                public void onMessage(Element e) { received.add(e); }
                public void onLog(String category, String message) { logs.add(category + ":" + message); }
            });
        StringWriter wire = new StringWriter();
        field(reader, "writer", new BufferedWriter(wire));
        field(reader, "socket", new Socket() {
            public InetAddress getLocalAddress() { return InetAddress.getLoopbackAddress(); }
        });
        field(reader, "boundJid", "test@localhost/reader");
        field(reader, "prerequisitesReady", true);
        Method process = CadXmppReader.class.getDeclaredMethod("processCad", Element.class);
        process.setAccessible(true);
        reader.requestParticipants("10");
        reader.requestParticipants("10");
        reader.requestHistory("11");
        check(wire.toString().contains("envRecuperarEnvolvidosChamada")
                  && !wire.toString().contains("envRecuperarResumosHistoricosChamada"),
              "Consultas seriais no mesmo transporte");
        process.invoke(
            reader, xml("<rtnRecuperarEnvolvidosChamada id='1' status='0'><paginationBean>"
                        + "<totalSize>2</totalSize><pageSize>1</pageSize></paginationBean><envolvidosChamada>"
                        + person("1", "Maria", "31999991234", true)
                        + "</envolvidosChamada></rtnRecuperarEnvolvidosChamada>"));
        check(wire.toString().contains("currentPage='2'")
                  && received.get(0).getAttribute("readerCallId").equals("10"),
              "Paginação correlacionada ao chamado");
        process.invoke(
            reader, xml("<rtnRecuperarEnvolvidosChamada id='2' status='0'><paginationBean>"
                        + "<totalSize>2</totalSize><pageSize>1</pageSize></paginationBean><envolvidosChamada>"
                        + person("2", "João", "3133331234", false)
                        + "</envolvidosChamada></rtnRecuperarEnvolvidosChamada>"));
        check(wire.toString().contains("envRecuperarResumosHistoricosChamada"),
              "Histórico prossegue após envolvidos");
        process.invoke(reader, xml("<rtnRecuperarResumosHistoricosChamada id='3' status='3'/>"));
        reader.requestParticipants("12");
        process.invoke(reader, xml("<rtnRecuperarEnvolvidosChamada id='4' status='3'/>"));
        reader.requestParticipants("13");
        process.invoke(reader, xml("<rtnRecuperarEnvolvidosChamada id='5' "
                                   + "status='0'><envolvidosChamada/></rtnRecuperarEnvolvidosChamada>"));
        check(logs.stream().anyMatch(s -> s.startsWith("ENVOLVIDOS:") && s.contains("recusada"))
                  && received.size() == 3,
              "Recusa não é publicada, resposta vazia é aceita e sessão continua");
        reader.requestParticipants("14");
        reader.requestHistory("15");
        Field pendingField = CadXmppReader.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
        Map<?, ?> pending = (Map<?, ?>) pendingField.get(reader);
        field(pending.get("6"), "deadlineNanos", 0L);
        Method maintain = CadXmppReader.class.getDeclaredMethod("maintainSession");
        maintain.setAccessible(true);
        maintain.invoke(reader);
        check(logs.stream().anyMatch(s -> s.startsWith("ENVOLVIDOS:") && s.contains("sem resposta"))
                  && pending.containsKey("7"),
              "Timeout de envolvidos libera a fila sem interromper a sessão");
    }
    public static void main(String[] args) throws Exception {
        verifyMonitor();
        verifySession();
        System.out.println("PASS envolvidos: despacho, cache compartilhado, paginação, permissão, resumo, "
                           + "WhatsApp e encerramento");
    }
}
