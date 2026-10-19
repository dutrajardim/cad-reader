# Verificação

## Compilar

```sh
npm ci --prefix frontend
npm run build --prefix frontend
mvn package
```

O build Maven compila e empacota; não executa os testes Java desta pasta automaticamente. Para executar a suíte sem interface, com Python 3 e JDK no PATH, na raiz do repositório:

```sh
python tests/run_tests.py
```

No Linux, use `python3` se necessário. O script compila todos os testes em uma pasta temporária e executa doze cenários sem interface, com o separador de classpath apropriado para Windows/Linux. Falhas retornam código diferente de zero. Não usa credenciais reais nem acessa o CAD/Discord.

O GitHub Actions compila em JDK 11 e executa essa suíte e `java -jar ... --help` em Java 11 no Windows e no Linux, além do broker simulado no Linux. Os testes com janelas e o broker não fazem parte de `run_tests.py`. Os comandos detalhados abaixo usam shell Linux; no Windows, o separador do classpath é `;`, em vez de `:`.

## Formatação, perfis e publicador

Na pasta `java-cad-reader`:

```sh
mkdir -p /tmp/cad-reader-tests
javac -encoding UTF-8 -cp target/java-cad-reader-1.0.0.jar -d /tmp/cad-reader-tests tests/ReaderIntegrationTest.java
java -Djava.awt.headless=true -cp target/java-cad-reader-1.0.0.jar:/tmp/cad-reader-tests br.gov.mg.sids.reader.ReaderIntegrationTest
```

Usa diretório temporário e transporte Discord falso. Verifica associação entre chamada e empenho, estados no local/encerrado, links, deduplicação na sessão e persistida, criação/reutilização de tópicos, mesmo prefixo com novo ID de recurso, prévia legível, bloqueio de publicadores locais, HTTP 429, retomada da fila, proteção do CSV e criptografia/senha incorreta. Verifica ainda que o buffer devolve o bloco disponível sem tentar outra leitura quando `available()` é impreciso. Também verifica busca por nome/ID sem acentos, restauração do filtro salvo, lista sem resultados, associação incremental e isolamento do cache em relação ao documento XML do snapshot. Também verifica snapshots amplos, eventos de unidades/tipos fora do filtro, saída/retorno ao filtro e isolamento de pendências de outro escopo. Verifica também recriação após erro explícito de tópico inexistente, ausência de recriação para webhook inválido/falha ambígua, diagnóstico sem vazamento de segredo, contagem de pendências e retomada automática ordenada após HTTP 429 sem antecipação por reenvio manual. Não publica no Discord.

## Atualizações e histórico da ocorrência

```sh
javac -encoding UTF-8 -cp target/java-cad-reader-1.0.0.jar:/tmp/cad-reader-tests -d /tmp/cad-reader-tests tests/ReaderUpdatesTest.java
java -Djava.awt.headless=true -cp target/java-cad-reader-1.0.0.jar:/tmp/cad-reader-tests br.gov.mg.sids.reader.ReaderUpdatesTest
```

Execute após compilar `ReaderIntegrationTest`. Verifica a barreira entre carga inicial e atualizações (incluindo mensagens intercaladas), ausência de enfileiramento da carga, isolamento de pendências iniciais antigas, histórico anterior ao novo empenho, novas páginas sem repetição, alterações somente de histórico, sinal explícito com quantidade inalterada, ausência de consultas por endereço/classificação/estado, consulta compartilhada entre viaturas, chamada sem vínculo ativo, divisão de texto longo, resposta tardia após encerramento e armazenamento/recuperação/remoção opcional da senha CAD cifrada. Usa somente arquivos temporários e transporte falso.

## Agrupamento de publicações

```sh
javac -encoding UTF-8 -cp target/java-cad-reader-1.0.0.jar:/tmp/cad-reader-tests -d /tmp/cad-reader-tests tests/ReaderBatchTest.java
java -Djava.awt.headless=true -cp target/java-cad-reader-1.0.0.jar:/tmp/cad-reader-tests br.gov.mg.sids.reader.ReaderBatchTest
```

