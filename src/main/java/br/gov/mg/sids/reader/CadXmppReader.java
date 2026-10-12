package br.gov.mg.sids.reader;

import java.io.BufferedWriter;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Cliente de leitura do CAD: autentica em XMPP, seleciona filtros e imprime estados.
 * <p>O programa mantém uma conexão até o servidor fechar ou ocorrer um erro. Não
 * altera atendimentos, não marca ciência e não envia notificações externas.
 * A configuração fica em {@link ReaderOptions}; o XML é tratado por {@link CadXml}.
 */
public final class CadXmppReader implements AutoCloseable {
    private static final String STREAM = "http://etherx.jabber.org/streams";
    private static final String TLS = "urn:ietf:params:xml:ns:xmpp-tls";
    private static final String SASL = "urn:ietf:params:xml:ns:xmpp-sasl";
    private static final String BIND = "urn:ietf:params:xml:ns:xmpp-bind";
    private static final String COMPRESS = "http://jabber.org/protocol/compress";
    private static final String COMPRESS_FEATURE = "http://jabber.org/features/compress";
    private static final String CLIENT = "jabber:client";
    private final ReaderOptions options;
    private final CadListener listener;
    private volatile boolean prerequisitesReady, closed;
    private String chamadaFilterId, recursoFilterId;
    private final Map<String, PendingRequest> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private Socket socket;
    private InputStream input;
    private Writer writer;
    private boolean tlsRequested, tlsEnabled, compressionRequested, compressionEnabled;
    private boolean authenticated, bound;
    private String bindId, boundJid;
    private int requestId;
    private final java.util.Queue<ReadQuery> queryQueue = new java.util.ArrayDeque<>();
    private final Map<String, ReadQuery> readQueries = new java.util.HashMap<>();
    private ReadQuery activeQuery;

    private final Map<String, java.util.concurrent.CompletableFuture<Element>> summaryRequests = new ConcurrentHashMap<>();

    /** Consulta pontual de outro filtro, sem envAlterarFiltroChamada e sem alimentar o monitor. */
    public synchronized java.util.concurrent.CompletableFuture<Element> requestFilterSummary(String filterId) {
        java.util.concurrent.CompletableFuture<Element> result = new java.util.concurrent.CompletableFuture<>();
        if (closed || !prerequisitesReady || !filterId.matches("[1-9][0-9]*")) {
            result.completeExceptionally(new IOException("Sessão CAD indisponível ou filtro inválido"));
            return result;
        }
        if (!summaryRequests.isEmpty()) {
            result.completeExceptionally(new IOException("Já existe uma consulta de relatório em andamento; tente novamente"));
            return result;
        }
        String id = Integer.toString(requestId + 1);
        summaryRequests.put(id, result);
        try {
            sendCad("envRecuperarResumosChamadasAtivas", CadXml.field("idFiltroChamada", filterId));
        } catch (IOException e) {
            summaryRequests.remove(id);
            result.completeExceptionally(e);
        }
        return result;
    }

    private long brokerTimestamp, brokerReferenceNanos;
    private volatile String timeoutError;
    private long lastPingNanos = System.nanoTime();

    /** Enfileira uma consulta de leitura; consultas repetidas são agrupadas por chamada. */
    public synchronized void requestHistory(String callId) throws IOException {
        if (closed || !prerequisitesReady || !callId.matches("[1-9][0-9]*"))
            return;
        ReadQuery existing = readQueries.get(callId);
        if (existing != null) {
            existing.dirty = true;
            return;
        }
        ReadQuery query = new ReadQuery(callId);
        readQueries.put(callId, query);
        queryQueue.add(query);
        pumpQueries();
    }

    /** Consulta envolvidos apenas sob demanda, na mesma fila serial do histórico. */
    public synchronized void requestParticipants(String callId) throws IOException {
        if (closed || !prerequisitesReady || !callId.matches("[1-9][0-9]*"))
            return;
        String key = "envolvidos:" + callId;
        if (readQueries.containsKey(key))
            return;
        ReadQuery query = new ReadQuery(callId, true);
        readQueries.put(key, query);
        queryQueue.add(query);
        pumpQueries();
    }

