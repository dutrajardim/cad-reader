package br.gov.mg.sids.reader;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import org.w3c.dom.Element;

/** Relaciona chamadas e empenhos, conservando o último estado recebido na sessão. */
final class CadMonitor {
    private final Map<String, Element> calls = new HashMap<>(), resources = new HashMap<>();
    private final Map<String, String> fingerprints = new HashMap<>();
    private final Map<String, String> previousBodies = new HashMap<>();
    private final Map<String, String> previousLinks = new HashMap<>();
    private final Map<String, Set<String>> resourcesByCall = new HashMap<>();
    private final Consumer<Event> sink;
    private CadResourceFilter resourceFilter;
    private boolean callsReady, resourcesReady, publishing;
    private Consumer<String> historyRequest = id -> {};
    private final Set<String> historyRecipients = new HashSet<>();
    // Enquanto a primeira consulta pagina, guarda contexto separado por empenho.
    private final Map<String, Map<String, Element>> initialHistories = new HashMap<>();
    private final Map<String, String> historyVersions = new HashMap<>();
    private final Map<String, String> historyFingerprints = new HashMap<>();

    private Consumer<String> participantsRequest = id -> {};
    private final Set<String> participantsRequested = new HashSet<>(), participantsRecipients
                                                                       = new HashSet<>();
    private final Map<String, List<Element>> participants = new HashMap<>();
    private final Map<String, String> participantFingerprints = new HashMap<>();

    /** A resposta do servidor determina se o usuário pode consultar os envolvidos. */
    synchronized void setParticipantsRequest(Consumer<String> request) {
        participantsRequest = Objects.requireNonNull(request);
    }

    private Consumer<String> diagnostics = message -> {};

    /** Explica decisões locais sem expor endereço, histórico ou contatos no diagnóstico. */
    synchronized void setDiagnostics(Consumer<String> listener) {
        diagnostics = Objects.requireNonNull(listener);
    }

    /** Consulta complementar somente para chamadas ligadas a recursos acompanhados. */
    synchronized void setHistoryRequest(Consumer<String> request) {
        historyRequest = Objects.requireNonNull(request);
    }
    CadMonitor(Consumer<Event> sink) { this.sink = sink; }

    /** Precisa ser definido antes da assinatura; eventos sem filtro não são autorizados. */
    synchronized void setResourceFilter(CadResourceFilter filter) {
        resourceFilter = Objects.requireNonNull(filter);
        callsReady = resourcesReady = false;
        historyRecipients.clear();
        initialHistories.clear();
        participantsRequested.clear();
        participantsRecipients.clear();
        participants.clear();
        participantFingerprints.clear();
        historyVersions.clear();
        historyFingerprints.clear();
        resources.clear();
        resourcesByCall.clear();
        calls.clear();
        fingerprints.clear();
        previousBodies.clear();
        previousLinks.clear();
    }

