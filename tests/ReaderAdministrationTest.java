package br.gov.mg.sids.reader;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Administração real sobre HTTP: isolamento, perfis legados, importação e concorrência. */
public final class ReaderAdministrationTest {
    static void check(boolean ok,String message){ReaderIntegrationTest.check(ok,message);}
    static final class Browser {
        final HttpClient client;
        final String url,token;
        Browser(CadWebServer web)throws Exception {
            this(web,HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build());
        }
        Browser(CadWebServer web,HttpClient client)throws Exception {
            this.client=client;
            url=web.url();token=BotJson.text(data(get("state")),"token");
        }
        HttpResponse<String> get(String path)throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create(url+"/api/"+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> post(String path,Object body)throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create(url+"/api/"+path)).header("X-CAD-Token",token).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(BotJson.write(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> login(String name,String password,String file)throws Exception {return post("admin/login",Java8.map("name",name,"password",password,"file",file));}
    }
    static Map<String,Object> data(HttpResponse<String> response){return BotJson.object(BotJson.parse(response.body()));}
    static String encoded(Path file)throws Exception{return Base64.getEncoder().encodeToString(Files.readAllBytes(file));}
    static void clear(Path root)throws Exception{try(java.util.stream.Stream<Path> paths=Files.walk(root)){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}}
    public static void main(String[] args)throws Exception {
        String tunnelOrigin=CadWebServer.normalizePublicOrigin("https://cad-exemplo.trycloudflare.com/");
        check(CadWebServer.publicRequestAllowed("cad-exemplo.trycloudflare.com",null,tunnelOrigin),"Túnel permite navegação inicial sem Origin");
        check(CadWebServer.publicRequestAllowed("cad-exemplo.trycloudflare.com",tunnelOrigin,tunnelOrigin),"Túnel permite mesma origem HTTPS");
        check(!CadWebServer.publicRequestAllowed("outro.trycloudflare.com",null,tunnelOrigin),"Não permite outro túnel");
        check(!CadWebServer.publicRequestAllowed("cad-exemplo.trycloudflare.com","https://externo.example",tunnelOrigin),"Recusa origem externa");
        check(!CadWebServer.publicRequestAllowed("cad-exemplo.trycloudflare.com","null",tunnelOrigin),"Recusa origem opaca");
        check(!CadWebServer.publicRequestAllowed("cad-exemplo.trycloudflare.com",null,""),"Túnel exige autorização explícita");
        try {CadWebServer.normalizePublicOrigin("https://cad.example/caminho");throw new AssertionError("Caminho deve ser recusado");}
        catch(IllegalArgumentException expected){}

        Path root=Files.createTempDirectory("cad-admin-test-");
        try {
            try(CadApplication application=new CadApplication(root);CadWebServer web=new CadWebServer(application,root,0)) {
                Browser a=new Browser(web),b=new Browser(web),anonymous=new Browser(web);
                for(String path:Java8.list("admin/status","admin/export","admin/history","events","events?after=0","communication"))
                    check(anonymous.get(path).statusCode()==401,"Leitura protegida: "+path);
                for(String path:Java8.list("admin/config","admin/connect","admin/monitor","admin/bot/start","admin/publications/clear","settings","routing/connect"))
                    check(anonymous.post(path,Java8.map()).statusCode()==401,"Escrita protegida mesmo com token público: "+path);
                check(!anonymous.get("state").body().contains("password"),"Estado público não contém credenciais");
                check(a.client.send(HttpRequest.newBuilder(URI.create(web.url()+"/admin")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200,"Entrada /admin servida pelo mesmo JAR");
                check(a.login("primeiro","senha-primeiro","").statusCode()==200,"Primeiro acesso cria perfil sem cadastro prévio");
                check(b.login("outro","senha-qualquer","").statusCode()==400,"Outro perfil não assume configuração ativa");Thread.sleep(1050);
                HttpResponse<String> login=b.login("primeiro","senha-primeiro","");check(login.statusCode()==200,"Segunda sessão com mesmas credenciais");
                String cookie=login.headers().firstValue("Set-Cookie").orElse("");
                check(cookie.contains("HttpOnly")&&cookie.contains("SameSite=Strict")&&!login.body().contains("senha-primeiro"),"Cookie protegido e senha ausente na resposta");
                HttpResponse<String> status=a.get("admin/status");check(status.statusCode()==200,"Estado administrativo disponível: "+status.body());
                Map<String,Object> initial=data(status);Map<String,Object> config=BotJson.object(initial.get("config"));
                config.put("user","USUARIO");config.put("password","senha-cad-protegida");config.put("saveCadPassword","true");config.put("webhook","https://discord.com/api/webhooks/123/ficticio");config.put("botToken","bot-token-ficticio");
                HttpResponse<String> saved=a.post("admin/config",Java8.map("revision",initial.get("revision"),"config",config));
                check(saved.statusCode()==200,"Configuração e segredos salvos");
                config.put("host","nao-deve-ser-salvo");
                check(b.post("admin/config",Java8.map("revision",initial.get("revision"),"config",config)).statusCode()==409,"Edição antiga não sobrescreve configuração nova");
                check(!BotJson.write(application.configuration()).contains("nao-deve-ser-salvo"),"Conflito preserva configuração aplicada");
                String exported=BotJson.text(data(a.get("admin/export")),"file");
                String raw=new String(Base64.getDecoder().decode(exported),StandardCharsets.UTF_8);
                check(raw.contains("password.encrypted")&&!raw.contains("senha-cad-protegida")&&!raw.contains("bot-token-ficticio"),"Exportação preserva criptografia dos segredos");
                check(!Files.readString(root.resolve("admin-auth/primeiro.properties")).contains("senha-primeiro"),"Autenticação persiste hash, não senha");
                check(new ProfileStore(root.resolve("perfis/primeiro.properties")).load("senha-primeiro".toCharArray()).getProperty("password").equals("senha-cad-protegida"),"Arquivo continua compatível com ProfileStore");
                check(!anonymous.get("state").body().contains("senha-cad-protegida")&&!anonymous.get("state").body().contains("bot-token-ficticio"),"Credenciais privadas não vazam para acompanhamento");
                Properties importedConfig=CadApplication.defaults();importedConfig.setProperty("user","IMPORTADO");
                importedConfig.setProperty("botToken","token-importado-ficticio");
                Path importPath=root.resolve("importar.properties");
                new ProfileStore(importPath).save(importedConfig,"https://discord.com/api/webhooks/123/importado","senha-cad-importada","senha-do-arquivo".toCharArray());
                String importFile=Base64.getEncoder().encodeToString(Files.readAllBytes(importPath));
                Object importRevision=data(a.get("admin/status")).get("revision");
                check(anonymous.post("admin/import",Java8.map("revision",importRevision,"file",importFile,"password","senha-do-arquivo")).statusCode()==401,"Importação exige autenticação");
                check(a.post("admin/import",Java8.map("revision",importRevision,"file",importFile,"password","senha-incorreta")).statusCode()!=200,"Senha incorreta recusa importação");
                check(!application.configuration().getProperty("user").equals("IMPORTADO"),"Falha preserva configuração");
                HttpResponse<String> importedResponse=a.post("admin/import",Java8.map("revision",importRevision,"file",importFile,"password","senha-do-arquivo"));
                check(importedResponse.statusCode()==200,"Importação no perfil ativo: "+importedResponse.body());
                check(BotJson.text(data(importedResponse),"profile").equals("primeiro"),"Importação mantém identidade do perfil");
                Properties persistedImport=new ProfileStore(root.resolve("perfis/primeiro.properties")).load("senha-primeiro".toCharArray());
                check(persistedImport.getProperty("user").equals("IMPORTADO")&&persistedImport.getProperty("password").equals("senha-cad-importada"),"Importação salva com a senha do perfil ativo");
                check(persistedImport.getProperty("botToken").equals("token-importado-ficticio"),"Importa token protegido com outra senha");
                check(a.post("admin/import",Java8.map("revision",importRevision,"file",importFile,"password","senha-do-arquivo")).statusCode()==409,"Importação antiga não sobrescreve revisão nova");
                check(a.post("admin/import",Java8.map("revision",data(a.get("admin/status")).get("revision"),"file",exported,"password","senha-primeiro")).statusCode()==200,"Restaura configuração exportada no perfil ativo");
                check(a.post("admin/logout",Java8.map()).statusCode()==200&&a.get("events").statusCode()==401,"Sair invalida apenas a sessão atual");
                check(b.get("events").statusCode()==200,"Outra sessão permanece autenticada");
                check(a.login("terceiro","outra-senha","").statusCode()==400,"Logout não libera perfil ativo");
            }
            // O mesmo navegador compartilha cookies entre portas do mesmo host.
            Path secondRoot=root.resolve("segunda-instancia");
            try(CadApplication first=new CadApplication(root);CadApplication second=new CadApplication(secondRoot);
                CadWebServer firstWeb=new CadWebServer(first,root,0);CadWebServer secondWeb=new CadWebServer(second,secondRoot,0)) {
                HttpClient shared=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
                Browser a=new Browser(firstWeb,shared),b=new Browser(secondWeb,shared);
                HttpResponse<String> firstLogin=a.login("primeiro","senha-primeiro","");
                HttpResponse<String> secondLogin=b.login("segundo","senha-segundo","");
                check(firstLogin.statusCode()==200&&secondLogin.statusCode()==200,"Login nas duas portas com o mesmo navegador");
                check(!firstLogin.headers().firstValue("Set-Cookie").get().split("=",2)[0].equals(secondLogin.headers().firstValue("Set-Cookie").get().split("=",2)[0]),"Cookies distintos por porta real, inclusive porta dinâmica");
                check(a.get("admin/status").statusCode()==200&&b.get("admin/status").statusCode()==200,"Login na segunda porta preserva a primeira sessão");
                check(b.post("admin/logout",Java8.map()).statusCode()==200&&b.get("admin/status").statusCode()==401,"Logout encerra sessão da segunda porta");
                check(a.get("admin/status").statusCode()==200,"Logout em outra porta preserva a primeira sessão");
            }
            // Reinício libera escolha, mas continua validando credenciais dos perfis anteriores.
            try(CadApplication application=new CadApplication(root);CadWebServer web=new CadWebServer(application,root,0)) {
                Browser a=new Browser(web);check(a.login("primeiro","senha-errada","").statusCode()==400,"Senha incorreta recusada após reinício");Thread.sleep(1050);
                check(a.login("primeiro","senha-primeiro","").statusCode()==200,"Perfil recuperado após reinício");
                check(application.configuration().getProperty("password").equals("senha-cad-protegida"),"Segredos anteriores recuperados");
            }
            String imported=encoded(root.resolve("perfis/primeiro.properties"));
            try(CadApplication application=new CadApplication(root);CadWebServer web=new CadWebServer(application,root,0)) {
                Browser a=new Browser(web);
                check(a.login("copia","senha-errada",imported).statusCode()==400,"Senha do novo perfil deve recuperar dados importados");
                check(!Files.exists(root.resolve("perfis/copia.properties"))&&application.revision()==0,"Importação recusada não cria perfil nem muda configuração");Thread.sleep(1050);
                check(a.login("copia","senha-primeiro",imported).statusCode()==200,"Importação cria perfil novo com senha do arquivo");
                check(BotJson.text(data(a.get("admin/status")),"profile").equals("copia")&&Files.exists(root.resolve("perfis/primeiro.properties")),"Perfil novo independente do original");
            }
            Properties plain=CadApplication.defaults();plain.setProperty("user","SEM_SEGREDOS");Path legacy=root.resolve("legado.properties");new ProfileStore(legacy).save(plain,"",new char[0]);
            try(CadApplication application=new CadApplication(root);CadWebServer web=new CadWebServer(application,root,0)) {
                Browser a=new Browser(web);check(a.login("sem-segredos","senha-livre",encoded(legacy)).statusCode()==200,"Arquivo sem segredos aceita senha escolhida sem alterar formato legado");
            }
            // Um perfil legado protegido também é recuperável sem conversão prévia.
            Properties old=CadApplication.defaults();old.setProperty("user","LEGADO");new ProfileStore(root.resolve("perfis/legado.properties")).save(old,"","cad-legado","senha-legada".toCharArray());
            try(CadApplication application=new CadApplication(root);CadWebServer web=new CadWebServer(application,root,0)) {
                Browser a=new Browser(web);check(a.login("legado","senha-legada","").statusCode()==200&&application.configuration().getProperty("password").equals("cad-legado"),"Abre perfil criptografado da interface Java");
            }
            System.out.println("PASS administração HTTP: autenticação, múltiplas sessões, isolamento, concorrência, reinício, perfis legados e importação criptografada");
        } finally {clear(root);}
    }
}
