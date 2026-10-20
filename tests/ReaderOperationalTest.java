package br.gov.mg.sids.reader;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.w3c.dom.Element;

/** Estado Web, escopo, perfis, roteamento opcional e API local; nenhum serviço externo. */
public final class ReaderOperationalTest {
    static void check(boolean c, String m) { ReaderIntegrationTest.check(c, m); }
    static Element xml(String value) throws Exception { return ReaderBotTest.xml(value); }
    static String call(String state, String lat) {
        return "<i><idChamada>1</idChamada><numeroChamada>20260001</numeroChamada><situacaoChamada>" + state
            + "</situacaoChamada><latitude>" + lat + "</latitude><longitude>-44.1</longitude><descricaoLocalizacao>Rua de teste</descricaoLocalizacao></i>";
    }
    static CadOperationalState state() throws Exception {
        CadOperationalState state = new CadOperationalState();
        state.start(new CadResourceFilter("f", Java8.set("7"), Java8.set()));
        state.accept(xml("<rtnRecuperarResumosChamadasAtivas><resumosChamadas>" + call("A", "-19.9") + "</resumosChamadas></rtnRecuperarResumosChamadasAtivas>"));
        state.accept(xml("<rtnRecuperarResumosRecursosParaEmpenho><resumosRecursos>"
            + "<i><idRecurso>2</idRecurso><idUnidadeServico>7</idUnidadeServico><idTipoRecurso>3</idTipoRecurso><situacaoRecurso>D</situacaoRecurso><prefixo>ABTS teste</prefixo></i>"
            + "<i><idRecurso>9</idRecurso><idUnidadeServico>999</idUnidadeServico><idTipoRecurso>3</idTipoRecurso></i>"
            + "</resumosRecursos></rtnRecuperarResumosRecursosParaEmpenho>"));
        return state;
    }
    public static void main(String[] args) throws Exception {
        assignments();
        matrixAndActivity();
        projectionCache();
        observation();
        areas();
        CadOperationalState state = state();
        check(Boolean.TRUE.equals(state.snapshot().get("ready")), "Duas cargas iniciais habilitam o estado Web");
        check(BotJson.array(state.snapshot().get("resources")).size() == 1, "Recurso fora do filtro não vaza para a Web");
        state.accept(xml("<envInformarAlteracaoChamada><resumoChamada><idChamada>1</idChamada><situacaoChamada>A</situacaoChamada><latitude>NaN</latitude><longitude>-44</longitude></resumoChamada></envInformarAlteracaoChamada>"));
        check(BotJson.object(BotJson.array(state.snapshot().get("calls")).get(0)).get("lat") == null, "Coordenada inválida permanece sem ponto, sem inventar localização");
        state.accept(xml("<envInformarAlteracaoChamada><resumoChamada><idChamada>1</idChamada><situacaoChamada>CLASSIFICADA</situacaoChamada></resumoChamada></envInformarAlteracaoChamada>"));
        check(BotJson.array(state.snapshot().get("calls")).isEmpty(), "Classificação remove a ocorrência ativa");
        state = state(); state.disconnected();
        check(!Boolean.TRUE.equals(state.snapshot().get("ready")) && BotJson.array(state.snapshot().get("calls")).size() == 1, "Desconexão marca dados antigos sem perdê-los");
        try { state.call("1"); throw new AssertionError("Consulta deveria recusar estado antigo"); } catch (IllegalArgumentException expected) {}
        state.reset(); check(BotJson.array(state.snapshot().get("calls")).isEmpty(), "Nova sessão limpa o estado anterior");
        Path root = Files.createTempDirectory("cad-map-test-");
        Map<String, Object> config = OperationalSettings.defaults();
        Map<String, Object> base = Java8.map("id", "b", "unitId", "7", "name", "Base teste", "lat", -19.95, "lon", -44.15);
        config.put("bases", Java8.list(base));
        config.put("server", "http://127.0.0.1:9999/custom/");
        Map<String, Object> custom = Java8.map("id", "custom", "name", "ABTS", "costing", "truck", "height", 3.2, "weight", 12);
        config.put("profiles", Java8.list(BotJson.array(config.get("profiles")).get(0), custom));
        OperationalSettings store = new OperationalSettings(root); store.save(config);
        check(new OperationalSettings(root).get().equals(store.get()), "Cadastro e URL própria persistem após reabertura");
        check(BotJson.text(store.get(), "server").endsWith("/custom"), "URL com prefixo é aceita e normalizada");
        Map<String, Object> invalid = OperationalSettings.defaults(); invalid.put("server", "file:///tmp/test");
        try { store.save(invalid); throw new AssertionError("URL inválida aceita"); } catch (IllegalArgumentException expected) {}
        check(store.get().equals(new OperationalSettings(root).get()), "Falha de validação preserva cadastro anterior");
        Map<String, Object> pending = new LinkedHashMap<>(store.get());
        Map<String, Object> pendingBase = Java8.map("id", "pending", "name", "Base por endereço", "unitId", "",
            "lat", null, "lon", null, "address", "Rua de teste, 10, Contagem - MG",
            "sourceUrl", "https://www.mg.gov.br/unidades", "checkedAt", "2026-10-18", "notes", "Coordenadas pendentes");
        pending.put("bases", Java8.list(pendingBase)); store.save(pending);
        Map<String, Object> persisted = BotJson.object(BotJson.array(new OperationalSettings(root).get().get("bases")).get(0));
        check(persisted.get("lat") == null && BotJson.text(persisted, "address").contains("Contagem")
            && BotJson.text(persisted, "sourceUrl").startsWith("https://"), "Base pendente e fonte persistem sem inventar coordenadas/ID CAD");
        Map<String, Object> badBase = new LinkedHashMap<>(pendingBase); badBase.put("lat", -19.9);
        pending.put("bases", Java8.list(badBase));
        try { store.save(pending); throw new AssertionError("Coordenadas parciais aceitas"); } catch (IllegalArgumentException expected) {}
        badBase = new LinkedHashMap<>(pendingBase); badBase.put("sourceUrl", "javascript:alert(1)");
        pending.put("bases", Java8.list(badBase));
        try { store.save(pending); throw new AssertionError("Fonte insegura aceita"); } catch (IllegalArgumentException expected) {}
        store.save(config);
        AtomicInteger requests = new AtomicInteger();
        List<Map<String, Object>> queries = new ArrayList<>();
        ValhallaRouting router = new ValhallaRouting((server, path, body) -> {
            requests.incrementAndGet();
            if (path.equals("/status")) return Java8.map("version", "test");
            queries.add(body);
            return Java8.map("trip", Java8.map("summary", Java8.map("time", 600, "length", 7.5), "legs", Java8.list(Java8.map("shape", Java8.map("type", "LineString", "coordinates", Java8.list(Java8.list(-44.15, -19.95), Java8.list(-44.1, -19.9)))))));
        });
        check(requests.get() == 0 && Boolean.FALSE.equals(router.status().get("connected")), "Valhalla não faz conexão automática");
        String server = BotJson.text(store.get(), "server");
        router.connect(server);
        Map<String, Object> call = state().call("1");
        Map<String, Object> route = router.calculate(server, call, base, custom);
        router.calculate(server, call, base, custom);
        try { router.calculate(server, call, pendingBase, custom); throw new AssertionError("Rota sem origem aceita"); }
        catch (IllegalArgumentException expected) {}

        check(requests.get() == 2 && ((Number)route.get("seconds")).intValue() == 600, "Cache evita repetir rota idêntica");
        Map<String, Object> query = queries.get(0);
        check(BotJson.object(BotJson.array(query.get("locations")).get(0)).get("lat").equals(base.get("lat")), "Origem é a base, não a ocorrência");
        check(BotJson.write(query).contains("3.2") && BotJson.text(query, "costing").equals("truck"), "Dimensões do perfil são enviadas ao motor");
        check(BotJson.array(route.get("coordinates")).size() == 2, "Geometria GeoJSON disponível para o mapa");
        router.disconnect();
        try { router.calculate(server, call, base, custom); throw new AssertionError("Rota desconectada aceita"); } catch (IllegalArgumentException expected) {}
        ValhallaRouting failing = new ValhallaRouting((s,p,b) -> { throw new java.io.IOException("simulada"); });
        try { failing.connect(server); throw new AssertionError("Falha esperada"); } catch (java.io.IOException expected) {}
        check(Boolean.FALSE.equals(failing.status().get("connected")), "Falha mantém roteamento desconectado");
        if (!Arrays.asList(args).contains("--no-sockets")) {http(root, router);reportHttp(root);linkageHttp(root);historyHttp(root);}
        try(java.util.stream.Stream<Path> paths=Files.walk(root)) {
            for(Path file:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(file);
        }
        System.out.println("PASS mapa operacional: estado, escopo, classificação, coordenadas, persistência, perfis, roteamento opcional, cache e HTTP local");
    }
    static void areas() throws Exception {
        CadOperationalState state=state();
        state.accept(xml("<rtnRecuperarCacheAntesValidarPreRequisitos><unidadesServico><i id='7'><descricao>Área Contagem</descricao></i></unidadesServico></rtnRecuperarCacheAntesValidarPreRequisitos>"));
        state.accept(xml("<envInformarAlteracaoChamada><resumoChamada><idChamada>1</idChamada><situacaoChamada>A</situacaoChamada><resumosAtendimentosChamada><i><orgao>BM</orgao><idUnidadeArea>7</idUnidadeArea><idUnidadeResponsavel>99</idUnidadeResponsavel></i><i><orgao>BM</orgao><idUnidadeArea>7</idUnidadeArea></i><i><orgao>PM</orgao><idUnidadeArea>8</idUnidadeArea></i></resumosAtendimentosChamada></resumoChamada></envInformarAlteracaoChamada>"));
        List<Object> areas=BotJson.array(firstCall(state).get("areas"));
        check(areas.size()==1 && BotJson.text(BotJson.object(areas.get(0)),"name").equals("Área Contagem") && BotJson.text(BotJson.object(areas.get(0)),"id").equals("7"),"Área usa catálogo e atendimento BM sem duplicação ou substituição pela unidade responsável");
    }
    static void observation() throws Exception {
        CadCommunication comm=new CadCommunication();comm.beginSession();
        comm.request("1","envTeste","",0,"started",0);comm.request("1","envTeste","",0,"ok",12);comm.request("1","envTeste","",0,"ok",12);
        comm.request("2","envTeste","1",1,"started",0);comm.request("2","envTeste","1",1,"timeout",25);
        comm.request("3","envTeste","",0,"started",0);comm.beginSession();
        Map<String,Object> stats=comm.snapshot();
        check(((Number)stats.get("total")).intValue()==3 && ((Number)stats.get("completed")).intValue()==1 && ((Number)stats.get("pending")).intValue()==0 && ((Number)stats.get("interrupted")).intValue()==1,"Contadores deduplicam respostas e reconexão encerra pendências anteriores");
        for(int n=4;n<400;n++){comm.request(""+n,"envTeste","",0,"started",0);comm.request(""+n,"envTeste","",0,"rejected",1);}
        check(BotJson.array(comm.snapshot().get("requests")).size()==300,"Registros de requisições são limitados sem perder contadores");
        CadOperationalState state=state();
        state.details.request("1","envRecuperarResumosHistoricosChamada",1,"started");
        state.accept(xml("<rtnRecuperarResumosHistoricosChamada readerCallId='1'><resumosHistoricosChamada><i id='h1'><descricao>Registro já recebido</descricao><dataHoraInclusao>1700000000000</dataHoraInclusao></i></resumosHistoricosChamada><paginationBean><totalSize>1</totalSize><pageSize>50</pageSize></paginationBean></rtnRecuperarResumosHistoricosChamada>"));
        state.details.finished("1",false,true);
        Map<String,Object> history=BotJson.object(state.callDetails("1").get("history"));
        check(BotJson.array(history.get("items")).size()==1 && Boolean.TRUE.equals(history.get("verified")),"Histórico já recebido é exposto sem iniciar consulta");
        state.details.request("1","envRecuperarEnvolvidosChamada",1,"started");
        state.accept(xml("<rtnRecuperarEnvolvidosChamada readerCallId='1'><envolvidosChamada><i id='p1'><nome>Contato local</nome><telefone>12345</telefone><solicitante>true</solicitante></i></envolvidosChamada></rtnRecuperarEnvolvidosChamada>"));
        state.details.finished("1",true,true);
        Map<String,Object> people=BotJson.object(state.callDetails("1").get("people"));
        check(BotJson.array(people.get("items")).size()==1 && !Boolean.TRUE.equals(people.get("verified")),"Sem paginação, conteúdo recebido não é declarado completo");
        state.details.request("1","envRecuperarResumosHistoricosChamada",1,"started");
        StringBuilder many=new StringBuilder("<rtnRecuperarResumosHistoricosChamada readerCallId='1' readerQueryPage='1'><resumosHistoricosChamada>");
        for(int n=0;n<201;n++)many.append("<i id='h").append(n).append("'><descricao>").append(n==0?"x".repeat(8001):"Registro "+n).append("</descricao></i>");
        many.append("</resumosHistoricosChamada></rtnRecuperarResumosHistoricosChamada>");state.accept(xml(many.toString()));
        Map<String,Object> limited=BotJson.object(state.details.get("1").get("history"));
        check(BotJson.array(limited.get("items")).size()==200 && Boolean.TRUE.equals(limited.get("truncated")),"Limites de histórico sinalizam conteúdo truncado");
        state.details.request("1","envRecuperarResumosHistoricosChamada",1,"started");state.details.request("1","envRecuperarResumosHistoricosChamada",1,"rejected");
        check(Boolean.TRUE.equals(BotJson.object(state.details.get("1").get("history")).get("truncated")),"Recusa de atualização preserva os dados e a indicação dos limites anteriores");
        state.reset();check(BotJson.array(BotJson.object(state.details.get("1").get("history")).get("items")).isEmpty(),"Nova sessão limpa contatos e históricos da sessão anterior");
    }
    static void projectionCache() throws Exception {
        CadOperationalState state=state(); Map<String,Object> first=state.snapshot();
        check(first==state.snapshot(),"Retrato sem mudanças reutiliza a projeção em memória");
        long revision=state.version();
        state.accept(xml("<envInformarAlteracaoHistoricoChamada><idChamada>1</idChamada></envInformarAlteracaoHistoricoChamada>"));
        check(state.version()==revision && first==state.snapshot(),"Histórico não invalida o mapa sem alteração de chamada ou recurso");
        state.publicationStatus("Discord: teste"); Map<String,Object> second=state.snapshot();
        check(second!=first && BotJson.text(second,"publicationStatus").equals("Discord: teste"),"Mudança de estado de publicação invalida a versão");
        state.publicationStatus("Discord: teste");check(state.snapshot()==second,"Timer Swing não invalida retrato com estado idêntico");
        state.activity("log","Primeiro","");long cursor=state.activityVersion();state.activity("log","Segundo","");
        check(state.activities(cursor).size()==1 && BotJson.text(BotJson.object(state.activities(cursor).get(0)),"title").equals("Segundo"),"Cursor retorna só eventos posteriores");
        check(state.activities(state.activityVersion()).isEmpty(),"Cursor atual não repete eventos antigos");
    }
    static void matrixAndActivity() throws Exception {
        CadOperationalState state = state();
        for(int n=0;n<1005;n++) state.activity("log","Registro "+n,"");
        check(state.activities().size()==1000 && BotJson.text(BotJson.object(state.activities().get(0)),"title").equals("Registro 1004"),"Eventos limitados e em ordem decrescente");
        state.publicationStatus("Discord: fila pausada");
        check(BotJson.text(state.snapshot(),"publicationStatus").contains("pausada"),"Configuração Web reflete o estado real de publicação");
        state.reset(); check(state.activities().size()==1000,"Reconexão preserva eventos desta execução");
        List<Object> bases = Java8.list(Java8.map("id","a","lat",-19.9,"lon",-44.1),Java8.map("id","b","lat",-19.8,"lon",-44.2),Java8.map("id","pending","lat",null,"lon",null));
        Map<String,Object> profile = Java8.map("id","truck","costing","truck","height",3.2);
        AtomicInteger matrices = new AtomicInteger();
        ValhallaRouting router = new ValhallaRouting((server,path,query)->{
            if(path.equals("/status"))return Java8.map("version","test");
            check(path.equals("/sources_to_targets"),"Comparação usa matriz, nunca N rotas");
            matrices.incrementAndGet();
            check(BotJson.array(query.get("sources")).size()==2 && BotJson.array(query.get("targets")).size()==1,"Múltiplas bases na origem, uma ocorrência no destino");
            check(((Number)BotJson.object(BotJson.object(query.get("costing_options")).get("truck")).get("height")).doubleValue()==(matrices.get()==1?3.2:4.1) && BotJson.text(query,"units").equals("kilometers"),"Matriz preserva dimensões e unidade de distância");
            return Java8.map("sources_to_targets",Java8.list(Java8.list(Java8.map("time",120,"distance",2.5)),Java8.list(Java8.map("time",null,"distance",null))));
        });
        router.connect("http://localhost:9999");
        List<Object> result=router.matrix("http://localhost:9999",state().call("1"),bases,profile);
        check(result.size()==2 && BotJson.text(BotJson.object(result.get(0)),"baseId").equals("a"),"Resultados da matriz associados à base correta");
        check(((Number)BotJson.object(BotJson.object(result.get(0)).get("route")).get("seconds")).intValue()==120,"Tempo da matriz em segundos");
        check(BotJson.object(result.get(1)).containsKey("error"),"Par inalcançável não inventa tempo ou distância");
        router.matrix("http://localhost:9999",state().call("1"),bases,profile);
        check(matrices.get()==1,"Cache evita matriz repetida");
        Map<String,Object> otherProfile = new LinkedHashMap<>(profile); otherProfile.put("height",4.1);
        router.matrix("http://localhost:9999",state().call("1"),bases,otherProfile);
        check(matrices.get()==2,"Mudança de dimensões invalida o cache da matriz");
        AtomicInteger batches = new AtomicInteger();
        ValhallaRouting batched = new ValhallaRouting((host,path,body)->{
            if(path.equals("/status")) return Java8.map("version","test");
            batches.incrementAndGet(); List<Object> rows = new ArrayList<>();
            check(BotJson.array(body.get("sources")).size()<=25,"Lote de matriz respeita limite de origens");
            for(Object ignored:BotJson.array(body.get("sources"))) rows.add(Java8.list(Java8.map("time",90,"distance",1.0)));
            return Java8.map("sources_to_targets",rows);
        });
        batched.connect("http://localhost:9999"); List<Object> many = new ArrayList<>();
        for(int n=0;n<26;n++)many.add(Java8.map("id","base"+n,"lat",-19.9,"lon",-44.1));
        List<Object> batchResult=batched.matrix("http://localhost:9999",state().call("1"),many,profile);
        check(batches.get()==2 && batchResult.size()==26 && BotJson.text(BotJson.object(batchResult.get(25)),"baseId").equals("base25"),"Segundo lote preserva correspondência dos índices às bases");
        router.disconnect();
        try {router.matrix("http://localhost:9999",state().call("1"),bases,profile);throw new AssertionError("Matriz desconectada aceita");}catch(IllegalArgumentException expected){}
    }
    static Map<String, Object> firstCall(CadOperationalState state) {
        return BotJson.object(BotJson.array(state.snapshot().get("calls")).get(0));
    }
    static String resource(String unit, String status) {
        return "<envInformarAlteracaoRecurso><resumoRecurso><idRecurso>77</idRecurso><prefixo>ABTS 77</prefixo>"
            + "<idUnidadeServico>" + unit + "</idUnidadeServico><idTipoRecurso>3</idTipoRecurso>"
            + "<resumosEmpenhosRecurso><i><idChamada>1</idChamada><situacaoEmpenhoRecurso>" + status
            + "</situacaoEmpenhoRecurso></i></resumosEmpenhosRecurso></resumoRecurso></envInformarAlteracaoRecurso>";
    }
    static void assignments() throws Exception {
        CadOperationalState state = state();
        check(BotJson.text(firstCall(state), "assignmentStatus").equals("unlinked"), "Ausência observada é explícita, sem inventar empenho");
        state.accept(xml(resource("7", "DP")));
        check(BotJson.text(firstCall(state), "assignmentStatus").equals("linked")
            && BotJson.write(firstCall(state).get("assignedResources")).contains("ABTS 77"), "Atualização de recurso associa a viatura à ocorrência");
        state.accept(xml(resource("7", "TR")));
        check(BotJson.array(firstCall(state).get("assignedResources")).isEmpty(), "Empenho terminado deixa o mapa sem vínculo ativo");
        state.accept(xml(resource("7", "NL")));
        state.accept(xml(resource("999", "NL")));
        check(BotJson.array(firstCall(state).get("assignedResources")).isEmpty(), "Saída do filtro remove o vínculo observado");
        String call = "<envInformarAlteracaoChamada><resumoChamada><idChamada>1</idChamada><situacaoChamada>A</situacaoChamada>"
            + "<resumosNaturezasChamada><i><codigo>S01</codigo><descricao>Salvamento</descricao></i><i><codigo>I02</codigo><descricao>Incêndio</descricao></i></resumosNaturezasChamada>"
            + "<resumosAtendimentosChamada><i><orgao>BM</orgao><resumosEmpenhosRecurso><i><idRecurso>77</idRecurso><prefixo>ABTS 77</prefixo><situacaoEmpenhoRecurso>AC</situacaoEmpenhoRecurso></i></resumosEmpenhosRecurso></i>"
            + "<i><orgao>PM</orgao><resumosEmpenhosRecurso><i><idRecurso>88</idRecurso><prefixo>PM 88</prefixo><situacaoEmpenhoRecurso>NL</situacaoEmpenhoRecurso></i></resumosEmpenhosRecurso></i></resumosAtendimentosChamada>"
            + "</resumoChamada></envInformarAlteracaoChamada>";
        state.accept(xml(call));
        check(BotJson.array(firstCall(state).get("natures")).size() == 2, "Todas as naturezas permanecem estruturadas para os filtros");
        check(BotJson.array(firstCall(state).get("assignedResources")).size() == 1
            && !BotJson.write(firstCall(state)).contains("PM 88"), "Resumo da chamada identifica apenas vínculos BM, inclusive fora do filtro de recursos");
        state.accept(xml(resource("7", "TR")));
        check(BotJson.array(firstCall(state).get("assignedResources")).isEmpty(), "Recurso posterior encerrado substitui o vínculo antigo da chamada");
        state.accept(xml(resource("7", "DP")));
        state.accept(xml("<envInformarAlteracaoChamada><resumoChamada><idChamada>1</idChamada><situacaoChamada>A</situacaoChamada><resumosAtendimentosChamada><i><orgao>BM</orgao><resumosEmpenhosRecurso/></i></resumosAtendimentosChamada></resumoChamada></envInformarAlteracaoChamada>"));
        check(BotJson.array(firstCall(state).get("assignedResources")).isEmpty(), "Resumo BM posterior sem empenho substitui recurso antigo");
        state.accept(xml("<envInformarAlteracaoChamada><resumoChamada><idChamada>1</idChamada><situacaoChamada>E</situacaoChamada></resumoChamada></envInformarAlteracaoChamada>"));
        check(BotJson.text(firstCall(state), "assignmentStatus").equals("linked"), "Vínculo observado não é perdido quando uma atualização não traz a coleção BM");
        state.accept(xml(resource("7", "TR")));
        check(BotJson.text(firstCall(state), "assignmentStatus").equals("reported"), "Empenho informado sem viatura identificada não é tratado como ausência confirmada");
        check(!BotJson.write(state.snapshot()).contains("_revision"), "Metadados de correlação não são expostos no DTO Web");
    }
    static HttpClient adminClient(CadWebServer web,String address) throws Exception {
        HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        adminLogin(client,web,address);return client;
    }
    static void adminLogin(HttpClient client,CadWebServer web,String address) throws Exception {
        Map<String,Object> bootstrap=BotJson.object(BotJson.parse(client.send(HttpRequest.newBuilder(URI.create(address+"/api/state")).GET().build(),HttpResponse.BodyHandlers.ofString()).body()));
        HttpResponse<String> response=client.send(HttpRequest.newBuilder(URI.create(address+"/api/admin/login"))
            .header("X-CAD-Token",BotJson.text(bootstrap,"token"))
            .POST(HttpRequest.BodyPublishers.ofString(BotJson.write(Java8.map("name","http-test","password","test-password")))).build(),HttpResponse.BodyHandlers.ofString());
        check(response.statusCode()==200,"Login administrativo: "+response.body());
    }
    static void historyHttp(Path root) throws Exception {
        AtomicInteger requested=new AtomicInteger();
        try(CadWebServer web=new CadWebServer(state(),root,0,new ValhallaRouting(),()->null,id->{check(id.equals("1"),"Consulta usa ID validado");requested.incrementAndGet();})){
            HttpClient client=HttpClient.newHttpClient();
            Map<String,Object> bootstrap=BotJson.object(BotJson.parse(client.send(HttpRequest.newBuilder(URI.create(web.url()+"/api/state")).GET().build(),HttpResponse.BodyHandlers.ofString()).body()));
            client.send(HttpRequest.newBuilder(URI.create(web.url()+"/api/call?id=1")).GET().build(),HttpResponse.BodyHandlers.ofString());
            check(requested.get()==0,"Abrir detalhes não consulta CAD");
            for(String id:Java8.list("1","2","inválido")){
                HttpRequest req=HttpRequest.newBuilder(URI.create(web.url()+"/api/call/history")).header("X-CAD-Token",BotJson.text(bootstrap,"token")).POST(HttpRequest.BodyPublishers.ofString(BotJson.write(Java8.map("callId",id)))).build();
                check(client.send(req,HttpResponse.BodyHandlers.ofString()).statusCode()==(id.equals("1")?202:400),"Histórico valida ocorrência ativa antes de consultar");
            }
            check(requested.get()==1,"Somente solicitação explícita válida consulta CAD");
            HttpRequest unauth=HttpRequest.newBuilder(URI.create(web.url()+"/api/call/history")).POST(HttpRequest.BodyPublishers.ofString("{\"callId\":\"1\"}")).build();
            check(client.send(unauth,HttpResponse.BodyHandlers.ofString()).statusCode()==403 && requested.get()==1,"Histórico exige token da sessão");
        }
    }
    static void linkageHttp(Path root) throws Exception {
        ValhallaRouting routing=new ValhallaRouting((host,path,body)->{throw new NoClassDefFoundError("br/gov/mg/sids/reader/DiscordBotApi");});
        try(CadWebServer web=new CadWebServer(state(),root,0,routing)){
            HttpClient client=adminClient(web, web.url());
            Map<String,Object> bootstrap=BotJson.object(BotJson.parse(client.send(HttpRequest.newBuilder(URI.create(web.url()+"/api/state")).GET().build(),HttpResponse.BodyHandlers.ofString()).body()));
            HttpRequest connect=HttpRequest.newBuilder(URI.create(web.url()+"/api/routing/connect")).header("X-CAD-Token",BotJson.text(bootstrap,"token")).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            for(int n=0;n<2;n++){
                HttpResponse<String> failed=client.send(connect,HttpResponse.BodyHandlers.ofString());
                check(failed.statusCode()==503&&failed.body().contains("Feche completamente"),"Falha de classe retorna orientação de reinício sem derrubar requisição HTTP");
            }
            check(client.send(HttpRequest.newBuilder(URI.create(web.url()+"/api/state")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200,"Servidor local continua respondendo após falha de classe no Valhalla");
        }
    }
    static void reportHttp(Path root) throws Exception {
        AtomicInteger count=new AtomicInteger();java.util.concurrent.CompletableFuture<Element> response=new java.util.concurrent.CompletableFuture<>();
        CadSummary summary=new CadSummary(id->{count.incrementAndGet();return response;});
        summary.catalog(xml("<cache><filtrosChamada><i id='99'><descricao>Outro filtro</descricao></i></filtrosChamada></cache>"));
        CadOperationalState state=state();
        try(CadWebServer web=new CadWebServer(state,root,0,new ValhallaRouting(),()->summary)){
            HttpClient client=HttpClient.newHttpClient();
            HttpResponse<String> catalog=client.send(HttpRequest.newBuilder(URI.create(web.url()+"/api/report-filters")).GET().build(),HttpResponse.BodyHandlers.ofString());
            check(catalog.statusCode()==200&&catalog.body().contains("Outro filtro")&&count.get()==0,"Ler catálogo não consulta o CAD");
            Map<String,Object> bootstrap=BotJson.object(BotJson.parse(client.send(HttpRequest.newBuilder(URI.create(web.url()+"/api/state")).GET().build(),HttpResponse.BodyHandlers.ofString()).body()));
            String token=BotJson.text(bootstrap,"token");
            HttpRequest denied=HttpRequest.newBuilder(URI.create(web.url()+"/api/report")).POST(HttpRequest.BodyPublishers.ofString(BotJson.write(Java8.map("filterId","99")))).build();
            check(client.send(denied,HttpResponse.BodyHandlers.ofString()).statusCode()==403&&count.get()==0,"Somatório exige token antes de solicitar CAD");
            HttpRequest query=HttpRequest.newBuilder(URI.create(web.url()+"/api/report")).header("X-CAD-Token",token).POST(HttpRequest.BodyPublishers.ofString(BotJson.write(Java8.map("filterId","99")))).build();
            java.util.concurrent.CompletableFuture<HttpResponse<String>> first=client.sendAsync(query,HttpResponse.BodyHandlers.ofString());
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(count.get()==0&&System.nanoTime()<deadline)Thread.sleep(10);
            check(count.get()==1,"Clique de consulta inicia exatamente um pedido");
            check(client.send(query,HttpResponse.BodyHandlers.ofString()).statusCode()==409&&count.get()==1,"Requisições Web concorrentes são limitadas sem sobrecarregar CAD");
            check(client.send(HttpRequest.newBuilder(URI.create(web.url()+"/api/state")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200,"Estado local continua disponível durante consulta CAD");
            response.complete(xml("<rtn><resumosChamadas><i><idChamada>900</idChamada><resumosNaturezasChamada><i><codigo>I01</codigo><descricao>Teste</descricao></i></resumosNaturezasChamada></i></resumosChamadas></rtn>"));
            HttpResponse<String> result=first.get(5,java.util.concurrent.TimeUnit.SECONDS);Map<String,Object> body=BotJson.object(BotJson.parse(result.body()));
            check(result.statusCode()==200&&((Number)body.get("total")).intValue()==1&&BotJson.text(body,"filterId").equals("99"),"Dashboard recebe relatório estruturado do filtro solicitado");
            check(summary.query("99").get().equals(BotJson.text(body,"text"))&&count.get()==1,"Bot reaproveita cache da consulta Web");
            check(client.send(query,HttpResponse.BodyHandlers.ofString()).statusCode()==200&&count.get()==1,"Novo clique dentro do cache não gera nova consulta");
            check(BotJson.text(firstCall(state),"id").equals("1")&&BotJson.array(state.snapshot().get("calls")).size()==1,"Outro filtro não modifica o estado monitorado no mapa");
        }
    }
    static void http(Path root, ValhallaRouting router) throws Exception {
        CadOperationalState state = state(); state.activity("log","Publicação confirmada", "Teste");
        try (CadWebServer web = new CadWebServer(state, root, 0, router)) {
            HttpClient client = adminClient(web, web.url());
            HttpResponse<String> page = client.send(HttpRequest.newBuilder(URI.create(web.url())).GET().build(), HttpResponse.BodyHandlers.ofString());
            check(page.statusCode() == 200 && page.body().contains("CAD Reader"), "Frontend servido de dentro do JAR");
            HttpResponse<String> catalog = client.send(HttpRequest.newBuilder(URI.create(web.url() + "/assets/unidades-cbmmg-rmbh.csv")).GET().build(), HttpResponse.BodyHandlers.ofString());
            check(catalog.statusCode() == 200 && catalog.headers().firstValue("Content-Type").orElse("").startsWith("text/csv")
                && catalog.body().contains("cbmmg-betim") && catalog.body().contains("cbmmg-ribeirao-das-neves"), "CSV pesquisado servido pelo JAR");
            HttpResponse<String> bootstrap = client.send(HttpRequest.newBuilder(URI.create(web.url() + "/api/state")).GET().build(), HttpResponse.BodyHandlers.ofString());
            Map<String, Object> data = BotJson.object(BotJson.parse(bootstrap.body()));
            String token = BotJson.text(data, "token");
            for(String endpoint:Java8.list("/api/communication","/api/call?id=1")){
                HttpResponse<String> observation=client.send(HttpRequest.newBuilder(URI.create(web.url()+endpoint)).GET().build(),HttpResponse.BodyHandlers.ofString());
                check(observation.statusCode()==200 && !observation.body().isEmpty(),"Dados locais disponíveis: "+endpoint);
            }

            // O alias de loopback prova o bind em todas as interfaces, sem depender de rede externa.
            java.util.List<String> addresses=new java.util.ArrayList<>();
            addresses.add("127.0.0.2");
            java.util.Enumeration<java.net.NetworkInterface> interfaces=java.net.NetworkInterface.getNetworkInterfaces();
            while(interfaces.hasMoreElements()){
                java.net.NetworkInterface network=interfaces.nextElement();
                if(!network.isUp()||network.isLoopback())continue;
                java.util.Enumeration<java.net.InetAddress> ips=network.getInetAddresses();
                while(ips.hasMoreElements()){
                    java.net.InetAddress ip=ips.nextElement();
                    if(ip instanceof java.net.Inet4Address)addresses.add(ip.getHostAddress());
                }
            }
            for(String address:addresses){
                String networkUrl="http://"+address+":"+URI.create(web.url()).getPort();
                check(client.send(HttpRequest.newBuilder(URI.create(networkUrl+"/api/state")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200,"Estado acessível pelo IP "+address);
                adminLogin(client, web, networkUrl);
                HttpRequest allowed=HttpRequest.newBuilder(URI.create(networkUrl+"/api/routing/disconnect")).header("Origin",networkUrl).header("X-CAD-Token",token).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
                check(client.send(allowed,HttpResponse.BodyHandlers.ofString()).statusCode()==200,"Escrita com origem e token válidos pelo IP "+address);
                HttpRequest blocked=HttpRequest.newBuilder(URI.create(networkUrl+"/api/routing/disconnect")).header("Origin","http://external.example").header("X-CAD-Token",token).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
                check(client.send(blocked,HttpResponse.BodyHandlers.ofString()).statusCode()==403,"Origem externa recusada no acesso por IP "+address);
            }

            String etag=bootstrap.headers().firstValue("ETag").orElse("");
            HttpResponse<String> unchanged=client.send(HttpRequest.newBuilder(URI.create(web.url()+"/api/state")).header("If-None-Match",etag).GET().build(),HttpResponse.BodyHandlers.ofString());
            check(!etag.isEmpty() && unchanged.statusCode()==304 && unchanged.body().isEmpty(),"Estado sem mudança não transmite nem serializa o retrato novamente");
            state.publicationStatus("Discord: novo estado");
            HttpResponse<String> changed=client.send(HttpRequest.newBuilder(URI.create(web.url()+"/api/state")).header("If-None-Match",etag).GET().build(),HttpResponse.BodyHandlers.ofString());
            check(changed.statusCode()==200 && changed.body().contains("novo estado"),"Alteração real invalida ETag local");
            state.activity("log","Segundo registro","");
            HttpResponse<String> delta=client.send(HttpRequest.newBuilder(URI.create(web.url()+"/api/events?after=1")).GET().build(),HttpResponse.BodyHandlers.ofString());
            check(BotJson.array(BotJson.parse(delta.body())).size()==1 && delta.body().contains("Segundo registro"),"API incremental não reenvia histórico de eventos");
            HttpResponse<String> events = client.send(HttpRequest.newBuilder(URI.create(web.url()+"/api/events")).GET().build(),HttpResponse.BodyHandlers.ofString());
            check(events.statusCode()==200 && events.body().contains("Publicação confirmada"),"API de eventos expõe os registros reais");
            check(!token.isEmpty() && BotJson.write(data).contains("ABTS teste"), "Estado e sessão Web disponíveis");
            for (String badOrigin : Java8.list("", "https://external.example")) {
                HttpRequest.Builder post = HttpRequest.newBuilder(URI.create(web.url() + "/api/routing/connect"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"));
                if (!badOrigin.isEmpty()) post.header("Origin", badOrigin).header("X-CAD-Token", token);
                check(client.send(post.build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 403, "Escrita sem token ou com origem externa recusada");
            }
            HttpRequest connect = HttpRequest.newBuilder(URI.create(web.url() + "/api/routing/connect"))
                .header("X-CAD-Token", token).header("Origin", web.url()).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            check(client.send(connect, HttpResponse.BodyHandlers.ofString()).statusCode() == 200, "Conexão opcional aceita pela API local");
        }
    }
}