    /** Aceita snapshots e eventos; uma ausência não significa encerramento. */
    synchronized void accept(Element message) {
        String tag = message.getLocalName();
        if (tag.equals("rtnRecuperarEnvolvidosChamada")) {
            String callId = message.getAttribute("readerCallId");
            if (!callId.isEmpty()) {
                List<Element> stored = participants.computeIfAbsent(callId, k -> new ArrayList<>());
                for (Element person :
                     CadXml.collectionItems(message, "envolvidosChamada", "envolvidoChamada")) {
                    org.w3c.dom.Document document
                        = message.getOwnerDocument().getImplementation().createDocument(null, null, null);
                    Element contact = document.createElementNS(CadXml.CAD, "envolvidoChamada");
                    contact.setAttribute("id", person.getAttribute("id"));
                    for (String field : Java8.list("idEnvolvidoChamada", "nome", "telefone", "solicitante")) {
                        Element value = document.createElementNS(CadXml.CAD, field);
                        value.setTextContent(CadXml.text(person, field));
                        contact.appendChild(value);
                    }
                    stored.add(contact);
                }
                emitParticipants(callId);
            }
            return;
        }
        if (tag.equals("rtnRecuperarResumosHistoricosChamada")) {
            emitHistories(
                message.getAttribute("readerCallId"),
                CadXml.collectionItems(message, "resumosHistoricosChamada", "resumoHistoricoChamada"));
            return;
        }
        boolean update = tag.startsWith("envInformarAlteracao");
        publishing = update && callsReady && resourcesReady;
        if (!(update || tag.equals("rtnRecuperarResumosChamadasAtivas")
              || tag.equals("rtnRecuperarResumosRecursosParaEmpenho")))
            return;
        if (tag.equals("rtnRecuperarResumosChamadasAtivas"))
            callsReady = true;
        if (tag.equals("rtnRecuperarResumosRecursosParaEmpenho"))
            resourcesReady = true;
        List<Element> cs = update ? CadXml.elements(message, "resumoChamada")
                                  : CadXml.collectionItems(message, "resumosChamadas", "resumoChamada");
        List<Element> rs = update ? CadXml.elements(message, "resumoRecurso")
                                  : CadXml.collectionItems(message, "resumosRecursos", "resumoRecurso");
        // Importa apenas os resumos para um documento sem raiz. Guardar um nó do
        // XML original manteria todo o snapshot vivo por ownerDocument/parentNode.
        org.w3c.dom.Document cacheDocument
            = message.getOwnerDocument().getImplementation().createDocument(null, null, null);
        Set<String> affected = new HashSet<>(), newAssignments = new HashSet<>();
        Set<String> requestedHistories = new HashSet<>();
        Map<String, Integer> changedResources = new HashMap<>();
        boolean historyAction
            = update
              && CadXml.collectionItems(message, "tiposAcoesGeramNotificacao", "i")
                     .stream()
                     .anyMatch(action -> "SALVAR_HISTORICO_CHAMADA".equals(action.getTextContent().trim()));
        Set<String> historyActionCalls = new HashSet<>();
        for (Element c : cs) {
            String id = CadXml.text(c, "idChamada");
            if (id.isEmpty())
                continue;
            Element stored = (Element)cacheDocument.importNode(c, true);
            calls.put(id, stored);
            if (historyAction)
                historyActionCalls.add(id);
            if (!publishing)
                historyVersions.put(id, historyVersion(stored));
            emitCall(stored, update);
            affected.addAll(resourcesByCall.getOrDefault(id, Collections.emptySet()));
        }
        for (Element r : rs) {
            String id = CadXml.text(r, "idRecurso");
            if (id.isEmpty())
                continue;
            if (resourceFilter == null || !resourceFilter.matches(r)) {
                Element excluded = resources.remove(id);
                if (excluded != null)
                    indexAssignments(id, excluded, false);
                fingerprints.keySet().removeIf(key -> key.startsWith(id + ":"));
                previousBodies.keySet().removeIf(key -> key.startsWith(id + ":"));
                previousLinks.keySet().removeIf(key -> key.startsWith(id + ":"));
                historyRecipients.removeIf(key -> key.startsWith(id + ":"));
                initialHistories.keySet().removeIf(key -> key.startsWith(id + ":"));
                participantsRecipients.removeIf(key -> key.startsWith(id + ":"));
                continue;
            }
            Element stored = (Element)cacheDocument.importNode(r, true);
            Element previous = resources.put(id, stored);
            Set<String> formerAssignments = assignmentKeys(id, previous);
            for (String key : assignmentKeys(id, stored))
                if (!formerAssignments.contains(key))
                    newAssignments.add(key);
            if (previous != null)
                indexAssignments(id, previous, false);
            indexAssignments(id, stored, true);
            affected.add(id);
        }
        // Uma alteração só recalcula o recurso recebido ou os ligados à chamada.
        for (String id : affected) {
            Element r = resources.get(id);
            if (r == null)
                continue;
            List<Element> assignments
                = CadXml.collectionItems(r, "resumosEmpenhosRecurso", "resumoEmpenhoRecurso");
            if (assignments.isEmpty())
                emit(r, null, null, update);
            else
                for (Element a : assignments) {
                    String callId = CadXml.text(a, "idChamada");
                    if (emit(r, a, calls.get(callId), update))
                        changedResources.merge(callId, 1, Integer::sum);
                    String assignmentKey = id + ":" + callId;
                    if (publishing && activeAssignment(a) && !callId.isEmpty()
                        && newAssignments.contains(assignmentKey)) {
                        participantsRecipients.add(assignmentKey);
                        emitParticipants(callId);
                        if (participantsRequested.add(callId))
                            participantsRequest.accept(callId);
                    }
                    if (publishing && !callId.isEmpty() && activeAssignment(a)) {
                        Element call = calls.get(callId);
                        boolean historicalChange
                            = call != null && !historyVersion(call).equals(historyVersions.get(callId));
                        boolean signaled = historyActionCalls.contains(callId);
                        if (!newAssignments.contains(assignmentKey) && !historicalChange && !signaled
                            && !historyRecipients.contains(assignmentKey))
                            continue;
                        boolean first = historyRecipients.add(assignmentKey);
                        if (first)
                            initialHistories.put(assignmentKey, new LinkedHashMap<>());
                        if (call != null) {
                            List<Element> history = CadXml.collectionItems(call, "resumosHistoricosChamada",
                                                                           "resumoHistoricoChamada");
                            emitHistories(callId, history);
                            String version = historyVersion(call);
                            if (first || historicalChange || signaled) {
                                historyVersions.put(callId, version);
                                if (requestedHistories.add(callId))
                                    historyRequest.accept(callId);
                            }
                        } else if ((first || signaled) && requestedHistories.add(callId))
                            historyRequest.accept(callId);
                    }
                }
            Set<String> active = new HashSet<>();
            for (Element a : assignments)
                if (activeAssignment(a))
                    active.add(id + ":" + CadXml.text(a, "idChamada"));
            historyRecipients.removeIf(key -> key.startsWith(id + ":") && !active.contains(key));
            initialHistories.keySet().removeIf(key -> key.startsWith(id + ":") && !active.contains(key));
            participantsRecipients.removeIf(key -> key.startsWith(id + ":") && !active.contains(key));
        }
        if (update) {
            Set<String> diagnosed = new HashSet<>();
            for (Element call : cs) {
                String callId = CadXml.text(call, "idChamada");
                if (callId.isEmpty() || !diagnosed.add(callId))
                    continue;
                int linked = resourcesByCall.getOrDefault(callId, Collections.emptySet()).size();
                int changed = changedResources.getOrDefault(callId, 0);
                String decision;
                if (!publishing)
                    decision = "referência inicial ainda incompleta; sem publicação";
                else if (linked == 0)
                    decision = "sem viatura acompanhada com empenho ativo conhecido; sem publicação";
                else if (changed > 0)
                    decision = "eventos de atualização gerados=" + changed
                               + "; encaminhados para publicação local/Discord";
                else
                    decision = "sem alteração nos campos publicados das viaturas vinculadas";
                if (requestedHistories.contains(callId))
                    decision += "; consulta de histórico solicitada";
                diagnostics.accept("chamada=" + CadXml.text(call, "numeroChamada") + "; id=" + callId
                                   + "; viaturas vinculadas=" + linked + "; " + decision);
            }
        }
    }

