package br.gov.mg.sids.reader;

import org.w3c.dom.Element;

/** Exercita o fluxo da interface: conectar, receber catálogo e assinar sem reconectar. */
public final class ReaderSessionTest {
    public static void main(String[] args) throws Exception {
        java.util.concurrent.atomic.AtomicInteger resourceEvents
            = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger historyEvents
            = new java.util.concurrent.atomic.AtomicInteger();
        CadMonitor monitor = new CadMonitor(event -> {
            if (event.title.startsWith("HISTÓRICO")) {
                if (!event.title.equals("HISTÓRICO INICIAL") || !event.body.contains("Registro 1") || !event.body.contains("Registro 2"))
                    throw new AssertionError("Contexto não reúne todas as páginas");
                historyEvents.incrementAndGet();
            }
            if (!event.resourceId.isEmpty() && !event.title.startsWith("HISTÓRICO")) {
                if (!event.resourceId.equals("456"))
                    throw new AssertionError("Recurso fora do filtro no monitor");
                resourceEvents.incrementAndGet();
            }
        });
        java.util.concurrent.atomic.AtomicBoolean summaryRefused = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean summaryReceived = new java.util.concurrent.atomic.AtomicBoolean();
        CadCommunication communication = new CadCommunication();
        CadCallDetails details = new CadCallDetails();
        final CadXmppReader[] session = new CadXmppReader[1];
        session[0] = new CadXmppReader(ReaderOptions.parse(args), new CadListener() {
            public void onCadRequest(String id,String operation,String callId,int page,String outcome,long elapsed){
                communication.request(id,operation,callId,page,outcome,elapsed);details.request(callId,operation,page,outcome);
            }
            public void onCadNotification(String operation){communication.notification();}
            public void onLog(String category, String text) {
                System.out.println("[" + category + "] " + text);
            }
            public void onHistoryFinished(String callId, boolean complete) {
                details.finished(callId,false,complete);
                monitor.historyFinished(callId, complete);
            }
            public void onMessage(Element message) {
                details.accept(message);
                monitor.accept(message);
            }
            public void onFilters(Element cache) {
                try {
                    monitor.setResourceFilter(CadResourceFilter.from(
                        CadXml.collectionItems(cache, "filtrosRecurso", "filtroRecurso").get(0)));
                    session[0].requestFilterSummary("99").thenAccept(response -> {
                        if (!CadXml.text(CadXml.collectionItems(response, "resumosChamadas", "resumoChamada").get(0), "numeroChamada").equals("OUTSIDE-SUMMARY"))
                            throw new AssertionError("Consulta pontual não recebeu resposta independente");
                        summaryReceived.set(true);
                        session[0].requestFilterSummary("98").whenComplete((ignored,error) -> summaryRefused.set(error != null));
                    });
                    session[0].applyFilters("12", "34");
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        monitor.setHistoryRequest(id -> {
            try {
                session[0].requestHistory(id);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        session[0].start();
        checkCommunication(communication,details);
        if (!summaryRefused.get()) throw new AssertionError("Recusa do relatório não foi tratada isoladamente");
        if (!summaryReceived.get()) throw new AssertionError("Relatório do segundo filtro não recebido");
        if (historyEvents.get() != 1)
            throw new AssertionError("Páginas de histórico não recebidas");
        if (resourceEvents.get() != 2)
            throw new AssertionError("Carga e atualização filtradas não recebidas");
    }
    static void checkCommunication(CadCommunication communication,CadCallDetails details){
        java.util.Map<String,Object> stats=communication.snapshot();
        if(((Number)stats.get("total")).intValue()!=13 || ((Number)stats.get("completed")).intValue()!=11 || ((Number)stats.get("rejected")).intValue()!=2 || ((Number)stats.get("pending")).intValue()!=0)
            throw new AssertionError("Contadores não correspondem às requisições reais: "+BotJson.write(stats));
        if(((Number)BotJson.object(stats.get("operations")).get("envRecuperarResumosHistoricosChamada")).intValue()!=3)
            throw new AssertionError("Duas páginas e uma nova consulta recusada devem contar como três requisições");
        if(((Number)stats.get("notifications")).intValue()<1)throw new AssertionError("Atualização espontânea não contabilizada");
        java.util.Map<String,Object> history=BotJson.object(details.get("123").get("history"));
        if(BotJson.array(history.get("items")).size()!=2 || !BotJson.text(history,"status").equals("incomplete"))throw new AssertionError("Histórico paginado não disponível à Web: "+BotJson.write(history));
        System.out.println("PASS telemetria passiva: contadores reais, duas páginas e histórico disponível sem novas consultas");
    }

}
