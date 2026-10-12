package br.gov.mg.sids.reader;

/** Legibilidade dos eventos e conversão segura de rodapés em pendências antigas. */
public final class ReaderMessageFormatTest {
    static void check(boolean value, String message) { ReaderIntegrationTest.check(value, message); }
    public static void main(String[] args) {
        String[] states = {"DESPACHADO", "A CAMINHO", "NO LOCAL", "EM ENCERRAMENTO", "ENCERRADO"};
        String[] icons = {"🚨", "🚒", "📍", "⏳", "✅"};
        String firstColor = null;
        for (int i = 0; i < states.length; i++) {
            CadMonitor.Event event = new CadMonitor.Event(
                "20", "ASL01095", "2026482447100", "20:10", "fp", "ATUALIZAÇÃO • " + states[i],
                "EMPENHO: " + states[i] + "\nHORÁRIO DO ESTADO CAD: 11/10/2026 16:33:30 -03:00", "",
                "2026-10-11T19:35:00Z");
            String payload = event.json(false);
            String color = payload.substring(payload.indexOf("\"color\":"), payload.indexOf(",\"timestamp\":"));
            if (firstColor == null) firstColor = color;
            check(firstColor.equals(color), "Mesma chamada mantém cor entre estados");
            CadMonitor.Event other = new CadMonitor.Event("20", "ASL01095", "2026482447101", "other", "fp",
                event.title, event.body, "", event.observed);
            check(!other.json(false).contains(color + ","), "Chamadas com sufixos distintos podem ter cores distintas");
            check(payload.contains(icons[i] + " CHAMADA 2026482447100 • " + states[i])
                      && payload.contains("\"color\":") && payload.contains("CAD • Recebido")
                      && !payload.contains("CAD-READER/1") && !payload.contains("event_id")
                      && payload.contains("HORÁRIO CAD:") && payload.contains("2026-10-11T19:35:00Z"),
                  "Evento legível com tipo, chamado e horários distintos");
        }
        String freeText = "Texto CAD-READER/1 {\"teste\":true}";
        String oldFooter = "CAD-READER/1 {\"call_id\":\"10\",\"prefix\":\"VTR \\\"A\\\"\"}";
        String legacy = "{\"embeds\":[{\"description\":" + AppFiles.json(freeText)
                        + ",\"footer\":{\"text\":" + AppFiles.json(oldFooter) + "}}]}";
        String converted = DiscordPublisher.readablePayload(legacy);
        check(converted.contains("\"footer\":{\"text\":\"CAD • Recebido\"}")
                  && converted.contains(AppFiles.json(freeText)) && !converted.contains("call_id"),
              "Reenvio remove JSON do rodapé e preserva o texto livre");
        check(DiscordPublisher.readablePayload(converted).equals(converted), "Conversão idempotente");
        System.out.println("PASS mensagens legíveis: tipos, ícones, cores, horários e pendências antigas");
    }
}