Execute após compilar `ReaderIntegrationTest`. Usa transporte Discord falso e diretórios temporários. Verifica a janela real de três segundos (sem renovação ou antecipação manual), três eventos em uma requisição, ordem e horários dos eventos, isolamento entre viaturas/chamadas, reutilização de tópico, limites de dez embeds/6.000 caracteres, divisão sem cortar eventos, persistência durante a espera, fechamento/reabertura, compatibilidade da fila anterior, preservação de texto livre contendo “SITUAÇÃO ATUAL”, confirmação dos fingerprints individuais, nova sequência real igual a um lote anterior, HTTP 429 com payload congelado e retomada após falha ambígua. Não faz requisições externas.

## Protocolo CAD com broker temporário

```sh
python3 tests/mock_broker.py
```

Requer Python 3, JDK (java/javac) e OpenSSL no PATH. O próprio script cria o servidor temporário em `127.0.0.1` e escolhe uma porta livre. Não exige servidor de teste previamente instalado, não acessa o CAD real e usa credenciais fictícias. Python serve apenas como simulador; a aplicação executada é o JAR Java.

Verifica fragmentação XML/UTF-8, zlib, SASL, bind, JID do servidor, IDs/timestamps, pré-requisitos, catálogo com itens `i`, seleção de filtros antecipada e na mesma sessão após receber o catálogo (fluxo da interface), snapshots, atualizações, consulta de histórico paginada na mesma sessão, classificação sem nova consulta e sinal explícito de histórico, recusa, timeout apesar de pings e TLS/zlib antes de autenticação. Não comprova permissões nem comportamento de todos os servidores reais.

## Atualização da tabela em lotes

O teste abaixo abre uma janela com 600 eventos fictícios e verifica o tamanho dos lotes e a preservação da seleção. Não conecta a serviços e não grava histórico operacional. Requer sessão gráfica; em Linux com Xvfb instalado pode ser executado em tela virtual:

```sh
javac -encoding UTF-8 -cp target/java-cad-reader-1.0.0.jar -d /tmp/cad-reader-tests tests/ReaderWindowTest.java
xvfb-run -a java -cp target/java-cad-reader-1.0.0.jar:/tmp/cad-reader-tests br.gov.mg.sids.reader.ReaderWindowTest
```

Para usar a tela normal, omita `xvfb-run -a`.

## Perfis na interface

```sh
javac -encoding UTF-8 -cp target/java-cad-reader-1.0.0.jar -d /tmp/cad-reader-tests tests/ReaderProfileWindowTest.java
xvfb-run -a java -cp target/java-cad-reader-1.0.0.jar:/tmp/cad-reader-tests br.gov.mg.sids.reader.ReaderProfileWindowTest
```

Usa uma pasta pessoal temporária, perfil fictício e tela virtual. Verifica seleção de perfil existente, recuperação dos segredos, abertura sem conexão automática, gravação assíncrona sem senha CAD opcional, preservação dos filtros antes de conectar e remoção confirmada sem apagar histórico. Não acessa serviços externos.

## Interface e instalação real

Abra `java -jar target/java-cad-reader-1.0.0.jar`, confira os campos, redimensionamento, abertura/gravação de perfil, senha de proteção incorreta e fechamento da janela.

Com uma conta CAD autorizada, conecte sem publicação, escolha os filtros e compare os estados com o dashboard original. Verifique chamadas recebidas depois dos recursos e mudanças reais de empenho. A aba de conexão informa recusas e prazos vencidos.

Para validar Discord, use um fórum de teste administrado pela unidade e habilite publicação conscientemente. Confira que a carga inicial não publica, um novo empenho publica com histórico, mudanças de histórico chegam ao tópico, tópico único por prefixo de viatura, atualização com chamada/endereço/horário, reutilização após reconectar e o comportamento de pendências. Esses passos enviam mensagens reais; os testes automatizados não os executam.


## Envolvidos no despacho

Após compilar `ReaderIntegrationTest`:

```sh
javac -encoding UTF-8 -cp target/java-cad-reader-1.0.0.jar:/tmp/cad-reader-tests -d /tmp/cad-reader-tests tests/ReaderParticipantsTest.java
java -Djava.awt.headless=true -cp target/java-cad-reader-1.0.0.jar:/tmp/cad-reader-tests br.gov.mg.sids.reader.ReaderParticipantsTest
```