    /** Identifica novos vínculos ativos, sem confundir uma mudança de estado com novo empenho. */
    private Set<String> assignmentKeys(String resourceId, Element resource) {
        Set<String> keys = new HashSet<>();
        if (resource != null)
            for (Element a :
                 CadXml.collectionItems(resource, "resumosEmpenhosRecurso", "resumoEmpenhoRecurso"))
                if (activeAssignment(a))
                    keys.add(resourceId + ":" + CadXml.text(a, "idChamada"));
        return keys;
    }

    private String historyVersion(Element call) {
        StringBuilder version = new StringBuilder(CadXml.text(call, "quantidadeHistoricos"));
        for (Element item :
             CadXml.collectionItems(call, "resumosHistoricosChamada", "resumoHistoricoChamada"))
            version.append(':')
                .append(CadXml.text(item, "dataHoraInclusao"))
                .append(':')
                .append(CadXml.text(item, "descricao"));
        return AppFiles.hash(version.toString());
    }

    private boolean activeAssignment(Element assignment) {
        String state = CadXml.text(assignment, "situacaoEmpenhoRecurso");
        return !state.equals("TR") && !state.equals("TERMINADO") && !state.equals("ENCERRADO");
    }

    private void indexAssignments(String resourceId, Element resource, boolean add) {
        for (Element a : CadXml.collectionItems(resource, "resumosEmpenhosRecurso", "resumoEmpenhoRecurso")) {
            if (!activeAssignment(a))
                continue;
            String callId = CadXml.text(a, "idChamada");
            if (add)
                resourcesByCall.computeIfAbsent(callId, k -> new HashSet<>()).add(resourceId);
            else {
                Set<String> ids = resourcesByCall.get(callId);
                if (ids != null) {
                    ids.remove(resourceId);
                    if (ids.isEmpty())
                        resourcesByCall.remove(callId);
                }
            }
        }
    }

    /** Chamadas sem empenho também aparecem no histórico local; tópicos são por recurso. */
    private void emitCall(Element c, boolean update) {
        String id = CadXml.text(c, "idChamada"), number = CadXml.text(c, "numeroChamada");
        if (id.isEmpty())
            return;
        String body = "CHAMADA: " + number + "\nOCORRÊNCIA: " + label(CadXml.text(c, "situacaoChamada"))
                      + "\nENDEREÇO: " + CadXml.text(c, "descricaoLocalizacao") + "\n"
                      + optional(c, "complementoEndereco", "COMPLEMENTO")
                      + optional(c, "referencia", "REFERÊNCIA");
        String key = "chamada:" + id, fingerprint = AppFiles.hash(body);
        if (fingerprint.equals(fingerprints.put(key, fingerprint)))
            return;
        sink.accept(new Event("", "—", number, key, fingerprint,
                              (update ? "ATUALIZAÇÃO" : "SITUAÇÃO ATUAL") + " • OCORRÊNCIA "
                                  + label(CadXml.text(c, "situacaoChamada")),
                              body, "", Instant.now().toString(), "", "", publishing));
    }

