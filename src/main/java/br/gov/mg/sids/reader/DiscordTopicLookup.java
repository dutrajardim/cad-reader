package br.gov.mg.sids.reader;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;

/** Localiza tópicos do fórum do webhook, incluindo os arquivados. */
final class DiscordTopicLookup implements AutoCloseable {
    private final DiscordBotApi api;
    private final String webhookPath;
    private String forum, guild;
    DiscordTopicLookup(String token, String webhook) {
        this(new DiscordBotApi(token), webhook);
    }
    DiscordTopicLookup(DiscordBotApi api, String webhook) {
        this.api = api;
        webhookPath = "/webhooks/" + webhook.split("/webhooks/")[1];
    }
    String find(String prefix) throws Exception {
        if (forum == null) {
            Map<String, Object> hook = BotJson.object(api.request("GET", webhookPath, null));
            String channelId = BotJson.text(hook, "channel_id");
            if (!channelId.matches("[0-9]+")) throw new IOException("Webhook sem fórum válido");
            Map<String, Object> channel = BotJson.object(api.request("GET", "/channels/" + channelId, null));
            String guildId = BotJson.text(channel, "guild_id");
            if (!"15".equals(BotJson.text(channel, "type")) || !guildId.matches("[0-9]+"))
                throw new IOException("Webhook não pertence a um fórum válido");
            forum = channelId;
            guild = guildId;
        }
        Map<String, Object> active = BotJson.object(api.request("GET", "/guilds/" + guild + "/threads/active", null));
        String found = match(active, prefix);
        if (found != null) return found;
        String before = "";
        Set<String> cursors = new HashSet<>();
        while (true) {
            Map<String, Object> page = BotJson.object(api.request("GET", "/channels/" + forum
                + "/threads/archived/public?limit=100" + (before.isEmpty() ? "" : "&before="
                    + Java8.encode(before)), null));
            found = match(page, prefix);
            if (found != null) return found;
            if (!Boolean.FALSE.equals(page.get("has_more")) && !Boolean.TRUE.equals(page.get("has_more")))
                throw new IOException("Resposta incompleta na busca de tópicos");
            if (Boolean.FALSE.equals(page.get("has_more"))) return null;
            List<Object> items = BotJson.array(page.get("threads"));
            if (items.isEmpty()) throw new IOException("Paginação de tópicos incompleta");
            before = BotJson.text(BotJson.object(BotJson.object(items.get(items.size() - 1)).get("thread_metadata")),
                                  "archive_timestamp");
            if (before.isEmpty() || !cursors.add(before)) throw new IOException("Paginação de tópicos inválida");
        }
    }
    private String match(Map<String, Object> page, String prefix) throws IOException {
        if (!(page.get("threads") instanceof List)) throw new IOException("Resposta sem lista de tópicos");
        String wanted = CadMonitor.Event.normalizedPrefix(prefix), selected = null;
        for (Object item : BotJson.array(page.get("threads"))) {
            Map<String, Object> thread = BotJson.object(item);
            if (!forum.equals(BotJson.text(thread, "parent_id"))) continue;
            String name = BotJson.text(thread, "name").trim();
            if (name.regionMatches(true, 0, "VTR ", 0, 4)) name = name.substring(4);
            String id = BotJson.text(thread, "id");
            if (wanted.equals(CadMonitor.Event.normalizedPrefix(name)) && id.matches("[0-9]+")
                && (selected == null || new java.math.BigInteger(id).compareTo(new java.math.BigInteger(selected)) < 0))
                selected = id;
        }
        return selected;
    }
    public void close() { api.close(); }
}
