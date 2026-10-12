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
        final CadXmppReader[] session = new CadXmppReader[1];
        session[0] = new CadXmppReader(ReaderOptions.parse(args), new CadListener() {
            public void onLog(String category, String text) {
                System.out.println("[" + category + "] " + text);
            }
            public void onHistoryFinished(String callId, boolean complete) {
                monitor.historyFinished(callId, complete);
            }
            public void onMessage(Element message) {
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
        if (!summaryRefused.get()) throw new AssertionError("Recusa do relatório não foi tratada isoladamente");
        if (!summaryReceived.get()) throw new AssertionError("Relatório do segundo filtro não recebido");
        if (historyEvents.get() != 1)
            throw new AssertionError("Páginas de histórico não recebidas");
        if (resourceEvents.get() != 2)
            throw new AssertionError("Carga e atualização filtradas não recebidas");
    }
}