Usa XML sintético e um transporte de saída em memória; não conecta ao CAD ou Discord. Verifica consulta apenas após novo empenho, compartilhamento entre viaturas, cache, lista resumida de múltiplos envolvidos, normalização de telefone/WhatsApp sem inventar DDD, ausência de documentos na publicação, deduplicação, divisão de listas grandes, encerramento antes da resposta e paginação correlacionada. Exercita também a fila serial junto do histórico, resposta vazia e recusa de permissão sem interromper consultas seguintes. A permissão e os campos efetivamente preenchidos precisam ser confirmados numa sessão autorizada com o servidor real.

## Limpar pendentes

Após compilar `ReaderIntegrationTest`:

```sh
javac -encoding UTF-8 -cp target/java-cad-reader-1.0.0.jar:/tmp/cad-reader-tests -d /tmp/cad-reader-tests tests/ReaderClearPendingTest.java
java -Djava.awt.headless=true -cp target/java-cad-reader-1.0.0.jar:/tmp/cad-reader-tests br.gov.mg.sids.reader.ReaderClearPendingTest
```

Transporte falso e pasta temporária. Verifica descarte persistido após reabertura, ausência de reenvio dos descartados, publicação de novas atualizações, limpeza durante HTTP em andamento, preservação de eventos posteriores ao clique e do prazo HTTP 429, além de diagnóstico de timeout sem exposição de mensagens/segredos das exceções.

## Legibilidade das mensagens

Após compilar `ReaderIntegrationTest`:

```sh
javac -encoding UTF-8 -cp target/java-cad-reader-1.0.0.jar:/tmp/cad-reader-tests -d /tmp/cad-reader-tests tests/ReaderMessageFormatTest.java
java -Djava.awt.headless=true -cp target/java-cad-reader-1.0.0.jar:/tmp/cad-reader-tests br.gov.mg.sids.reader.ReaderMessageFormatTest
```

Verifica títulos por estado com ícones e cor estável entre eventos de uma chamada, horário CAD separado do recebimento e ausência de JSON técnico nas novas mensagens. Verifica também conversão idempotente de rodapés técnicos em pendências antigas, preservando textos livres com conteúdo semelhante.

## Transporte HTTP do Discord

Após compilar `ReaderIntegrationTest` e `ReaderClearPendingTest`:

```sh
javac -encoding UTF-8 -cp target/java-cad-reader-1.0.0.jar:/tmp/cad-reader-tests -d /tmp/cad-reader-tests tests/ReaderHttpTransportTest.java
java -Djava.awt.headless=true -cp target/java-cad-reader-1.0.0.jar:/tmp/cad-reader-tests br.gov.mg.sids.reader.ReaderHttpTransportTest
```

Cria um servidor temporário apenas em localhost, sem CAD, Discord ou credenciais reais. Verifica conexões distintas entre envios, `Connection: close`, método POST, tamanho UTF-8, ausência de redirecionamento, preservação do corpo/cabeçalho HTTP 429 e uma interrupção após receber o POST sem repetição automática. Também usa conexões falsas para falhas nas etapas TCP/TLS, cabeçalhos e leitura de resposta, verificando liberação de recursos, diagnóstico seguro e pendências preservadas. `--no-sockets` executa somente a parte de conexões falsas quando sockets locais não estão disponíveis. Não reproduz as condições da rede de produção, nem prova a causa de uma IOException anterior.

## Bot Discord

Após compilar o JAR e os testes, execute:

```sh
java -XX:-UsePerfData -Djava.awt.headless=true -cp target/java-cad-reader-1.0.0.jar:/tmp/cad-reader-tests br.gov.mg.sids.reader.ReaderBotTest
```

Usa transporte REST falso, sem token real e sem Discord. Verifica JSON Unicode, total e naturezas por área BM, IDs repetidos, ausência de área, desconsideração da unidade de atendimento e de outros órgãos, páginas sem perda, sugestões de filtros, cache/consulta simultânea, resposta privada e publicação sem nova consulta, criptografia do token, prévia sem exclusão, confirmação única, permissões e preservação de mensagens posteriores ao corte.