    private boolean emit(Element r, Element a, Element c, boolean update) {
        String resource = CadXml.text(r, "prefixo"), id = CadXml.text(r, "idRecurso");
        if (id.isEmpty())
            return false;
        String number = a == null ? "—" : CadXml.text(a, "numeroChamada");
        if (c != null && !CadXml.text(c, "numeroChamada").isEmpty())
            number = CadXml.text(c, "numeroChamada");
        String state = a == null ? "" : CadXml.text(a, "situacaoEmpenhoRecurso");
        String cadTime = a == null ? CadXml.text(r, "dataHoraSituacaoRecurso")
                                   : CadXml.text(a, "dataHoraSituacaoEmpenho");
        String address
            = c == null ? "Endereço ainda não recebido pelo filtro" : CadXml.text(c, "descricaoLocalizacao");
        String callState = c == null ? "" : CadXml.text(c, "situacaoChamada");
        String key = id + ":" + (a == null ? "recurso" : CadXml.text(a, "idChamada"));
        String details = "";
        if (c != null) {
            details
                = optional(c, "complementoEndereco", "COMPLEMENTO") + optional(c, "referencia", "REFERÊNCIA");
            List<String> nature = new ArrayList<>();
            for (Element n : CadXml.collectionItems(c, "resumosNaturezasChamada", "resumoNaturezaChamada"))
                nature.add(CadXml.text(n, "codigo") + " " + CadXml.text(n, "descricao"));
            if (!nature.isEmpty())
                details += "NATUREZA: " + String.join("; ", nature) + "\n";
            String requested = CadXml.text(c, "dataHoraSolicitacao");
            if (!requested.isEmpty())
                details += "SOLICITAÇÃO CAD: " + time(requested) + "\n";
        }
        String body = "VIATURA: " + resource + " | CHAMADA: " + number + "\n"
                      + "UNIDADE: " + CadXml.text(r, "descricaoUnidadeServico") + " ("
                      + CadXml.text(r, "idUnidadeServico") + ")\n"
                      + "RECURSO: " + label(CadXml.text(r, "situacaoRecurso")) + "\n"
                      + (a == null ? "" : "EMPENHO: " + label(state) + "\n")
                      + (a == null ? "" : "OCORRÊNCIA: " + label(callState) + "\nENDEREÇO: " + address + "\n")
                      + details
                      + "HORÁRIO DO ESTADO CAD: " + (cadTime.isEmpty() ? "não informado" : time(cadTime));
        String lat = c == null ? "" : CadXml.text(c, "latitude"),
               lon = c == null ? "" : CadXml.text(c, "longitude");
        String dest = coordinates(lat, lon) ? lat + "," + lon : address;
        String links = a == null || (c == null)
                           ? ""
                           : "\n[Google Maps](https://www.google.com/maps/dir/?api=1&destination="
                                 + encode(dest) + "&travelmode=driving) | [Waze](https://waze.com/ul?"
                                 + (coordinates(lat, lon) ? "ll=" : "q=") + encode(dest) + "&navigate=yes)";
        String fingerprint = AppFiles.hash(body + links);
        if (fingerprint.equals(fingerprints.put(key, fingerprint)))
            return false;
        String previous = previousBodies.put(key, body);
        String formerLinks = previousLinks.put(key, links);
        Map<String, String> before = fields(previous), now = fields(body);
        boolean dispatch = "DESPACHADO".equals(label(state))
                           && !Objects.equals(before.get("EMPENHO"), now.get("EMPENHO"));
        boolean full = previous == null || dispatch
                       || (before.getOrDefault("ENDEREÇO", "").contains("ainda não recebido") && c != null);
        StringBuilder compact = new StringBuilder();
        for (Map.Entry<String, String> field : now.entrySet()) {
            String name = field.getKey();
            if (name.equals("VIATURA") || (!full && name.equals("UNIDADE")))
                continue;
            if (full || !Objects.equals(before.get(name), field.getValue()))
                compact.append(name).append(": ").append(field.getValue()).append('\n');
        }
        // Ausências também são alterações: uma referência removida não pode parecer inalterada.
        if (!full)
            for (String name : before.keySet())
                if (!now.containsKey(name) && !name.equals("VIATURA") && !name.equals("UNIDADE"))
                    compact.append(name).append(": removido\n");
        if (!full && compact.indexOf("HORÁRIO DO ESTADO CAD:") < 0)
            compact.append("HORÁRIO DO ESTADO CAD: ").append(now.get("HORÁRIO DO ESTADO CAD"));
        boolean locationChanged = !Objects.equals(formerLinks, links)
                                  || !Objects.equals(before.get("ENDEREÇO"), now.get("ENDEREÇO"));
        String title = (update ? "ATUALIZAÇÃO" : "SITUAÇÃO ATUAL") + " • "
                       + label(state.isEmpty() ? CadXml.text(r, "situacaoRecurso") : state);
        if (!full && Objects.equals(before.get("EMPENHO"), now.get("EMPENHO"))
            && Objects.equals(before.get("RECURSO"), now.get("RECURSO")))
            title = !Objects.equals(before.get("OCORRÊNCIA"), now.get("OCORRÊNCIA"))
                        ? "OCORRÊNCIA • " + label(callState)
                        : "DADOS DA OCORRÊNCIA ATUALIZADOS";
        sink.accept(new Event(id, resource, number, key, fingerprint, title, body, links,
                              Instant.now().toString(), CadXml.text(r, "idUnidadeServico"),
                              CadXml.text(r, "idTipoRecurso"), publishing, Java8.stripTrailing(compact.toString()),
                              locationChanged ? links : ""));
        return true;
    }
    /** Compara campos de apresentação; o CSV conserva o estado completo de cada evento. */
    private static Map<String, String> fields(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        if (body != null)
            for (String line : body.split("\\n")) {
                int colon = line.indexOf(": ");
                if (colon > 0)
                    values.put(line.substring(0, colon), line.substring(colon + 2));
            }
        return values;
    }

