package br.gov.mg.sids.reader;

import java.time.Instant;
import java.util.*;
import org.w3c.dom.Element;

/** Projeção de leitura para a Web; nunca consulta o CAD nem produz publicações. */
final class CadOperationalState {
    final CadCommunication communication = new CadCommunication();
    final CadCallDetails details = new CadCallDetails();
    private final Map<String, Map<String, Object>> calls = new LinkedHashMap<>(), resources = new LinkedHashMap<>();
    private CadResourceFilter scope;
    private final Map<String,String> unitNames=new HashMap<>();
    private String status = "Conecte ao CAD e inicie o monitoramento na janela desktop.", updated = "";
    private boolean connected, callsReady, resourcesReady;
    private long revision, activityId, cachedRevision = -1;
    private Map<String,Object> cachedSnapshot;
    synchronized long version() { return revision; }
    synchronized long activityVersion() { return activityId; }
    private final Deque<Map<String,Object>> activity = new ArrayDeque<>();
    private Map<String,Object> sessionConfig = Collections.emptyMap();
    private String callFilterId = "", resourceFilterId = "";
    private String callFilterName = "", resourceFilterName = "";
    synchronized void sessionConfig(String host, int port, boolean insecure) {
        unitNames.clear();revision++; sessionConfig = Java8.map("host",host,"port",port,"tlsValidation",!insecure);
    }
    synchronized void filterConfig(String calls, String resources) {filterConfig(calls, resources, "", "");}
    synchronized void filterConfig(String calls, String resources, String callName, String resourceName) {
        callFilterId=calls;resourceFilterId=resources;callFilterName=callName;resourceFilterName=resourceName;revision++;
    }
    private String botStatus="parado";
    synchronized void botStatus(String value){if(!botStatus.equals(value)){botStatus=value;revision++;}}
    private String publicationStatus = "Discord: publicação desativada nesta sessão";
    synchronized void activity(String category, String title, String details) {
        activity.addFirst(Java8.map("id",++activityId,"at",Instant.now().toString(),"category",category,"title",title,"details",details));
        while (activity.size() > 1000) activity.removeLast();
    }
    synchronized List<Object> activities() { return new ArrayList<>(activity); }
    synchronized List<Object> activities(long after) {
        List<Object> result = new ArrayList<>();
        for(Map<String,Object> item:activity) { if(((Number)item.get("id")).longValue()<=after) break; result.add(item); }
        return result;
    }
    synchronized void publicationStatus(String value) { if(!publicationStatus.equals(value)){publicationStatus = value;revision++;} }