    private synchronized void pumpQueries() throws IOException {
        if (activeQuery != null || closed)
            return;
        activeQuery = queryQueue.poll();
        if (activeQuery != null)
            sendQueryPage(activeQuery);
    }

    private void sendQueryPage(ReadQuery query) throws IOException {
        if (query.participants) {
            sendCad("envRecuperarEnvolvidosChamada", CadXml.field("idChamada", query.callId),
                    " currentPage='" + query.page + "' pageSize='50'", query);
            return;
        }
        String content = CadXml.field("idChamada", query.callId)
                         + ("<orgaosAtendimentoEmergencia><i>BM</i><i>PC</i><i>PM</i><i>PP</i><i>SS</"
                            + "i><i>SJ</i><i>SM</i></orgaosAtendimentoEmergencia>")
                         + ("<tiposHistoricosChamada><i>HISTORICO_TELEATENDIMENTO</"
                            + "i><i>HISTORICO_DESPACHO_E_OUTROS</i></tiposHistoricosChamada>");
        sendCad("envRecuperarResumosHistoricosChamada", content,
                " currentPage='" + query.page + "' pageSize='50'", query);
    }

    /** Falhas de uma consulta complementar não interrompem o monitoramento. */
    private synchronized void finishQuery(ReadQuery query, boolean succeeded) throws IOException {
        readQueries.remove(query.participants ? "envolvidos:" + query.callId : query.callId);
        activeQuery = null;
        if (!query.participants)
            listener.onHistoryFinished(query.callId, succeeded);
        if (query.dirty && succeeded) {
            ReadQuery refresh = new ReadQuery(query.callId);
            readQueries.put(refresh.callId, refresh);
            queryQueue.add(refresh);
        }
        pumpQueries();
    }

    private synchronized void receiveQuery(Element message, ReadQuery query) throws Exception {
        if (!"0".equals(message.getAttribute("status"))) {
            log(query.participants ? "ENVOLVIDOS" : "HISTORICO",
                "Consulta recusada para chamada " + query.callId
                    + "; status=" + message.getAttribute("status") + ". Monitoramento mantido.");
            finishQuery(query, false);
            return;
        }
        message.setAttribute("readerCallId", query.callId);
        listener.onMessage(message);
        String total = CadXml.nestedText(message, "paginationBean", "totalSize");
        String size = CadXml.nestedText(message, "paginationBean", "pageSize");
        int count = CadXml
                        .collectionItems(
                            message, query.participants ? "envolvidosChamada" : "resumosHistoricosChamada",
                            query.participants ? "envolvidoChamada" : "resumoHistoricoChamada")
                        .size();
        if (total.isEmpty() || size.isEmpty()) {
            log(query.participants ? "ENVOLVIDOS" : "HISTORICO",
                "Resposta sem paginação para chamada " + query.callId
                    + "; apenas a página recebida foi processada.");
            finishQuery(query, true);
            return;
        }
        int totalSize, pageSize;
        try {
            totalSize = Integer.parseInt(total);
            pageSize = Integer.parseInt(size);
            if (pageSize <= 0 || totalSize < 0)
                throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            log(query.participants ? "ENVOLVIDOS" : "HISTORICO",
                "Paginação inválida para chamada " + query.callId
                    + "; apenas a página recebida foi processada. Monitoramento mantido.");
            finishQuery(query, false);
            return;
        }
        if ((long)query.page * pageSize < totalSize) {
            if (count == 0) {
                log(query.participants ? "ENVOLVIDOS" : "HISTORICO",
                    "Página vazia antes do fim da consulta da chamada " + query.callId
                        + ". Consulta incompleta; monitoramento mantido.");
                finishQuery(query, false);
            } else {
                query.page++;
                sendQueryPage(query);
            }
        } else
            finishQuery(query, true);
    }

