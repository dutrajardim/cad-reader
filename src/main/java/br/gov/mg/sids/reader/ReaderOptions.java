package br.gov.mg.sids.reader;

import java.util.*;

/** Opções da linha de comando, separadas da comunicação para facilitar a leitura. */
public final class ReaderOptions {
    final String host, domain, username, password, resource;
    final String chamadaFilterId, recursoFilterId;
    final String clientVersion, clientRevision, brokerMinimumRevision;
    final int port, requestTimeoutSeconds;
    final boolean insecure, debug;

    /** Converte opções já reconhecidas e valida números e credenciais. */
    private ReaderOptions(Map<String, String> values) {
        domain = values.getOrDefault("to", "cad.sids.mg.gov.br");
        host = values.getOrDefault("host", domain);
        // A tela original aplica upperCaseOnly ao usuário; nunca à senha.
        username = values.getOrDefault("user", "").trim().toUpperCase(Locale.ROOT);
        password = values.getOrDefault("password", System.getenv().getOrDefault("CAD_PASSWORD", ""));
        resource = values.getOrDefault("resource", "reader");
        // Valores comprovados em sids/build/ClienteCadBuildInformation.java.
        clientVersion = values.getOrDefault("client-version", "4.4.55");
        clientRevision = values.getOrDefault("client-revision", "279");
        // Valor verificado no ClienteCADApplication.properties do cad.jar original.
        brokerMinimumRevision = values.getOrDefault("broker-minimum-revision", "279");
        positive(clientRevision, "--client-revision");
        positive(brokerMinimumRevision, "--broker-minimum-revision");
        if (clientVersion.trim().isEmpty())
            throw new IllegalArgumentException("--client-version não pode ser vazio");
        port = positive(values.getOrDefault("port", "5222"), "--port");
        if (port > 65535)
            throw new IllegalArgumentException("--port deve ser até 65535");
        requestTimeoutSeconds = positive(values.getOrDefault("request-timeout", "45"), "--request-timeout");
        chamadaFilterId = filterId(values.get("chamada-filter-id"));
        recursoFilterId = filterId(values.get("recurso-filter-id"));
        insecure = values.containsKey("insecure");
        debug = values.containsKey("debug");
        if (username.trim().isEmpty() || password.isEmpty())
            throw new IllegalArgumentException("Informe --user e CAD_PASSWORD (ou --password)");
        if (host.trim().isEmpty() || domain.trim().isEmpty() || resource.trim().isEmpty())
            throw new IllegalArgumentException("Host, domínio e recurso não podem ser vazios");
    }

    /** Cria opções para a interface sem montar comandos ou expor a senha em argumentos. */
    static ReaderOptions from(Map<String, String> values) {
        return new ReaderOptions(values);
    }

    /** Indica se devemos selecionar filtros após validar os pré-requisitos. */
    boolean hasFilters() {
        return chamadaFilterId != null || recursoFilterId != null;
    }

    /** Aceita apenas IDs numéricos positivos; descrição da unidade não é um ID. */
    private static String filterId(String value) {
        if (value == null)
            return null;
        if (Long.parseLong(value) <= 0)
            throw new IllegalArgumentException("ID de filtro deve ser positivo");
        return value;
    }

    /** Valida porta e prazo em segundos, com mensagem indicando a opção inválida. */
    private static int positive(String value, String option) {
        int parsed = Integer.parseInt(value);
        if (parsed <= 0)
            throw new IllegalArgumentException(option + " deve ser positivo");
        return parsed;
    }

    /** Lê argumentos sem imprimir segredos; --help retorna null sem abrir conexão. */
    public static ReaderOptions parse(String[] args) {
        if (Arrays.asList(args).contains("--help")) {
            System.out.println(
                "Uso: java -jar target/java-cad-reader-1.0.0.jar [--gui | --user USUARIO opções]\n"
                + "Senha: variável CAD_PASSWORD ou --password SENHA\n"
                + "--host HOST              Endereço TCP; padrão: domínio de --to\n"
                + "--port PORTA             Porta TCP; padrão: 5222 (informe a porta real do CAD)\n"
                + "--to DOMINIO             Domínio XMPP; padrão: cad.sids.mg.gov.br\n"
                + "--client-version VERSAO  Versão original; padrão local: 4.4.55\n"
                + "--client-revision NUM    Revisão original; padrão local: 279\n"
                + "--broker-minimum-revision NUM  Revisão mínima do broker; padrão local: 279\n"
                + "--resource NOME          Recurso XMPP; padrão: reader\n"
                + "--chamada-filter-id ID   Filtro de ocorrências no servidor\n"
                + "--recurso-filter-id ID   Filtro de recursos no servidor\n"
                + "--request-timeout SEG    Prazo por pedido CAD; padrão: 45 segundos\n"
                + "--debug                  Exibe XML CAD e pings\n"
                + "--insecure               Diagnóstico: desativa validação TLS\n"
                + "Sem IDs: solicita o catálogo de filtros; com IDs: seleciona e lê estados.");
            return null;
        }
        Set<String> valued = Java8.set("host", "port", "to", "user", "username", "password", "resource",
            "chamada-filter-id", "recurso-filter-id", "request-timeout", "client-version",
            "client-revision", "broker-minimum-revision");
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--"))
                throw new IllegalArgumentException("Argumento inesperado (consulte --help)");
            String key = arg.substring(2);
            if (key.equals("debug") || key.equals("insecure"))
                values.put(key, "true");
            else if (valued.contains(key)) {
                if (++i >= args.length)
                    throw new IllegalArgumentException("Falta valor para --" + key);
                values.put(key.equals("username") ? "user" : key, args[i]);
            } else if (key.equals("filter") || key.equals("webhook")) {
                throw new IllegalArgumentException("--" + key
                    + (" foi removido. Use IDs de filtros no terminal; configure Discord na interface "
                       + "gráfica."));
            } else
                throw new IllegalArgumentException("Opção desconhecida (consulte --help)");
        }
        return new ReaderOptions(values);
    }
}