`tests/mock_broker.py` verifica também consulta de segundo filtro por ID, resposta isolada do monitor e recusa não fatal, na mesma conexão. Não comprova o comportamento do servidor CAD de produção. Testes locais não publicam nem apagam mensagens reais. Para validação integrada, use bot/servidor de teste e tópico descartável antes de operar a limpeza em produção.

`ReaderBotGatewayTest` usa WebSocket falso para verificar Identify com intent 0, heartbeat/ACK com sequência, retomada de sessão, dispatch e encerramento, sem conexão Discord.

`ReaderBotWindowTest` verifica os controles Swing reais sem rede: configurar antes da conexão CAD, confirmar/cancelar a edição, habilitar início após o catálogo, mostrar estado e bloquear reinício durante a parada assíncrona. `ReaderProfileWindowTest` verifica também recuperação e persistência de token/IDs sem iniciar o bot.

```sh
xvfb-run -a java -XX:-UsePerfData -cp target/java-cad-reader-1.0.0.jar:/tmp/cad-reader-tests br.gov.mg.sids.reader.ReaderBotWindowTest
```

`ReaderBotStartupTest` simula HTTP 403 nas quatro etapas do início, verifica identificação da etapa e código Discord, instruções de configuração e ausência de token/corpo externo no diagnóstico. Não acessa a rede.

`ReaderBotHttpTest` verifica requisições REST com `java.net.http` (Java 11), autenticação, timeout, UTF-8, repetição após HTTP 429, diagnóstico HTTP 403 e ausência de repetição pela aplicação após falha de rede. Inclui envio real de `PATCH` para um servidor temporário em localhost, evitando que uma conexão simulada esconda incompatibilidades do transporte. Não acessa Discord nem CAD; `--no-sockets` executa apenas os cenários sem servidor local.


`ReaderOperationalTest` verifica a projeção operacional: carga inicial, escopo de recursos, classificação/remoção de chamadas, coordenadas inválidas, desconexão com dados antigos, bloqueio de cálculos antigos e reset de sessão. Verifica ainda persistência/validação atômica do cadastro, URL de servidor próprio, perfis físicos, ausência de conexão automática, cache, ordem base → ocorrência e geometria GeoJSON com Valhalla simulado. A parte HTTP usa somente localhost, valida o frontend servido pelo JAR, acesso ao estado e recusa de escrita sem token ou de origem externa. `--no-sockets` pula essa parte. A suíte requer o frontend compilado e incluído no JAR, sem acessar CAD, Google Maps, FOSSGIS ou tiles externos.


## CSV e abas administrativas da Web

Execute `npm test --prefix frontend` antes de `npm run build --prefix frontend`. Os testes compilam os módulos TypeScript para uma pasta temporária e usam asserções Node sem dependências adicionais. Cobrem UTF-8/BOM, delimitadores, aspas, campos multilinha, vírgula decimal, IDs textuais, round-trip de unidades/perfis, campos pendentes sem coordenadas falsas, erros por linha, duplicatas, cabeçalhos, dimensões inválidas, datas, URLs de fonte, proteção de fórmulas, limites e mescla/substituição sem mutar o cadastro anterior. Conferem também as seis linhas do CSV oficial pesquisado. O CI executa esses testes antes de empacotar a Web.

`ReaderOperationalTest` verifica também a persistência do endereço/fonte/data/observações de uma base com coordenadas e vínculo CAD pendentes, recusa de coordenadas parciais/fontes inseguras, bloqueio de rota sem origem e download do CSV com MIME correto a partir do JAR.

### Atualizações da interface operacional

`npm test --prefix frontend` verifica CSV, coordenadas das seis bases e regras de filtro. `ReaderOperationalTest` cobre matriz com múltiplas origens, sentido base→ocorrência, parâmetros físicos, par inalcançável, cache, bloqueio desconectado, buffer limitado de eventos e API de eventos. Os serviços externos não são acessados.

Conferência de navegador: letra no marcador; hover compacto; clique abre detalhes sem selecionar; duplo clique seleciona; seletores pesquisáveis com opções do recorte; lista e mapa sincronizados; painéis recolhíveis e tela cheia; comparação com uma matriz por perfil e geometria somente ao clicar no resultado; dashboard e eventos; ajustes de Valhalla em Configurações; navegação móvel sem rolagem horizontal. Em execução real, confira as confirmações/pausas do publicador na página Eventos e o portão de saída das bases.