    /** Estado de uma consulta paginada de leitura: histórico ou envolvidos. */
    private static final class ReadQuery {
        final String callId;
        final boolean participants;
        int page = 1;
        boolean dirty;
        ReadQuery(String callId) { this(callId, false); }
        ReadQuery(String callId, boolean participants) {
            this.callId = callId;
            this.participants = participants;
        }
    }

    /** Guarda opções já validadas; a conexão só é aberta em {@link #start()}. */
    public CadXmppReader(ReaderOptions options) {
        this(options, new CadListener() {
            @Override
            public void onLog(String category, String text) {
                System.out.println("[" + category + "] " + text);
            }
        });
    }

    /** Permite usar a mesma sessão na interface gráfica e na linha de comando. */
    public CadXmppReader(ReaderOptions options, CadListener listener) {
        this.options = options;
        this.listener = listener;
        this.chamadaFilterId = options.chamadaFilterId;
        this.recursoFilterId = options.recursoFilterId;
    }

    /** Seleciona filtros na sessão já validada; chamada pela interface fora da EDT. */
    public synchronized void applyFilters(String chamadaId, String recursoId) throws IOException {
        if (!prerequisitesReady || closed)
            throw new IOException("Sessão ainda não está pronta");
        if (chamadaId == null && recursoId == null)
            throw new IOException("Selecione ao menos um filtro");
        if (chamadaId != null && !chamadaId.matches("[1-9][0-9]*"))
            throw new IOException("Filtro de chamadas inválido");
        if (recursoId != null && !recursoId.matches("[1-9][0-9]*"))
            throw new IOException("Filtro de recursos inválido");
        chamadaFilterId = chamadaId;
        recursoFilterId = recursoId;
        selectFilters();
    }

    /** Abre o TCP e executa a leitura bloqueante; sempre libera conexão e tarefas. */
    public void start() throws Exception {
        try {
            log("CONNECT", options.host + ":" + options.port);
            socket = new Socket();
            if (closed)
                throw new IOException("Conexão cancelada");
            socket.connect(new InetSocketAddress(options.host, options.port), 15000);
            socket.setSoTimeout(120000);
            installStreams(socket.getInputStream(), socket.getOutputStream());
            openStream();
            readLoop();
        } catch (Exception e) {
            if (timeoutError != null)
                throw new IOException(timeoutError, e);
            throw e;
        } finally {
            close();
        }
    }

