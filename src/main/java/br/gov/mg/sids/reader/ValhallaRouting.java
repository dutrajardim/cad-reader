package br.gov.mg.sids.reader;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Roteamento opcional. Nunca muda filtros, dados CAD ou regras de publicação. */
final class ValhallaRouting {
    interface Transport { Map<String, Object> request(String server, String path, Map<String, Object> body) throws Exception; }
    private final Transport transport;
    private volatile String connectedServer = "", notice = "Valhalla não conectado. Mapa e Google Maps disponíveis.";
    private final Map<String, Cache> cache = new ConcurrentHashMap<>();
    private static final class Cache {
        final long at = System.nanoTime(); final Map<String, Object> value;
        Cache(Map<String, Object> value) { this.value = value; }
    }
    ValhallaRouting() { this(ValhallaRouting::http); }
    ValhallaRouting(Transport transport) { this.transport = transport; }
    private static Map<String, Object> http(String server, String path, Map<String, Object> body) throws Exception {
        HttpClient.Builder client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER);
        Proxy proxy = DiscordBotApi.discordProxy();
        // Endereços locais não devem sair pelo proxy corporativo.
        String host = URI.create(server).getHost();
        if (proxy != null && !host.equals("localhost") && !host.equals("127.0.0.1") && !host.equals("[::1]") && !host.equals("::1"))
            client.proxy(ProxySelector.of((InetSocketAddress)proxy.address()));
        else if (proxy != null)
            client.proxy(new ProxySelector() {
                public List<Proxy> select(URI uri) { return Collections.singletonList(Proxy.NO_PROXY); }
                public void connectFailed(URI uri, SocketAddress address, IOException error) {}
            });
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(server + path))
            .timeout(Duration.ofSeconds(25)).header("Accept", "application/json")
            .header("User-Agent", "CAD-Reader/1.0 (mapa operacional)")
            .header("X-Client-Id", "cad-reader");
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(BotJson.write(body)));
        HttpResponse<String> result = client.build().send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (result.statusCode() < 200 || result.statusCode() >= 300)
            throw new IOException("Valhalla HTTP " + result.statusCode() + ". Confira o endereço, acesso e limites do servidor.");
        Object parsed = BotJson.parse(result.body());
        if (!(parsed instanceof Map)) throw new IOException("Resposta Valhalla inválida.");
        return BotJson.object(parsed);
    }
    synchronized void connect(String server) throws Exception {
        disconnect();
        try {
            Map<String, Object> status = transport.request(server, "/status", null);
            if (!status.containsKey("version")) throw new IOException("O endereço não retornou um status Valhalla reconhecido.");
            connectedServer = server; notice = "Conectado ao Valhalla · versão " + BotJson.text(status, "version");
        } catch (Exception e) {
            notice = "Não foi possível conectar. Mapa e Google Maps continuam disponíveis.";
            throw e;
        }
    }
    synchronized void disconnect() { connectedServer = ""; cache.clear(); notice = "Valhalla não conectado. Mapa e Google Maps disponíveis."; }
    Map<String, Object> status() { return Java8.map("connected", !connectedServer.isEmpty(), "server", connectedServer, "notice", notice); }
    synchronized List<Object> matrix(String server, Map<String, Object> call, List<Object> bases, Map<String, Object> profile) throws Exception {
        if (!server.equals(connectedServer)) throw new IllegalArgumentException("Conecte ao Valhalla antes de comparar.");
        String key = "matrix:" + BotJson.write(Java8.list(server, bases, call.get("lat"), call.get("lon"), profile));
        Cache saved = cache.get(key);
        if (saved != null && System.nanoTime() - saved.at < Duration.ofMinutes(5).toNanos()) return BotJson.array(saved.value.get("results"));
        List<Object> sources = new ArrayList<>(), answers = new ArrayList<>();
        List<Map<String,Object>> located = new ArrayList<>();
        for (Object value : bases) {
            Map<String,Object> base = BotJson.object(value);
            if (base.get("lat") != null && base.get("lon") != null) located.add(base);
        }
        Map<String,Object> dimensions = new LinkedHashMap<>();
        for (String field : Java8.list("height", "width", "length", "weight", "axle_load"))
            if (profile.containsKey(field)) dimensions.put(field, profile.get(field));
        // Lotes pequenos respeitam o limite usual do servidor público; nunca há fallback N rotas.
        for (int offset = 0; offset < located.size(); offset += 25) {
            sources.clear();
            int end = Math.min(offset + 25, located.size());
            for (int n = offset; n < end; n++) sources.add(Java8.map("lat", located.get(n).get("lat"), "lon", located.get(n).get("lon")));
            Map<String,Object> result = transport.request(server, "/sources_to_targets", Java8.map(
                "sources", new ArrayList<>(sources), "targets", Java8.list(Java8.map("lat", call.get("lat"), "lon", call.get("lon"))),
                "costing", profile.get("costing"), "costing_options", Java8.map(BotJson.text(profile,"costing"), dimensions), "units", "kilometers", "verbose", true));
            List<Object> rows = BotJson.array(result.get("sources_to_targets"));
            if (rows.size() != sources.size()) throw new IOException("Matriz Valhalla incompleta.");
            for (int n = 0; n < rows.size(); n++) {
                List<Object> cells = BotJson.array(rows.get(n));
                if (cells.size() != 1) throw new IOException("Matriz Valhalla inválida.");
                Map<String,Object> cell = BotJson.object(cells.get(0)), base = located.get(offset+n);
                Object time = cell.get("time"), distance = cell.get("distance");
                if (time instanceof Number && distance instanceof Number && Double.isFinite(((Number)time).doubleValue()) && Double.isFinite(((Number)distance).doubleValue()) && ((Number)time).doubleValue() >= 0 && ((Number)distance).doubleValue() >= 0)
                    answers.add(Java8.map("baseId",base.get("id"),"profileId",profile.get("id"),"route",Java8.map("baseId",base.get("id"),"profileId",profile.get("id"),"seconds",time,"kilometers",distance,"coordinates",Collections.emptyList())));
                else answers.add(Java8.map("baseId",base.get("id"),"profileId",profile.get("id"),"error","Sem conexão viária para este par na matriz."));
            }
            if (end < located.size()) Thread.sleep(1100);
        }
        if (cache.size() >= 300) cache.clear();
        cache.put(key, new Cache(Java8.map("results",answers)));
        return answers;
    }
    synchronized Map<String, Object> calculate(String server, Map<String, Object> call, Map<String, Object> base,
                                               Map<String, Object> profile) throws Exception {
        if (!server.equals(connectedServer)) throw new IllegalArgumentException("Conecte ao servidor Valhalla antes de calcular.");
        if (base.get("lat") == null || base.get("lon") == null)
            throw new IllegalArgumentException("Cadastre as coordenadas da base antes de calcular no Valhalla.");
        String key = BotJson.write(Java8.list(server, base, call.get("lat"), call.get("lon"), profile));
        Cache saved = cache.get(key);
        if (saved != null && System.nanoTime() - saved.at < Duration.ofMinutes(5).toNanos()) return saved.value;
        Map<String, Object> dimensions = new LinkedHashMap<>();
        for (String field : Java8.list("height", "width", "length", "weight", "axle_load"))
            if (profile.containsKey(field)) dimensions.put(field, profile.get(field));
        Map<String, Object> query = Java8.map("locations", Java8.list(
            Java8.map("lat", base.get("lat"), "lon", base.get("lon")),
            Java8.map("lat", call.get("lat"), "lon", call.get("lon"))),
            "costing", profile.get("costing"), "costing_options", Java8.map(BotJson.text(profile, "costing"), dimensions),
            "units", "kilometers", "language", "pt-BR", "shape_format", "geojson");
        Map<String, Object> result = transport.request(server, "/route", query);
        Map<String, Object> trip = BotJson.object(result.get("trip")), summary = BotJson.object(trip.get("summary"));
        if (!(summary.get("time") instanceof Number) || !(summary.get("length") instanceof Number))
            throw new IOException("Valhalla não retornou tempo/distância para este percurso.");
        List<Object> coordinates = new ArrayList<>();
        for (Object leg : BotJson.array(trip.get("legs"))) {
            Map<String, Object> shape = BotJson.object(BotJson.object(leg).get("shape"));
            coordinates.addAll(BotJson.array(shape.get("coordinates")));
        }
        Map<String, Object> answer = Java8.map("baseId", base.get("id"), "profileId", profile.get("id"),
            "seconds", summary.get("time"), "kilometers", summary.get("length"), "coordinates", coordinates);
        if (cache.size() >= 300) cache.clear();
        cache.put(key, new Cache(answer)); return answer;
    }
}