A suíte Web também verifica os rótulos de empenho, polling serial, pausa/retomada por visibilidade, limpeza dos temporizadores e revalidação HTTP 304. `ReaderOperationalTest` verifica cache da projeção sem mudança, invalidação por alteração real, mensagens irrelevantes para o mapa, cursor incremental de eventos e ETag na API local. Conferência no Chrome: etiquetas de viaturas, legenda recolhida/expandida, alternância ruas/satélite e observação do DOM sem recriar marcadores após uma resposta 304.

### Comunicação passiva e dados locais das ocorrências

`ReaderOperationalTest` verifica contadores deduplicados, interrupção de pendências na reconexão, limite da tabela, histórico/contatos recebidos sem iniciar consultas, paginação desconhecida e limpeza da sessão. As APIs `/api/communication` e `/api/call?id=...` são de leitura local e usam ETag. `ReaderSessionTest`, executado pelo broker simulado, confere os contadores contra 13 requisições reais do cenário (11 sucessos, 2 recusas), incluindo duas páginas de histórico e uma consulta posterior recusada. O histórico anteriormente recebido permanece no cache com indicação de consulta incompleta. Os demais cenários do broker verificam TLS, compressão, filtros, recusas e timeout.

Conferência no navegador: páginas Comunicação/Ocorrências/Viaturas; filtro por operação; navegação requisição → ocorrência → histórico/contatos → mapa; indicadores CAD/Valhalla acessíveis por mouse e teclado; navegação móvel e ausência de pedidos CAD criados ao abrir detalhes. Históricos ausentes devem mostrar o estado do cache, nunca iniciar consultas silenciosas.

### Visão padrão, áreas e somatório Web

A suíte TypeScript cobre classificação exclusiva de viaturas, restrições, áreas sem duplicação por ocorrência e validação da visualização salva. `ReaderOperationalTest` verifica a origem BM da unidade de área, catálogo local de filtros sem consulta, proteção do POST de somatório, bloqueio de consultas concorrentes, disponibilidade da API de estado durante o pedido, cache compartilhado com o bot e preservação das ocorrências do mapa. `ReaderBotTest` verifica que texto do bot e dados do dashboard usam o mesmo resultado.

Conferência no Chrome: ponto distante não muda o zoom 14 inicial; filtros preservam enquadramento; salvar/restaurar visão funciona após recarregar; resumo de viaturas e gráfico de área BM; escolher filtro sem consultar; consulta somente pelo botão; navegar preserva resultado sem repetir pedido; copiar texto e PNG para clipboard; baixar PNG válido de 1.200 pixels de largura; navegação móvel sem rolagem horizontal. O teste usa respostas simuladas e o contexto local seguro para clipboard; não acessa CAD, Valhalla ou WhatsApp reais.

### Centralização da área e falha de carregamento de classes

O enquadramento automático ao abrir/filtrar foi restaurado. O botão por ícone centraliza Contagem com zoom 14 sem excluir atendimentos distantes; a leitura periódica de dados não desfaz essa centralização. A visualização salva anteriormente não controla mais a abertura do mapa.

`ReaderOperationalTest.linkageHttp` simula `NoClassDefFoundError` no transporte do Valhalla e verifica duas respostas HTTP 503 com orientação de reinício, além da continuidade de `/api/state`. Não simula rede externa nem altera o proxy. Conferência no Chrome: enquadramento inicial inclui ponto distante; botão Contagem centraliza; polling mantém posição; mudança do filtro volta a enquadrar os pontos.


### Detalhes da ocorrência em janela sobre o mapa (18/10/2026)

Removida a página Ocorrências do menu; os atalhos abrem resumo, histórico e envolvidos em diálogo modal com os dados locais recebidos. Testes do frontend e build TypeScript/Vite passaram. Validação no Chrome com dados simulados passou para abertura, histórico, fechamento por Esc/botão, retorno ao mapa, tela cheia e largura de celular. Maven package passou; integridade do JAR e correspondência dos arquivos frontend/dist com os recursos web incorporados verificadas.
