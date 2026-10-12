package br.gov.mg.sids.reader;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import org.w3c.dom.Element;

/** Catálogo da sessão e relatórios pontuais; cache curto evita repetir consultas simultâneas. */
final class CadSummary {
    private final java.util.function.Function<String, CompletableFuture<Element>> fetch;
    private final Map<String, String> filters = new ConcurrentHashMap<>(), units = new ConcurrentHashMap<>();
    private String cachedId = "", cachedText = "";
    private long cachedAt;
    private CompletableFuture<String> inFlight;
    private String inFlightId;
    CadSummary(CadXmppReader reader) {
        this.fetch = reader::requestFilterSummary;
    }
    CadSummary(java.util.function.Function<String, CompletableFuture<Element>> fetch) {
        this.fetch = fetch;
    }
    void catalog(Element cache) {
        filters.clear();
        for (Element f : CadXml.collectionItems(cache, "filtrosChamada", "filtroChamada"))
            filters.put(f.getAttribute("id"), CadXml.text(f, "descricao"));
    }
    void accept(Element message) {
        if (!"rtnRecuperarCacheAntesValidarPreRequisitos".equals(message.getLocalName()))
            return;
        for (Element unit : CadXml.collectionItems(message, "unidadesServico", "unidadeServico")) {
            String id = unit.getAttribute("id");
            if (id.isEmpty())
                id = CadXml.text(unit, "idUnidadeServico");
            String name = CadXml.text(unit, "descricao");
            if (!id.isEmpty() && !name.isEmpty())
                units.put(id, name);
        }
    }
    boolean hasFilters() { return !filters.isEmpty(); }

    Map<String, String> filters() {
        return new TreeMap<>(filters);
    }
    synchronized CompletableFuture<String> query(String id) {
        if (!filters.containsKey(id))
            return failed("Selecione um filtro do catálogo da sessão CAD");
        if (id.equals(cachedId) && System.nanoTime() - cachedAt < TimeUnit.SECONDS.toNanos(30))
            return CompletableFuture.completedFuture(cachedText);
        if (inFlight != null && !inFlight.isDone())
            return id.equals(inFlightId) ? inFlight
                                         : failed("Outra consulta está em andamento; tente novamente");
        inFlightId = id;
        inFlight = fetch.apply(id).thenApply(response -> {
            String text = format(filters.getOrDefault(id, id), response, units);
            synchronized (this) {
                cachedId = id;
                cachedText = text;
                cachedAt = System.nanoTime();
            }
            return text;
        });
        return inFlight;
    }
    private static CompletableFuture<String> failed(String message) {
        CompletableFuture<String> f = new CompletableFuture<>();
        f.completeExceptionally(new IllegalArgumentException(message));
        return f;
    }
    /** Conta chamadas únicas, naturezas sem duplicação por chamada e área do atendimento BM. */
    static String format(String filter, Element response, Map<String, String> units) {
        Map<String, Element> unique = new LinkedHashMap<>();
        for (Element c : CadXml.collectionItems(response, "resumosChamadas", "resumoChamada")) {
            String id = CadXml.text(c, "idChamada");
            if (id.isEmpty())
                throw new IllegalArgumentException(
                    "Resposta CAD contém chamada sem ID; relatório não calculado");
            unique.put(id, c);
        }
        Map<String, Integer> totalNatures = new TreeMap<>();
        Map<String, Set<String>> areaCalls = new TreeMap<>();
        Map<String, Map<String, Integer>> areaNatures = new TreeMap<>();
        for (Map.Entry<String, Element> entry : unique.entrySet()) {
            Element c = entry.getValue();
            Set<String> natures = new TreeSet<>(), areas = new TreeSet<>();
            for (Element n : CadXml.collectionItems(c, "resumosNaturezasChamada", "resumoNaturezaChamada")) {
                String text = (CadXml.text(n, "codigo") + " " + CadXml.text(n, "descricao")).trim();
                if (!text.isEmpty())
                    natures.add(text);
            }
            if (natures.isEmpty())
                natures.add("Natureza não informada");
            for (Element a :
                 CadXml.collectionItems(c, "resumosAtendimentosChamada", "resumoAtendimentoChamada")) {
                if (!"BM".equals(CadXml.text(a, "orgao")))
                    continue;
                String id = CadXml.text(a, "idUnidadeArea");
                areas.add(id.isEmpty() ? "Área não informada"
                                       : units.getOrDefault(id, "Unidade ID " + id) + " (" + id + ")");
            }
            if (areas.isEmpty())
                areas.add("Área BM não informada");
            for (String n : natures)
                totalNatures.merge(n, 1, Integer::sum);
            for (String area : areas) {
                areaCalls.computeIfAbsent(area, k -> new HashSet<>()).add(entry.getKey());
                Map<String, Integer> counts = areaNatures.computeIfAbsent(area, k -> new TreeMap<>());
                for (String n : natures)
                    counts.merge(n, 1, Integer::sum);
            }
        }
        StringBuilder out = new StringBuilder("Totais de ocorrências por natureza — ")
                                .append(filter)
                                .append("\nConsultado em: ")
                                .append(ZonedDateTime.now(ZoneId.of("America/Sao_Paulo"))
                                            .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss XXX")))
                                .append("\n\nTOTAL: ")
                                .append(unique.size())
                                .append(" chamadas\n");
        totalNatures.forEach(
            (n, count) -> out.append("• ").append(n).append(": ").append(count).append('\n'));
        out.append("\nPOR UNIDADE DE ÁREA — BM\n");
        areaCalls.forEach((area, ids) -> {
            out.append("\n").append(area).append(" — ").append(ids.size()).append(" chamadas\n");
            areaNatures.get(area).forEach(
                (n, count) -> out.append("• ").append(n).append(": ").append(count).append('\n'));
        });
        out.append("\nUma chamada com várias naturezas pode aparecer em mais de uma categoria. Área " +
                   "ausente não é substituída pela unidade de atendimento.");
        if (unique.size() >= 1000)
            out.append("\nATENÇÃO: limite de 1.000 registros atingido; o resultado pode estar incompleto.");
        return out.toString();
    }
}
