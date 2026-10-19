package br.gov.mg.sids.reader;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/** Cadastro local independente dos perfis/segredos da conexão CAD. */
final class OperationalSettings {
    private final Path file;
    private Map<String, Object> settings;
    private long revision;
    synchronized long version(){return revision;}
    OperationalSettings(Path root) throws IOException {
        file = root.resolve("mapa-operacional.json");
        settings = defaults();
        if (Files.exists(file)) settings = validate(BotJson.object(BotJson.parse(Files.readString(file))));
    }
    static Map<String, Object> defaults() {
        return new LinkedHashMap<>(Java8.map("server", "https://valhalla1.openstreetmap.de", "bases", new ArrayList<>(),
            "profiles", Java8.list(
                Java8.map("id", "car", "name", "Carro padrão", "costing", "auto", "height", 1.9, "width", 1.6, "length", 2.7, "weight", 0.8),
                Java8.map("id", "truck", "name", "Caminhão padrão", "costing", "truck", "height", 4.11, "width", 2.6, "length", 21.64, "weight", 21.77, "axle_load", 9.07))));
    }
    synchronized Map<String, Object> get() {
        return BotJson.object(BotJson.parse(BotJson.write(settings)));
    }
    synchronized void save(Map<String, Object> value) throws IOException {
        Map<String, Object> checked = validate(value);
        AppFiles.write(file, BotJson.write(checked)); settings = checked; revision++;
    }
    static String server(String value) {
        try {
            URI uri = URI.create(value.trim());
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
            return uri.toString().replaceAll("/+$", "");
        } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Informe uma URL HTTP/HTTPS válida, sem credenciais, consulta ou fragmento."); }
    }
    private static String required(Map<String, Object> value, String key, int max) {
        String text = BotJson.text(value, key).trim();
        if (text.isEmpty() || text.length() > max) throw new IllegalArgumentException("Campo inválido: " + key);
        return text;
    }
    static Map<String, Object> validate(Map<String, Object> value) {
        List<Object> bases = new ArrayList<>(), profiles = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        if (!(value.get("bases") instanceof List) || !(value.get("profiles") instanceof List))
            throw new IllegalArgumentException("Informe as listas de unidades e perfis.");
        if (BotJson.array(value.get("bases")).size() > 200 || BotJson.array(value.get("profiles")).size() > 30)
            throw new IllegalArgumentException("Limite: 200 bases e 30 perfis.");
        for (Object item : BotJson.array(value.get("profiles"))) {
            Map<String, Object> p = BotJson.object(item);
            String id = required(p, "id", 80), costing = required(p, "costing", 20);
            if (!ids.add(id) || !(costing.equals("auto") || costing.equals("truck"))) throw new IllegalArgumentException("Perfil duplicado ou modo inválido.");
            Map<String, Object> profile = new LinkedHashMap<>(Java8.map("id", id, "name", required(p, "name", 100), "costing", costing));
            for (String dimension : Java8.list("height", "width", "length", "weight", "axle_load")) {
                String raw = BotJson.text(p, dimension);
                if (raw.isEmpty()) continue;
                Double n = CadOperationalState.coordinate(raw, 1000);
                if (n == null || n <= 0) throw new IllegalArgumentException("Dimensão/peso deve ser positivo: " + dimension);
                profile.put(dimension, n);
            }
            profiles.add(profile);
        }
        if (profiles.isEmpty()) throw new IllegalArgumentException("Cadastre pelo menos um perfil.");
        ids.clear();
        for (Object item : BotJson.array(value.get("bases"))) {
            Map<String, Object> b = BotJson.object(item);
            String id = required(b, "id", 80), unit = BotJson.text(b, "unitId").trim();
            String rawLat = BotJson.text(b, "lat"), rawLon = BotJson.text(b, "lon");
            Double lat = rawLat.isEmpty() ? null : CadOperationalState.coordinate(rawLat, 90);
            Double lon = rawLon.isEmpty() ? null : CadOperationalState.coordinate(rawLon, 180);
            if (!ids.add(id) || (!unit.isEmpty() && !unit.matches("[0-9]{1,30}"))
                || (!rawLat.isEmpty() && lat == null) || (!rawLon.isEmpty() && lon == null) || (lat == null) != (lon == null))
                throw new IllegalArgumentException("Base duplicada, unidade CAD ou par de coordenadas inválido.");
            Map<String, Object> base = new LinkedHashMap<>(Java8.map("id", id, "name", required(b, "name", 120),
                "unitId", unit, "lat", lat, "lon", lon));
            for (String field : Java8.list("address", "sourceUrl", "checkedAt", "notes")) {
                String text = BotJson.text(b, field).trim();
                if (text.length() > (field.equals("notes") ? 2000 : 600))
                    throw new IllegalArgumentException("Campo da base muito longo: " + field);
                if (field.equals("sourceUrl") && !text.isEmpty()) {
                    try {
                        URI uri = URI.create(text);
                        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null)
                            throw new IllegalArgumentException();
                    } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Fonte da base deve ser uma URL HTTP/HTTPS sem credenciais."); }
                }
                if (field.equals("checkedAt") && !text.isEmpty()) {
                    try { java.time.LocalDate.parse(text); }
                    catch (java.time.DateTimeException e) { throw new IllegalArgumentException("Data de consulta inválida; use AAAA-MM-DD."); }
                }
                if (!text.isEmpty()) base.put(field, text);
            }
            if (lat == null && BotJson.text(base, "address").isEmpty())
                throw new IllegalArgumentException("Informe endereço ou coordenadas para a base.");
            bases.add(base);
        }
        return Java8.map("server", server(required(value, "server", 500)), "bases", bases, "profiles", profiles);
    }
}