    /** Publica registros por viatura, sem repetir páginas nem cortar silenciosamente textos longos. */
    private void emitHistories(String callId, List<Element> history) {
        if (callId.isEmpty())
            return;
        List<Element> ordered = new ArrayList<>(history);
        ordered.sort(Comparator.comparing(e -> CadXml.text(e, "dataHoraInclusao")));
        for (String resourceId : resourcesByCall.getOrDefault(callId, Collections.emptySet())) {
            String assignment = resourceId + ":" + callId;
            if (!historyRecipients.contains(assignment))
                continue;
            Element r = resources.get(resourceId), c = calls.get(callId);
            if (r == null)
                continue;
            String prefix = CadXml.text(r, "prefixo");
            String number = c == null ? "" : CadXml.text(c, "numeroChamada");
            if (number.isEmpty()) {
                for (Element a : CadXml.collectionItems(r, "resumosEmpenhosRecurso", "resumoEmpenhoRecurso"))
                    if (callId.equals(CadXml.text(a, "idChamada")))
                        number = CadXml.text(a, "numeroChamada");
                if (number.isEmpty())
                    number = callId;
            }
            for (Element item : ordered) {
                String description = CadXml.text(item, "descricao");
                if (description.trim().isEmpty())
                    continue;
                String date = CadXml.text(item, "dataHoraInclusao");
                String identifier = item.getAttribute("id");
                if (identifier.isEmpty())
                    identifier = CadXml.text(item, "idHistoricoChamada");
                if (identifier.isEmpty())
                    identifier = AppFiles.hash(date + ":" + description);
                String key = assignment + ":historico:" + identifier;
                String fingerprint = AppFiles.hash(date + ":" + description);
                Map<String, Element> initial = initialHistories.get(assignment);
                if (initial != null) {
                    initial.put(identifier, (Element)item.cloneNode(true));
                    continue;
                }
                if (fingerprint.equals(historyFingerprints.put(key, fingerprint)))
                    continue;
                int parts = (description.length() + 2699) / 2700;
                for (int part = 0; part < parts; part++) {
                    String body = "VIATURA: " + prefix + " | CHAMADA: " + number
                                  + "\nREGISTRO CAD: " + time(date) + "\nHISTÓRICO DA OCORRÊNCIA:\n"
                                  + description.substring(part * 2700,
                                                          Math.min(description.length(), (part + 1) * 2700));
                    sink.accept(new Event(
                        resourceId, prefix, number, key + ":" + part, AppFiles.hash(fingerprint + ":" + part),
                        "HISTÓRICO DA OCORRÊNCIA" + (parts > 1 ? " • PARTE " + (part + 1) + "/" + parts : ""),
                        body, "", Instant.now().toString(), CadXml.text(r, "idUnidadeServico"),
                        CadXml.text(r, "idTipoRecurso"), true));
                }
            }
        }
    }

