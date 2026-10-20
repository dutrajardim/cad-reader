package br.gov.mg.sids.reader;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Usa o broker local para provar que CAD e monitoramento funcionam sem janela ou HTTP. */
public final class ReaderApplicationSessionTest {
    public static void main(String[] args)throws Exception {
        ReaderOptions options=ReaderOptions.parse(args);
        Path root=Files.createTempDirectory("cad-service-session-");
        try(CadApplication app=new CadApplication(root)) {
            app.listeners(System.out::println,event->{});
            Properties config=CadApplication.defaults();config.setProperty("host",options.host);
            config.setProperty("port",Integer.toString(options.port));config.setProperty("user",options.username);
            config.setProperty("password",options.password);config.setProperty("insecure",Boolean.toString(options.insecure));
            app.configure(config);app.connect();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(BotJson.array(app.status().get("callFilters")).isEmpty()&&System.nanoTime()<deadline)Thread.sleep(10);
            if(BotJson.array(app.status().get("callFilters")).size()!=1||BotJson.array(app.status().get("resourceFilters")).size()!=1)
                throw new AssertionError("Catálogo não chegou ao serviço");
            try{app.configure(config);throw new AssertionError("Edição durante conexão aceita");}catch(IllegalArgumentException expected){}
            app.monitor("12","34",false);
            try{app.monitor("12","34",false);throw new AssertionError("Monitoramento duplicado aceito");}catch(IllegalArgumentException expected){}
            while(Boolean.TRUE.equals(app.status().get("connected"))&&System.nanoTime()<deadline)Thread.sleep(10);
            if(Boolean.TRUE.equals(app.status().get("connected")))throw new AssertionError("Sessão não encerrou");
            Map<String,Object> state=app.operational.snapshot();
            if(BotJson.array(state.get("calls")).size()!=1||BotJson.array(state.get("resources")).size()!=1)
                throw new AssertionError("Estado operacional não foi mantido após desconexão: "+BotJson.write(state));
            if(Boolean.TRUE.equals(state.get("ready"))||Boolean.TRUE.equals(app.status().get("monitoring")))
                throw new AssertionError("Desconexão não encerrou monitoramento");
            if(!Files.isRegularFile(root.resolve("historico.csv")))throw new AssertionError("Histórico não foi gravado sem Swing");
            app.configure(config);
            System.out.println("PASS serviço CAD sem Swing: catálogo, monitoramento, histórico, estado e desconexão");
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(root)) {
                for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);
            }
        }
    }
}
