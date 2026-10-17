package br.gov.mg.sids.reader;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Recusas em cada etapa devem explicar a configuração, sem expor token ou corpo de erro. */
public final class ReaderBotStartupTest {
    static void check(boolean value,String message){ReaderIntegrationTest.check(value,message);}
    public static void main(String[] args)throws Exception {
        String secret="token-nao-pode-aparecer-no-log";
        String[] stages={"identificar a aplicação", "acessar o fórum", "registrar /cad-reader", "abrir o Gateway"};
        Path root=Files.createTempDirectory("bot-startup-test-");
        try {
            for(int failed=0;failed<4;failed++) {
                final int phase=failed;
                List<String> logs=new ArrayList<>();
                DiscordBotApi api=new DiscordBotApi(secret) {
                    int step;
                    @Override Object request(String method,String path,Object body)throws Exception {
                        if(step++==phase) throw failure(403,Java8.map("code",50001,"message",secret));
                        if(path.equals("/oauth2/applications/@me")) return Java8.map("id","456789");
                        if(path.equals("/channels/234567")) return Java8.map("guild_id","123456","type",15);
                        return Java8.map();
                    }
                };
                CadSummary summary=new CadSummary(id -> new CompletableFuture<>());
                try(DiscordBot bot=new DiscordBot(api,"123456","234567","",summary,logs::add)) {
                    try {
                        bot.start(secret,root);
                        throw new AssertionError("Início deveria ser recusado");
                    } catch(IllegalStateException e) {
                        check(e.getMessage().contains(stages[phase]) && e.getMessage().contains("403")
                            && e.getMessage().contains("50001"),"Diagnóstico da etapa recusada");
                        check(!e.getMessage().contains(secret) && logs.stream().noneMatch(m->m.contains(secret)),"Diagnóstico não expõe token");
                    }
                }
            }
            check(DiscordBotApi.failure(403,Java8.map("code",secret,"message",secret)).getMessage().equals("Discord HTTP 403"),"Erro externo não injeta conteúdo no diagnóstico");
            System.out.println("PASS início bot: 403 por etapa, código Discord, orientação e segredo omitido");
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(root)) {paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}
        }
    }
}