    /** Finaliza o contexto apenas depois de todas as páginas; falhas preservam o recebido. */
    synchronized void historyFinished(String callId, boolean complete) {
        for (String resourceId : resourcesByCall.getOrDefault(callId, Collections.emptySet())) {
            String assignment = resourceId + ":" + callId;
            Map<String, Element> initial = initialHistories.remove(assignment);
            if (initial == null || !historyRecipients.contains(assignment) || initial.isEmpty())
                continue;
            Element resource = resources.get(resourceId), call = calls.get(callId);
            if (resource == null)
                continue;
            String number = call == null ? "" : CadXml.text(call, "numeroChamada");
            if (number.isEmpty())
                for (Element a : CadXml.collectionItems(resource, "resumosEmpenhosRecurso", "resumoEmpenhoRecurso"))
                    if (callId.equals(CadXml.text(a, "idChamada")))
                        number = CadXml.text(a, "numeroChamada");
            if (number.isEmpty()) number = callId;
            List<Map.Entry<String, Element>> ordered = new ArrayList<>(initial.entrySet());
            ordered.sort(Comparator.comparing(e -> CadXml.text(e.getValue(), "dataHoraInclusao")));
            StringBuilder context = new StringBuilder();
            for (Map.Entry<String, Element> entry : ordered) {
                String date = CadXml.text(entry.getValue(), "dataHoraInclusao");
                String description = CadXml.text(entry.getValue(), "descricao");
                historyFingerprints.put(assignment + ":historico:" + entry.getKey(), AppFiles.hash(date + ":" + description));
                if (context.length() > 0) context.append("\n\n");
                context.append("REGISTRO CAD: ").append(time(date)).append("\n").append(description);
            }
            String text = context.toString();
            int parts = (text.length() + 2699) / 2700;
            for (int part = 0; part < parts; part++) {
                String body = "CHAMADA: " + number + "\nCONTEXTO INICIAL DO CHAMADO"
                    + (complete ? "" : " • CONSULTA INCOMPLETA") + "\n"
                    + text.substring(part * 2700, Math.min(text.length(), (part + 1) * 2700));
                sink.accept(new Event(resourceId, CadXml.text(resource, "prefixo"), number,
                    assignment + ":historico:contexto:" + part, AppFiles.hash(body),
                    "HISTÓRICO INICIAL" + (parts > 1 ? " • PARTE " + (part + 1) + "/" + parts : ""),
                    body, "", Instant.now().toString(), CadXml.text(resource, "idUnidadeServico"),
                    CadXml.text(resource, "idTipoRecurso"), true));
            }
        }
    }

    /** Publica somente nome/telefone e condição de solicitante; demais dados pessoais são ignorados. */
    private void emitParticipants(String callId) {
        List<Element> people = participants.getOrDefault(callId, Collections.emptyList());
        for (String resourceId : resourcesByCall.getOrDefault(callId, Collections.emptySet())) {
            String assignment = resourceId + ":" + callId;
            if (!participantsRecipients.contains(assignment))
                continue;
            Element r = resources.get(resourceId), call = calls.get(callId);
            if (r == null)
                continue;
            String number = call == null ? "" : CadXml.text(call, "numeroChamada");
            if (number.isEmpty())
                for (Element a : CadXml.collectionItems(r, "resumosEmpenhosRecurso", "resumoEmpenhoRecurso"))
                    if (callId.equals(CadXml.text(a, "idChamada")))
                        number = CadXml.text(a, "numeroChamada");
            if (number.isEmpty())
                number = callId;
            List<String> lines = new ArrayList<>();
            for (Element person : people) {
                String name = CadXml.text(person, "nome"), phone = CadXml.text(person, "telefone");
                if (name.trim().isEmpty() && phone.trim().isEmpty())
                    continue;
                String identity = CadXml.text(person, "idEnvolvidoChamada");
                if (identity.isEmpty())
                    identity = person.getAttribute("id");
                if (identity.isEmpty())
                    identity = AppFiles.hash(name + ":" + phone + ":" + CadXml.text(person, "solicitante"));
                String key = assignment + ":envolvido:" + identity;
                String fingerprint
                    = AppFiles.hash(name + ":" + phone + ":" + CadXml.text(person, "solicitante"));
                if (fingerprint.equals(participantFingerprints.put(key, fingerprint)))
                    continue;
                String text = ("true".equalsIgnoreCase(CadXml.text(person, "solicitante"))
                               || "1".equals(CadXml.text(person, "solicitante")))
                                  ? "Solicitante"
                                  : "Envolvido";
                text += ": " + safeContact(name.trim().isEmpty() ? "nome não informado" : name);
                if (!phone.trim().isEmpty()) {
                    text += " • Telefone: " + safeContact(phone);
                    String digits = phone.replaceAll("[^0-9]", "");
                    if (digits.length() == 10 || digits.length() == 11)
                        digits = "55" + digits;
                    if (digits.matches("55[1-9][0-9]{9,10}"))
                        text += " • [WhatsApp](https://wa.me/" + digits + ")";
                }
                lines.add(text);
            }
            // Blocos pequenos mantêm todos os envolvidos sem ultrapassar os limites do Discord.
            StringBuilder block = new StringBuilder();
            int part = 0;
            for (String line : lines) {
                if (block.length() > 0 && block.length() + line.length() > 2700) {
                    emitParticipantsBlock(r, number, assignment, block.toString(), part++);
                    block.setLength(0);
                }
                for (int offset = 0; offset < line.length(); offset += 2500) {
                    if (offset > 0 && block.length() > 0) {
                        emitParticipantsBlock(r, number, assignment, block.toString(), part++);
                        block.setLength(0);
                    }
                    block.append(line, offset, Math.min(line.length(), offset + 2500)).append('\n');
                }
            }
            if (block.length() > 0)
                emitParticipantsBlock(r, number, assignment, block.toString(), part);
        }
    }

