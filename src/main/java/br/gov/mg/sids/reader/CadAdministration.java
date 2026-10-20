package br.gov.mg.sids.reader;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Perfil ativo por execução. Credenciais persistentes separadas do formato portátil legado. */
final class CadAdministration implements AutoCloseable {
    private final CadApplication application;
    private final Path root;
    private String active = "";
    private char[] password = new char[0];
    private final Map<String,Long> sessions = new HashMap<>();
    private long nextAttempt;
    CadAdministration(CadApplication application, Path root) { this.application=application; this.root=root; }
    synchronized boolean authenticated(String session) {
        Long expires=sessions.get(session);
        if(expires==null)return false;
        if(expires<System.currentTimeMillis()){sessions.remove(session);return false;}
        return true;
    }
    synchronized Map<String,Object> session(String session) {
        return Java8.map("authenticated", authenticated(session), "activeProfile", active);
    }
    synchronized String login(Map<String,Object> body) throws Exception {
        long now=System.currentTimeMillis();
        if(now<nextAttempt)throw new IllegalArgumentException("Aguarde alguns segundos antes de tentar novamente.");
        nextAttempt=now+1000;
        String name=BotJson.text(body,"name").trim(), secret=BotJson.text(body,"password");
        if(!name.matches("[A-Za-z0-9_-]{1,60}"))throw new IllegalArgumentException("Nome: use letras sem acento, números, _ ou - (até 60).");
        if(secret.length()<8||secret.length()>1024)throw new IllegalArgumentException("Use uma senha de 8 a 1024 caracteres.");
        char[] supplied=secret.toCharArray();
        try {
            if(!active.isEmpty()) {
                if(!active.equals(name)||!MessageDigest.isEqual(new String(password).getBytes(StandardCharsets.UTF_8),secret.getBytes(StandardCharsets.UTF_8)))
                    throw new IllegalArgumentException("Use o nome e a senha do perfil ativo.");
                if(!BotJson.text(body,"file").isEmpty())throw new IllegalArgumentException("Já existe um perfil ativo. Reinicie o JAR para importar outro.");
            } else {
                Path profile=profile(name), auth=root.resolve("admin-auth").resolve(name+".properties");
                String imported=BotJson.text(body,"file");
                Properties config;
                if(!imported.isEmpty()) {
                    if(Files.exists(profile)||Files.exists(auth))throw new IllegalArgumentException("Para importar, escolha um nome de perfil novo.");
                    byte[] bytes;
                    try { bytes=Base64.getDecoder().decode(imported); }
                    catch(IllegalArgumentException e){throw new IllegalArgumentException("Arquivo de perfil inválido.");}
                    if(bytes.length>131072)throw new IllegalArgumentException("Arquivo de perfil muito grande.");
                    Path temp=Files.createTempFile("cad-import-", ".properties");
                    try {
                        Files.write(temp,bytes);
                        config=new ProfileStore(temp).load(supplied);
                        if(!config.containsKey("host")||!config.containsKey("user"))throw new IllegalArgumentException("Arquivo não contém um perfil CAD Reader válido.");
                    } finally { Files.deleteIfExists(temp); Arrays.fill(bytes,(byte)0); }
                } else if(Files.exists(profile)) {
                    if(Files.exists(auth) && !verify(AppFiles.load(auth),supplied))throw new IllegalArgumentException("Nome ou senha incorretos.");
                    config=new ProfileStore(profile).load(supplied);
                } else {
                    if(Files.exists(auth))throw new IllegalArgumentException("Perfil salvo indisponível.");
                    config=CadApplication.defaults();config.setProperty("user", "");
                }
                if(!config.getProperty("password", "").isEmpty())config.setProperty("saveCadPassword","true");
                // Não substitui a configuração que a interface desktop já colocou em execução.
                synchronized(application) {
                    Map<String,Object> state=application.status();
                    if(Boolean.TRUE.equals(state.get("connected"))||Boolean.TRUE.equals(state.get("botRunning")))
                        throw new IllegalArgumentException("Desconecte a sessão desktop antes de ativar um perfil na administração Web.");
                    if(!Files.exists(profile))save(profile,config,supplied);
                    if(!Files.exists(auth))AppFiles.save(auth,credentials(supplied));
                    application.configure(config);
                    active=name;password=supplied.clone();
                }
            }
            sessions.entrySet().removeIf(e->e.getValue()<now);
            if(sessions.size()>=100)throw new IllegalArgumentException("Limite de sessões administrativas atingido.");
            String session=UUID.randomUUID().toString()+UUID.randomUUID().toString();
            sessions.put(session,now+12*60*60*1000L);nextAttempt=0;return session;
        } finally { Arrays.fill(supplied,'\0'); }
    }
    synchronized void logout(String session) { sessions.remove(session); }
    synchronized Map<String,Object> status() { Map<String,Object> result=new LinkedHashMap<>(application.status());result.put("profile",active);return result; }
    synchronized Map<String,Object> configure(Map<String,Object> body) throws Exception {
        Object revision=body.get("revision");
        if(!(revision instanceof Number)||((Number)revision).longValue()!=application.revision())
            throw new Conflict("A configuração mudou em outra sessão. Recarregue antes de salvar.");
        Properties next=new Properties();Map<String,Object> input=BotJson.object(body.get("config"));
        for(String field:Java8.list("host","port","user","password","webhook","botToken","botGuild","botForum","botRole","calls","resources","insecure","publish","saveCadPassword")) {
            Object value=input.get(field);
            if(value!=null)next.setProperty(field,String.valueOf(value));
        }
        synchronized(application) {
            if(((Number)revision).longValue()!=application.revision())throw new Conflict("A configuração mudou em outra sessão. Recarregue antes de salvar.");
            Map<String,Object> state=application.status();
            if(Boolean.TRUE.equals(state.get("connected"))||Boolean.TRUE.equals(state.get("botRunning")))
                throw new IllegalArgumentException("Desconecte do CAD e pare o bot antes de alterar a configuração.");
            save(profile(active),next,password);
            application.configure(next);
        }
        return status();
    }
    synchronized void save() throws Exception { save(profile(active),application.configuration(),password); }
    synchronized String export() throws Exception { save();return Base64.getEncoder().encodeToString(Files.readAllBytes(profile(active))); }
    synchronized void action(String action,Map<String,Object> body) throws Exception {
        if(action.equals("connect")||action.equals("monitor")) {
            synchronized(application) {
                Object expected=body.get("revision");
                if(!(expected instanceof Number)||((Number)expected).longValue()!=application.revision())
                    throw new Conflict("A configuração mudou em outra sessão. Recarregue antes de continuar.");
                if(action.equals("connect"))application.connect();
                else application.monitor(BotJson.text(body,"calls"),BotJson.text(body,"resources"),Boolean.TRUE.equals(body.get("publish")));
            }
            return;
        }
        switch(action) {
        case "disconnect": application.disconnect();break;
        case "bot/start": application.startBot();break;
        case "bot/stop": application.stopBot();break;
        case "publications/retry": application.retry();break;
        case "publications/clear": application.clearPending();break;
        case "profile/save": save();break;
        default:throw new IllegalArgumentException("Operação administrativa desconhecida.");
        }
    }
    private Path profile(String name) { return root.resolve("perfis").resolve(name+".properties"); }
    private static void save(Path path,Properties config,char[] key) throws Exception {
        new ProfileStore(path).save(config,config.getProperty("webhook",""),
            Boolean.parseBoolean(config.getProperty("saveCadPassword","false"))?config.getProperty("password",""):"",key);
    }
    private static Properties credentials(char[] password) throws Exception {
        byte[] salt=new byte[16];new SecureRandom().nextBytes(salt);
        Properties data=new Properties();data.setProperty("salt",Base64.getEncoder().encodeToString(salt));
        data.setProperty("hash",Base64.getEncoder().encodeToString(derive(password,salt)));return data;
    }
    private static boolean verify(Properties data,char[] password) throws Exception {
        return MessageDigest.isEqual(Base64.getDecoder().decode(data.getProperty("hash","")),derive(password,Base64.getDecoder().decode(data.getProperty("salt",""))));
    }
    private static byte[] derive(char[] password,byte[] salt) throws Exception {
        PBEKeySpec spec=new PBEKeySpec(password,salt,210000,256);
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}
        finally{spec.clearPassword();}
    }
    public synchronized void close() { sessions.clear();Arrays.fill(password,'\0');password=new char[0]; }
    static final class Conflict extends IllegalArgumentException { Conflict(String message){super(message);} }
}