    synchronized void reset() {
        details.reset(); calls.clear(); resources.clear(); scope = null; callFilterId = resourceFilterId = callFilterName = resourceFilterName = "";
        connected = callsReady = resourcesReady = false;
        status = "Aguardando seleção dos filtros e início do monitoramento.";
        updated = ""; revision++;
    }
    synchronized void authenticated(){connected=true;status="CAD conectado · aguardando seleção dos filtros e monitoramento.";revision++;}
    synchronized void start(CadResourceFilter filter) {
        reset(); scope = filter; connected = true;
        status = "Carregando ocorrências e recursos do filtro selecionado…";
    }
    synchronized void disconnected() {
        details.disconnected(); connected = false; status = "CAD desconectado. Os dados exibidos são da última atualização."; revision++;
    }
    synchronized void accept(Element message) {
        details.accept(message);
        if("rtnRecuperarCacheAntesValidarPreRequisitos".equals(message.getLocalName())){
            for(Element unit:CadXml.collectionItems(message,"unidadesServico","unidadeServico")){
                String id=unit.getAttribute("id");if(id.isEmpty())id=CadXml.text(unit,"idUnidadeServico");
                String name=CadXml.text(unit,"descricao");if(!id.isEmpty()&&!name.isEmpty())unitNames.put(id,name);
            }
            revision++;return;
        }
        if (scope == null || !connected) return;
        String tag = message.getLocalName();
        boolean update = tag.startsWith("envInformarAlteracao");
        if (!update && !tag.equals("rtnRecuperarResumosChamadasAtivas") && !tag.equals("rtnRecuperarResumosRecursosParaEmpenho")) return;
        if (tag.equals("rtnRecuperarResumosChamadasAtivas")) callsReady = true;
        if (tag.equals("rtnRecuperarResumosRecursosParaEmpenho")) resourcesReady = true;
        List<Element> changedCalls = update ? CadXml.elements(message,"resumoChamada") : CadXml.collectionItems(message,"resumosChamadas","resumoChamada");
        List<Element> changedResources = update ? CadXml.elements(message,"resumoRecurso") : CadXml.collectionItems(message,"resumosRecursos","resumoRecurso");
        if(update && changedCalls.isEmpty() && changedResources.isEmpty()) return;
        for (Element c : changedCalls) {
            String id = CadXml.text(c, "idChamada"), state = CadXml.text(c, "situacaoChamada");
            if (id.isEmpty()) continue;
            if (state.equals("C") || state.equals("CLASSIFICADA")) { calls.remove(id); continue; }
            details.summary(id,c);
            Set<String> natures = new LinkedHashSet<>();
            List<Object> natureItems = new ArrayList<>();
            for (Element n : CadXml.collectionItems(c, "resumosNaturezasChamada", "resumoNaturezaChamada")) {
                String code = CadXml.text(n, "codigo"), description = CadXml.text(n, "descricao");
                String label = (code + " " + description).trim();
                if (!label.isEmpty() && natures.add(label)) natureItems.add(Java8.map("code", code, "description", description));
            }
            List<Object> embedded = new ArrayList<>();
            Set<String> areas=new LinkedHashSet<>();
            boolean embeddedKnown = false;
            for (Element attendance : CadXml.collectionItems(c, "resumosAtendimentosChamada", "resumoAtendimentoChamada")) {
                if (!"BM".equals(CadXml.text(attendance, "orgao"))) continue;
                embeddedKnown = true;
                areas.add(CadXml.text(attendance,"idUnidadeArea"));
                for (Element assignment : CadXml.collectionItems(attendance, "resumosEmpenhosRecurso", "resumoEmpenhoRecursoChamada")) {
                    String resourceId = CadXml.text(assignment, "idRecurso"), assignmentState = CadXml.text(assignment, "situacaoEmpenhoRecurso");
                    if (!resourceId.isEmpty() && active(assignmentState)) embedded.add(Java8.map("resourceId", resourceId,
                        "prefix", CadXml.text(assignment, "prefixo"), "status", assignmentState));
                }
            }
            calls.put(id, Java8.map("id", id, "number", CadXml.text(c, "numeroChamada"),
                "complement",CadXml.text(c,"complementoEndereco"),"reference",CadXml.text(c,"referencia"),"requestedAt",CadXml.text(c,"dataHoraSolicitacao"),"historyCount",CadXml.text(c,"quantidadeHistoricos"),
                "nature", String.join(" · ", natures), "natures", natureItems, "address", CadXml.text(c, "descricaoLocalizacao"),
                "status", state, "lat", coordinate(CadXml.text(c, "latitude"), 90),
                "lon", coordinate(CadXml.text(c, "longitude"), 180), "updatedAt", Instant.now().toString(),
                "areaIds",new ArrayList<>(areas),"_revision", revision + 1, "_embeddedKnown", embeddedKnown, "_assignments", embedded));
        }
        for (Element r : changedResources) {
            String id = CadXml.text(r, "idRecurso");
            if (id.isEmpty()) continue;
            if (!scope.matches(r)) { resources.remove(id); continue; }
            List<Object> assignments = new ArrayList<>();
            for (Element a : CadXml.collectionItems(r, "resumosEmpenhosRecurso", "resumoEmpenhoRecurso")) {
                String callId = CadXml.text(a, "idChamada"), assignmentState = CadXml.text(a, "situacaoEmpenhoRecurso");
                if (!callId.isEmpty() && active(assignmentState)) assignments.add(Java8.map("callId", callId, "status", assignmentState));
            }
            resources.put(id, Java8.map("id", id, "prefix", CadXml.text(r, "prefixo"),
                "unitId", CadXml.text(r, "idUnidadeServico"), "unitName", CadXml.text(r, "descricaoUnidadeServico"),
                "typeId", CadXml.text(r, "idTipoRecurso"), "type", CadXml.text(r, "tipoRecurso"),
                "plate",CadXml.text(r,"placa"),"statusAt",CadXml.text(r,"dataHoraSituacaoRecurso"),
                "status", CadXml.text(r, "situacaoRecurso"), "restriction", CadXml.text(r, "motivoRestricaoEmpenho"),
                "assignments", assignments, "_revision", revision + 1));
        }
        updated = Instant.now().toString(); revision++;
        if (callsReady && resourcesReady) status = "Monitoramento ativo · dados dos filtros selecionados no CAD.";
    }
    static Double coordinate(String value, double limit) {
        try { double n = Double.parseDouble(value); return Double.isFinite(n) && Math.abs(n) <= limit ? n : null; }
        catch (NumberFormatException e) { return null; }
    }
    private static boolean active(String state) {
        return !state.isEmpty() && !Java8.set("TR", "TERMINADO", "ENCERRADO").contains(state);
    }
    private static long version(Map<String, Object> value) { return ((Number)value.get("_revision")).longValue(); }
    synchronized Map<String, Object> snapshot() {
        if(cachedSnapshot != null && cachedRevision == revision) return cachedSnapshot;
        Map<String, List<Map<String, Object>>> byCall = new HashMap<>();
        List<Object> resourceViews = new ArrayList<>(), callViews = new ArrayList<>();
        for (Map<String, Object> r : resources.values()) {
            Map<String, Object> view = new LinkedHashMap<>(r); view.remove("_revision"); resourceViews.add(view);
            for (Object item : BotJson.array(r.get("assignments"))) {
                Map<String, Object> a = BotJson.object(item);
                byCall.computeIfAbsent(BotJson.text(a, "callId"), key -> new ArrayList<>()).add(Java8.map(
                    "resourceId", r.get("id"), "prefix", r.get("prefix"), "status", a.get("status"), "_revision", r.get("_revision")));
            }
        }
        for (Map<String, Object> c : calls.values()) {
            Map<String, Map<String, Object>> links = new LinkedHashMap<>();
            for (Object item : BotJson.array(c.get("_assignments"))) {
                Map<String, Object> a = BotJson.object(item);
                Map<String, Object> resource = resources.get(BotJson.text(a, "resourceId"));
                // Um resumo de recurso posterior substitui o vínculo antigo do resumo da chamada.
                if (resource == null || version(resource) < version(c)) {
                    Map<String,Object> link = new LinkedHashMap<>(a);
                    if(resource != null && BotJson.text(link,"prefix").isEmpty()) link.put("prefix",resource.get("prefix"));
                    links.put(BotJson.text(a,"resourceId"),link);
                }
            }
            for (Map<String, Object> a : byCall.getOrDefault(BotJson.text(c, "id"), Collections.emptyList())) {
                if (!Boolean.TRUE.equals(c.get("_embeddedKnown")) || version(a) >= version(c)) {
                    Map<String, Object> link = new LinkedHashMap<>(a); link.remove("_revision"); links.put(BotJson.text(a, "resourceId"), link);
                }
            }
            String status = !links.isEmpty() ? "linked" : Java8.set("E", "EMPENHADA").contains(c.get("status")) ? "reported"
                : callsReady && resourcesReady ? "unlinked" : "unknown";
            Map<String, Object> view = new LinkedHashMap<>(c);
            view.remove("_revision"); view.remove("_embeddedKnown"); view.remove("_assignments");
            List<Object> areas=new ArrayList<>();
            for(Object value:BotJson.array(c.get("areaIds"))){String id=String.valueOf(value);areas.add(Java8.map("id",id,"name",id.isEmpty()?"Área não informada":unitNames.getOrDefault(id,"Unidade ID "+id)));}
            view.remove("areaIds");view.put("areas",areas);
            view.put("assignedResources", new ArrayList<>(links.values())); view.put("assignmentStatus", status); callViews.add(view);
        }
        cachedRevision = revision;
        cachedSnapshot = Java8.map("calls", callViews, "resources", resourceViews,
            "connected", connected, "ready", connected && callsReady && resourcesReady,
            "status", status, "updatedAt", updated, "revision", revision,
            "sessionConfig",sessionConfig,"callFilterName",callFilterName,"resourceFilterName",resourceFilterName,"callFilterId",callFilterId,"resourceFilterId",resourceFilterId,"botStatus",botStatus,"publicationStatus", publicationStatus, "scopeUnits", scope == null ? Collections.emptySet() : scope.units,
            "scopeTypes", scope == null ? Collections.emptySet() : scope.types);
        return cachedSnapshot;
    }
    synchronized Map<String,Object> callDetails(String id){
        Map<String,Object> view=null;
        for(Object item:BotJson.array(snapshot().get("calls")))if(id.equals(BotJson.text(BotJson.object(item),"id"))){view=BotJson.object(item);break;}
        if(view==null)throw new IllegalArgumentException("Ocorrência fora do estado atual.");
        Map<String,Object> result=new LinkedHashMap<>(details.get(id));result.put("call",view);return result;
    }
    synchronized Map<String, Object> call(String id) {
        if (!connected || !callsReady || !resourcesReady) throw new IllegalArgumentException("Aguarde o monitoramento CAD ficar ativo.");
        Map<String, Object> call = calls.get(id);
        if (call == null || call.get("lat") == null || call.get("lon") == null)
            throw new IllegalArgumentException("Ocorrência sem coordenadas válidas ou fora do estado atual.");
        return new LinkedHashMap<>(call);
    }
}