    private void emitParticipantsBlock(Element r, String number, String assignment, String body, int part) {
        sink.accept(new Event(CadXml.text(r, "idRecurso"), CadXml.text(r, "prefixo"), number,
                              assignment + ":envolvidos:" + AppFiles.hash(body) + ":" + part,
                              AppFiles.hash(body), "CONTATOS • ENVOLVIDOS NO DESPACHO", Java8.stripTrailing(body),
                              "", Instant.now().toString(), CadXml.text(r, "idUnidadeServico"),
                              CadXml.text(r, "idTipoRecurso"), true));
    }

    /** Mantém contatos como texto, sem permitir que nomes/telefones criem links Markdown. */
    private static String safeContact(String text) {
        return text.replace("\\", "\\\\")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("*", "\\*")
            .replace("_", "\\_")
            .replace("`", "\\`")
            .replace('\n', ' ')
            .replace('\r', ' ');
    }

    private String optional(Element c, String field, String label) {
        String value = CadXml.text(c, field);
        return value.isEmpty() ? "" : label + ": " + value + "\n";
    }
    private String time(String value) {
        try {
            long millis = Long.parseLong(value);
            if (millis > 100000000000L)
                return java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss XXX")
                    .withZone(java.time.ZoneId.of("America/Sao_Paulo"))
                    .format(Instant.ofEpochMilli(millis));
        } catch (Exception ignored) {
        }
        return value;
    }
    static boolean coordinates(String lat, String lon) {
        try {
            double a = Double.parseDouble(lat), b = Double.parseDouble(lon);
            return Double.isFinite(a) && Double.isFinite(b) && Math.abs(a) <= 90 && Math.abs(b) <= 180;
        } catch (Exception e) {
            return false;
        }
    }
    static String encode(String s) { return Java8.encode(s); }
    static String label(String s) {
        switch (s) {
        case "NL":
        case "NO_LOCAL":
            return "NO LOCAL";
        case "AC":
        case "A_CAMINHO":
            return "A CAMINHO";
        case "EC":
        case "EM_CONTROLE":
            return "EM CONTROLE";
        case "ED":
        case "EM_DIRECAO":
            return "EM DIREÇÃO";
        case "LI":
        case "NO_LOCAL_INTERMEDIARIO":
            return "NO LOCAL INTERMEDIÁRIO";
        case "ER":
        case "EM_RETORNO":
            return "EM RETORNO";
        case "SU":
        case "SUSPENSO":
            return "SUSPENSO";
        case "DP":
        case "DESPACHADO":
            return "DESPACHADO";
        case "EN":
        case "ENCERRAMENTO":
            return "EM ENCERRAMENTO";
        case "TR":
        case "TERMINADO":
            return "ENCERRADO";
        case "D":
        case "DISPONIVEL":
            return "DISPONÍVEL";
        default:
            return s.isEmpty() ? "não informado" : s.replace('_', ' ');
        }
    }
    /** Registro operacional; contatos consultados incluem somente nome e telefone dos envolvidos. */
    static final class Event {
        final boolean publishable;
        final String resourceId, resource, number, key, fingerprint, title, body, links, observed, unitId,
            typeId;
        final String discordBody, discordLinks;
        Event(String id, String resource, String number, String key, String fingerprint, String title,
              String body, String links, String observed) {
            this(id, resource, number, key, fingerprint, title, body, links, observed, "", "");
        }
        Event(String id, String resource, String number, String key, String fingerprint, String title,
              String body, String links, String observed, String unitId, String typeId) {
            this(id, resource, number, key, fingerprint, title, body, links, observed, unitId, typeId, true);
        }
        Event(String id, String resource, String number, String key, String fingerprint, String title,
              String body, String links, String observed, String unitId, String typeId, boolean publishable) {
            this(id, resource, number, key, fingerprint, title, body, links, observed, unitId, typeId,
                 publishable, body.replaceFirst("^VIATURA: [^\\n]*\\n", ""), links);
        }
        /** Apresentação compacta no Discord, independente do registro completo local. */
        Event(String id, String resource, String number, String key, String fingerprint, String title,
              String body, String links, String observed, String unitId, String typeId, boolean publishable,
              String discordBody, String discordLinks) {
            this.discordBody = discordBody;
            this.discordLinks = discordLinks;
            this.publishable = publishable;
            this.unitId = unitId;
            this.typeId = typeId;
            this.resourceId = id;
            this.resource = resource;
            this.number = number;
            this.key = key;
            this.fingerprint = fingerprint;
            this.title = title;
            this.body = body;
            this.links = links;
            this.observed = observed;
        }
        /** Prefixo identifica o tópico mesmo se o CAD mudar o ID do recurso. */
        String topicKey() {
            return topicKey(resource, resourceId);
        }
        static String normalizedPrefix(String resource) {
            String prefix = resource.trim().toUpperCase(Locale.ROOT);
            java.util.regex.Matcher match = java.util.regex.Pattern
                .compile("^([A-Z]+)[\\s-]*([0-9]+)(?:\\s*\\([^)]*\\))?\\s*$").matcher(prefix);
            if (!match.matches())
                return prefix;
            String digits = match.group(2).replaceFirst("^0+(?!$)", "");
            return match.group(1) + Java8.repeat("0", Math.max(0, 5 - digits.length())) + digits;
        }
        static String topicKey(String resource, String resourceId) {
            String prefix = normalizedPrefix(resource);
            return prefix.isEmpty() ? "resource." + resourceId : "vtr." + AppFiles.hash(prefix);
        }
        private static final String FOOTER = "CAD • Recebido";