    /**
     * Lê elementos completos, inclusive quando TCP fragmenta bytes UTF-8.
     * Reinicia o parser depois de TLS, SASL e zlib, que abrem novos streams XML.
     * Não permite leitura antecipada do parser através dessas fronteiras.
     */
    private void readLoop() throws Exception {
        boolean restart;
        do {
            restart = false;
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
            InputStream exact = new FilterInputStream(input) {
                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    return super.read(b, off, bound ? len : Math.min(len, 1));
                }
                @Override public void close() {
    summaryRequests.values().forEach(f -> f.completeExceptionally(new IOException("Sessão CAD encerrada")));
    summaryRequests.clear(); /* O socket pertence ao reader. */ }
            };
            XMLStreamReader parser = factory.createXMLStreamReader(exact, "UTF-8");
            try {
                while (parser.hasNext()) {
                    int event = parser.next();
                    if (event == XMLStreamConstants.DTD)
                        throw new IOException("DTD não permitido");
                    if (event != XMLStreamConstants.START_ELEMENT)
                        continue;
                    if (STREAM.equals(parser.getNamespaceURI()) && "stream".equals(parser.getLocalName()))
                        continue;
                    Element stanza = CadXml.readElement(parser);
                    if (processStanza(stanza)) {
                        restart = true;
                        break;
                    }
                }
            } finally {
                parser.close();
            }
        } while (restart);
        if (!pending.isEmpty())
            throw new IOException("Servidor encerrou a conexão com pedidos pendentes");
        log("CLOSED", "Servidor encerrou o stream");
    }

    /** Despacha elementos XMPP. Retorna true quando um novo parser é necessário. */
    private boolean processStanza(Element stanza) throws Exception {
        String tag = stanza.getLocalName(), ns = stanza.getNamespaceURI();
        if (("failure".equals(tag) && (TLS.equals(ns) || SASL.equals(ns) || COMPRESS.equals(ns)))
            || ("error".equals(tag) && STREAM.equals(ns))) {
            throw new IOException("Servidor recusou negociação: " + CadXml.serialize(stanza));
        }
        if (STREAM.equals(ns) && "features".equals(tag)) {
            negotiateFeatures(stanza);
        } else if (TLS.equals(ns) && "proceed".equals(tag) && tlsRequested) {
            startTls();
            return true;
        } else if (SASL.equals(ns) && "success".equals(tag)) {
            authenticated = true;
            log("AUTH", "Autenticação confirmada");
            openStream();
            return true;
        } else if (COMPRESS.equals(ns) && "compressed".equals(tag) && compressionRequested) {
            enableCompression();
            return true;
        } else if (CLIENT.equals(ns) && "iq".equals(tag)) {
            processIq(stanza);
        } else if (CLIENT.equals(ns) && "message".equals(tag)) {
            if ("error".equals(stanza.getAttribute("type")))
                throw new IOException("Mensagem XMPP recusada: " + CadXml.serialize(stanza));
            // O CAD é uma extensão de <message>, não texto de <body>.
            for (Node child = stanza.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element && CadXml.CAD.equals(child.getNamespaceURI()))
                    processCad((Element)child);
            }
        } else if (options.debug) {
            log("XMPP", CadXml.serialize(stanza));
        }

        return false;
    }

    /** Escolhe apenas entre features efetivamente oferecidas pelo broker. */
    private void negotiateFeatures(Element features) throws Exception {
        if (!tlsEnabled && CadXml.has(features, TLS, "starttls")) {
            tlsRequested = true;
            sendXml("<starttls xmlns='" + TLS + "'/>");
        } else if (!authenticated && CadXml.has(features, SASL, "mechanisms")) {
            if (!CadXml.values(features, SASL, "mechanism").contains("PLAIN"))
                throw new IOException("Servidor não oferece SASL PLAIN");
            String payload = "\u0000" + options.username + "\u0000" + options.password;
            sendXml("<auth xmlns='" + SASL + "' mechanism='PLAIN'>"
                    + Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                    + "</auth>");
            log("AUTH", "Autenticação enviada; usuário=" + options.username + " (senha omitida)");
        } else if (!compressionEnabled && CadXml.has(features, COMPRESS_FEATURE, "compression")) {
            if (!CadXml.values(features, COMPRESS_FEATURE, "method").contains("zlib"))
                throw new IOException("Servidor não oferece compressão zlib");
            compressionRequested = true;
            sendXml("<compress xmlns='" + COMPRESS + "'><method>zlib</method></compress>");
        } else if (authenticated && !bound && CadXml.has(features, BIND, "bind")) {
            bindId = "bind-" + System.nanoTime();
            sendXml("<iq type='set' id='" + bindId + "'><bind xmlns='" + BIND + "'>"
                    + CadXml.field("resource", options.resource) + "</bind></iq>");
        } else {
            throw new IOException("Features incompatíveis com a sessão: " + CadXml.serialize(features));
        }
    }

    /** Confirma bind pelo ID do pedido e responde aos pings do broker. */
    private void processIq(Element iq) throws Exception {
        String id = iq.getAttribute("id"), type = iq.getAttribute("type");
        if (!bound && id.equals(bindId)) {
            if (!"result".equals(type) || !CadXml.has(iq, BIND, "jid"))
                throw new IOException("Bind recusado: " + CadXml.serialize(iq));
            boundJid = CadXml.first(iq, BIND, "jid").getTextContent().trim();
            if (boundJid.isEmpty())
                throw new IOException("Bind retornou JID vazio");
            bound = true;
            log("BIND", "Confirmado; remetente da sessão: " + boundJid);
            scheduler.scheduleAtFixedRate(this::maintainSession, 1, 1, TimeUnit.SECONDS);
            sendCad("envIniciarSessao", "");
        } else if ("get".equals(type) && CadXml.has(iq, "urn:xmpp:ping", "ping")) {
            sendXml("<iq type='result' id='" + CadXml.escape(id) + "' from='" + CadXml.escape(boundJid) + "'"
                    + (iq.hasAttribute("from") ? " to='" + CadXml.escape(iq.getAttribute("from")) + "'" : "")
                    + "/>");
        } else if (options.debug) {
            log("IQ", CadXml.serialize(iq));
        }
    }

    /**
     * Processa respostas CAD correlacionadas por ID e eventos espontâneos.
     * A sessão fornece o relógio do broker, usado nos pedidos posteriores.
     * Valida os pré-requisitos antes de consultar filtros ou estados, como no cliente original.
     */
    private void processCad(Element message) throws Exception {
        String tag = message.getLocalName();
        String xml = options.debug ? CadXml.serialize(message) : null;
        if (options.debug)
            log("CAD-XML", xml);
        if (tag.startsWith("rtn")) {
            PendingRequest request = pending.get(message.getAttribute("id"));
            if (request == null) {
                log("UNEXPECTED", tag + " com ID não solicitado: " + message.getAttribute("id"));
                return;
            }
            if (!request.responseTag.equals(tag))
                throw new IOException("Resposta inesperada para " + request.tag + ": " + tag);
            if (!pending.remove(message.getAttribute("id"), request))
                return;
            java.util.concurrent.CompletableFuture<Element> summary = summaryRequests.remove(message.getAttribute("id"));
            if (summary != null) {
                if ("0".equals(message.getAttribute("status"))) summary.complete(message);
                else summary.completeExceptionally(new IOException("Consulta CAD recusada; status=" + message.getAttribute("status")));
                return;
            }
            if (request.query != null) {
                receiveQuery(message, request.query);
                return;
            }
            if (!"0".equals(message.getAttribute("status")))
                throw new IOException(tag + " recusado; status=" + message.getAttribute("status")
                                      + ("6".equals(message.getAttribute("status"))
                                             ? " (FALHA_VERIFICACAO_PRE_REQUISITOS)"
                                             : "")
                                      + "; " + (xml != null ? xml : CadXml.serialize(message)));
            log("RESPONSE", tag + " id=" + message.getAttribute("id") + " sucesso");
        }
        listener.onMessage(message);
        switch (tag) {
        case "rtnIniciarSessao":
            String timestamp = CadXml.text(message, "brokerTimestamp");
            if (!timestamp.isEmpty()) {
                brokerTimestamp = Long.parseLong(timestamp);
                brokerReferenceNanos = System.nanoTime();
            }
            log("SESSION",
                "Sessão CAD iniciada; unidade=" + CadXml.nestedText(message, "unidadeServico", "descricao"));
            sendCad("envRecuperarCacheAntesValidarPreRequisitos", "");
            break;
        case "rtnRecuperarCacheAntesValidarPreRequisitos":
            validatePrerequisites();
            break;
        case "rtnValidarPreRequisitos":
            prerequisitesReady = true;
            log("PREREQUISITES", "Validação aceita pelo servidor");
            if (options.hasFilters())
                selectFilters();
            else
                sendCad("envRecuperarCacheAposValidarPreRequisitos", "");
            break;
        case "rtnRecuperarCacheAposValidarPreRequisitos":
            listener.onFilters(message);
            printFilters(message, "filtroChamada", "--chamada-filter-id");
            printFilters(message, "filtroRecurso", "--recurso-filter-id");
            log("ACTION", "Filtros recebidos. Na interface, selecione e inicie o monitoramento; no terminal, "
                              + "execute novamente com os IDs desejados. ID da unidade não é ID do filtro.");
            break;
        case "rtnAlterarFiltroChamada":
            sendCad("envRecuperarResumosChamadasAtivas", CadXml.field("idFiltroChamada", chamadaFilterId));
            break;
        case "rtnAlterarFiltroRecurso":
            sendCad("envRecuperarResumosRecursosParaEmpenho",
                    CadXml.field("idFiltroRecurso", recursoFilterId));
            break;
        case "rtnRecuperarResumosChamadasAtivas":
            printSummaries(message, false, "CARGA");
            break;
        case "rtnRecuperarResumosRecursosParaEmpenho":
            printSummaries(message, true, "CARGA");
            break;
        case "envInformarAlteracaoChamada":
            printSummaries(message, false, "ATUALIZACAO");
            break;
        case "envInformarAlteracaoRecurso":
            printSummaries(message, true, "ATUALIZACAO");
            break;
        default:
            log("EVENT", tag + " recebido; consulte --debug para detalhes");
        }
    }

    /**
     * Envia os três campos usados pela aplicação original. Os padrões foram
     * verificados no build e no ClienteCADApplication.properties do cad.jar local.
     * Uma recusa preserva o motivo e os parâmetros recebidos no diagnóstico XML.
     */
    private void validatePrerequisites() throws IOException {
        String content = CadXml.field("brokerMinimumRevision", options.brokerMinimumRevision)
                         + CadXml.field("revision", options.clientRevision)
                         + CadXml.field("version", options.clientVersion);
        log("PREREQUISITES", "Validando versão=" + options.clientVersion
                                 + " revisão=" + options.clientRevision
                                 + " revisão mínima do broker=" + options.brokerMinimumRevision);
        sendCad("envValidarPreRequisitos", content);
    }

    /** Assina os filtros no broker; cada sucesso dispara o respectivo snapshot. */
    private void selectFilters() throws IOException {
        if (chamadaFilterId != null)
            sendCad("envAlterarFiltroChamada", CadXml.field("idFiltro", chamadaFilterId));
        if (recursoFilterId != null)
            sendCad("envAlterarFiltroRecurso", CadXml.field("idFiltro", recursoFilterId));
    }

    /** Mostra IDs e descrições de filtros, sem despejar cadastros pessoais do cache. */
    private void printFilters(Element cache, String tag, String option) {
        List<Element> filters = CadXml.collectionItems(
            cache, "filtroChamada".equals(tag) ? "filtrosChamada" : "filtrosRecurso", tag);
        for (Element filter : filters)
            log("FILTRO",
                option + " " + filter.getAttribute("id") + " | " + CadXml.text(filter, "descricao"));
        log("FILTROS", tag + ": " + filters.size());
    }

    /**
     * Imprime a situação enviada pelo CAD. Recurso não implica necessariamente viatura:
     * o tipo, o meio material e a placa permitem identificar sua natureza.
     * Não interpreta ausência de um recurso numa atualização como remoção.
     */
    private void printSummaries(Element message, boolean resources, String origin) {
        String entityTag = resources ? "resumoRecurso" : "resumoChamada";
        List<Element> summaries
            = "ATUALIZACAO".equals(origin)
                  ? CadXml.elements(message, entityTag)
                  : CadXml.collectionItems(message, resources ? "resumosRecursos" : "resumosChamadas",
                                           entityTag);
        if (resources) {
            int available = 0;
            for (Element item : summaries) {
                String status = CadXml.text(item, "situacaoRecurso");
                if ("DISPONIVEL".equals(status) || "D".equals(status))
                    available++;
                log("RECURSO", origin + " id=" + CadXml.text(item, "idRecurso") + " prefixo="
                                   + CadXml.text(item, "prefixo") + " situação=" + status + " tipo="
                                   + CadXml.text(item, "tipoRecurso") + " placa=" + CadXml.text(item, "placa")
                                   + " unidade=" + CadXml.text(item, "descricaoUnidadeServico"));
            }
            log("RECURSOS",
                origin + " recebidos=" + summaries.size() + " disponíveis neste lote=" + available);
        } else {
            for (Element item : summaries)
                log("OCORRENCIA", origin + " id=" + CadXml.text(item, "idChamada")
                                      + " número=" + CadXml.text(item, "numeroChamada")
                                      + " situação=" + CadXml.text(item, "situacaoChamada")
                                      + " local=" + CadXml.text(item, "descricaoLocalizacao"));
            log("OCORRENCIAS", origin + " recebidas=" + summaries.size());
        }
        if (summaries.isEmpty() && "ATUALIZACAO".equals(origin))
            log("EVENT", "Evento sem resumo completo; consulte --debug. Não é uma carga vazia.");
    }

    /**
     * Encapsula a extensão CAD em {@code <message>}, usa o JID do bind e registra o prazo.
     * dataHoraEnvio acompanha o relógio do broker, como em AbstractComunicador.
     */
    private synchronized void sendCad(String tag, String content) throws IOException {
        sendCad(tag, content, "", null);
    }

    private synchronized void sendCad(String tag, String content, String attributes, ReadQuery query)
        throws IOException {
        String id = Integer.toString(++requestId);
        String timestamp
            = brokerTimestamp == 0
                  ? ""
                  : " dataHoraEnvio='"
                        + (brokerTimestamp
                           + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - brokerReferenceNanos))
                        + "'";
        PendingRequest request = new PendingRequest(tag, options.requestTimeoutSeconds, query);
        pending.put(id, request);
        try {
            sendXml("<message xmlns='" + CLIENT + "' from='" + CadXml.escape(boundJid) + "'><" + tag
                    + " xmlns='" + CadXml.CAD + "' id='" + id + "'" + timestamp + attributes + ">"
                    + CadXml.field("ipLocal", socket.getLocalAddress().getHostAddress()) + content + "</"
                    + tag + "></message>");
        } catch (IOException e) {
            pending.remove(id);
            throw e;
        }
        log("REQUEST", tag + " id=" + id);
    }

    /** Verifica prazos independentemente dos pings; fecha conexão ao detectar falha. */
    private void maintainSession() {
        try {
            long now = System.nanoTime();
            for (Map.Entry<String, PendingRequest> entry : pending.entrySet()) {
                if (now >= entry.getValue().deadlineNanos) {
                    java.util.concurrent.CompletableFuture<Element> summary = summaryRequests.remove(entry.getKey());
                    if (summary != null) {
                        pending.remove(entry.getKey());
                        summary.completeExceptionally(new IOException("Consulta CAD sem resposta no prazo"));
                        continue;
                    }
                    if (entry.getValue().query != null) {
                        if (pending.remove(entry.getKey(), entry.getValue())) {
                            log(entry.getValue().query.participants ? "ENVOLVIDOS" : "HISTORICO",
                                "Consulta sem resposta para chamada " + entry.getValue().query.callId
                                    + ". Monitoramento mantido.");
                            finishQuery(entry.getValue().query, false);
                        }
                        continue;
                    }
                    timeoutError = "Sem resposta CAD para " + entry.getValue().tag + " id=" + entry.getKey()
                                   + " após " + options.requestTimeoutSeconds
                                   + "s. Pings não confirmam processamento CAD. "
                                   + ("Confira endereço/porta do broker de leitura e permissões; use "
                                      + "--debug.");
                    log("TIMEOUT", timeoutError);
                    socket.close();
                    return;
                }
            }
            // O scheduler roda a cada segundo, mas o ping é enviado apenas a cada 30s.
            if (now - lastPingNanos >= TimeUnit.SECONDS.toNanos(30)) {
                sendXml("<iq type='get' id='ping-" + now + "' from='" + CadXml.escape(boundJid)
                        + "'><ping xmlns='urn:xmpp:ping'/></iq>");
                lastPingNanos = now;
            }
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Usa o remetente técnico ie@domínio, como NettyXMPPConstants no CAD original. */
    private void openStream() throws IOException {
        sendXml("<?xml version='1.0' encoding='UTF-8'?><stream:stream xmlns='" + CLIENT + "' xmlns:stream='"
                + STREAM + "' to='" + CadXml.escape(options.domain) + "' from='"
                + CadXml.escape("ie@" + options.domain) + "' version='1.0'>");
    }

    /** Serializa saídas do parser e do keepalive para que bytes não se misturem. */
    private synchronized void sendXml(String xml) throws IOException {
        writer.write(xml);
        writer.flush();
    }

    /** Instala streams UTF-8 sem fechar os streams da fase anterior. */
    private void installStreams(InputStream in, OutputStream out) {
        input = new CadInputBuffer(in);
        writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
    }

    /** Faz STARTTLS no socket atual; --insecure existe somente para diagnóstico. */
    private void startTls() throws Exception {
        SSLSocketFactory factory = (SSLSocketFactory)SSLSocketFactory.getDefault();
        if (options.insecure) {
            log("TLS", "Diagnóstico: validação de certificado desativada");
            TrustManager[] trustAll = {new X509TrustManager(){
                public void checkClientTrusted(X509Certificate[] chain, String authType){} public void
                    checkServerTrusted(X509Certificate[] chain,
                                       String authType){} public X509Certificate[] getAcceptedIssuers(){
                        return new X509Certificate[0];
        }
    }
};
SSLContext context = SSLContext.getInstance("TLS");
context.init(null, trustAll, new SecureRandom());
factory = context.getSocketFactory();
}
// O domínio identifica o certificado, mesmo quando --host é um endereço IP.
SSLSocket secure = (SSLSocket)factory.createSocket(socket, options.domain, options.port, true);
secure.setUseClientMode(true);
secure.setSoTimeout(120000);
SSLParameters parameters = secure.getSSLParameters();
parameters.setEndpointIdentificationAlgorithm(options.insecure ? null : "HTTPS");
secure.setSSLParameters(parameters);
secure.startHandshake();
socket = secure;
installStreams(socket.getInputStream(), socket.getOutputStream());
tlsEnabled = true;
log("TLS", "Estabelecido");
openStream();
}

/** Usa wrapper zlib e SYNC_FLUSH para não reter pedidos dentro do compressor. */
private void enableCompression() throws IOException {
    installStreams(new InflaterInputStream(input),
                   new DeflaterOutputStream(socket.getOutputStream(),
                                            new Deflater(Deflater.DEFAULT_COMPRESSION, false), 8192, true));
    compressionEnabled = true;
    log("ZLIB", "Compressão estabelecida");
    openStream();
}

/** Encerra o socket e o scheduler, inclusive quando há erro de autenticação. */
@Override
public void close() {
    summaryRequests.values().forEach(f -> f.completeExceptionally(new IOException("Sessão CAD encerrada")));
    summaryRequests.clear();
    closed = true;
    scheduler.shutdownNow();
    try {
        if (socket != null)
            socket.close();
    } catch (IOException ignored) {
    }
}

/** Padroniza mensagens operacionais; XML completo só aparece com --debug. */
private void log(String category, String text) { listener.onLog(category, text); }

/** Abre a interface sem argumentos ou com --gui; demais opções executam o modo terminal. */
public static void main(String[] args) {
    try {
        if (args.length == 0 || java.util.Arrays.asList(args).contains("--gui")) {
            javax.swing.SwingUtilities.invokeLater(() -> new CadReaderWindow().setVisible(true));
            return;
        }
        ReaderOptions options = ReaderOptions.parse(args);
        if (options == null)
            return;
        new CadXmppReader(options).start();
    } catch (Exception e) {
        System.err.println("[ERROR] " + e.getMessage());
        System.exit(1);
    }
}

/** Pedido aguardando resposta, identificado pelo atributo CAD id. */
private static final class PendingRequest {
    final String tag, responseTag;
    final long deadlineNanos;
    final ReadQuery query;
    PendingRequest(String tag, int seconds, ReadQuery query) {
        this.query = query;
        this.tag = tag;
        this.responseTag = "rtn" + tag.substring(3);
        this.deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    }
}
}