        /** Destaques visuais ajudam a distinguir os eventos durante o atendimento. */
        private int stateColor() {
            if (title.contains("HISTÓRICO"))
                return 0x9b59b6;
            if (title.startsWith("CONTATOS"))
                return 0x1abc9c;
            String state = title.replace("ATUALIZAÇÃO • ", "");
            switch (state) {
            case "DESPACHADO":
                return 0xe67e22;
            case "A CAMINHO":
            case "EM DIREÇÃO":
            case "EM RETORNO":
                return 0x3498db;
            case "NO LOCAL":
            case "NO LOCAL INTERMEDIÁRIO":
                return 0x2ecc71;
            case "EM ENCERRAMENTO":
                return 0xf1c40f;
            case "ENCERRADO":
                return 0x7f8c8d;
            default:
                return 0x5865f2;
            }
        }
        /** Cor estável por chamada; o sufixo evita depender do prefixo comum do número. */
        private int color() {
            String digits = number.replaceAll("[^0-9]", "");
            if (digits.isEmpty())
                return 0x7f8c8d;
            String suffix = digits.substring(Math.max(0, digits.length() - 8));
            long hash = Long.parseLong(AppFiles.hash(suffix).substring(0, 8), 16);
            float hue = (hash % 3600) / 3600f;
            return java.awt.Color.HSBtoRGB(hue, 0.65f, 0.85f) & 0xffffff;
        }
        private String icon() {
            switch (stateColor()) {
            case 0x9b59b6:
                return "📝";
            case 0x1abc9c:
                return "☎️";
            case 0xe67e22:
                return "🚨";
            case 0x3498db:
                return "🚒";
            case 0x2ecc71:
                return "📍";
            case 0xf1c40f:
                return "⏳";
            case 0x7f8c8d:
                return "✅";
            default:
                return "ℹ️";
            }
        }
        private String embedTitle() {
            String label
                = title.startsWith("ATUALIZAÇÃO • ") ? title.substring("ATUALIZAÇÃO • ".length()) : title;
            return limit(icon() + " CHAMADA " + number + " • " + label, 256);
        }
        private String embedDescription() {
            return limit(limit(discordBody.replace("HORÁRIO DO ESTADO CAD:", "HORÁRIO CAD:"), 3500)
                             + discordLinks,
                         4096);
        }
        /** Comprimento dos campos que entram no limite agregado de 6.000 caracteres do Discord. */
        int embedTextLength() {
            return embedTitle().length() + embedDescription().length() + FOOTER.length();
        }
        String embedJson() {
            return "{\"title\":" + AppFiles.json(embedTitle()) + ",\"description\":"
                + AppFiles.json(embedDescription()) + ",\"footer\":{\"text\":" + AppFiles.json(FOOTER)
                + "},\"color\":" + color() + ",\"timestamp\":" + AppFiles.json(observed) + "}";
        }
        String json(boolean create) { return jsonBatch(Collections.singletonList(this), create); }
        /** Mantém um bloco por evento na ordem de recebimento, sem substituir pelo estado final. */
        static String jsonBatch(List<Event> events, boolean create) {
            Event first = events.get(0);
            StringBuilder json = new StringBuilder("{");
            if (create)
                json.append("\"thread_name\":")
                    .append(AppFiles.json(first.limit("VTR " + normalizedPrefix(first.resource), 100)))
                    .append(',');
            json.append("\"allowed_mentions\":{\"parse\":[]},\"embeds\":[");
            for (int i = 0; i < events.size(); i++) {
                if (i > 0)
                    json.append(',');
                json.append(events.get(i).embedJson());
            }
            return json.append("]}").toString();
        }
        private String limit(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }
    }
}
