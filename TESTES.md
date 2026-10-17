# Verificação

## Compilar

```sh
mvn package
```

O build Maven compila e empacota; não executa os testes Java desta pasta automaticamente. Para executar a suíte sem interface, com Python 3 e JDK no PATH, na raiz do repositório:

```sh
python tests/run_tests.py
```

No Linux, use `python3` se necessário. O script compila todos os testes em uma pasta temporária e executa onze cenários sem interface, com o separador de classpath apropriado para Windows/Linux. Falhas retornam código diferente de zero. Não usa credenciais reais nem acessa o CAD/Discord.

O GitHub Actions compila em JDK 11 e executa essa suíte e `java -jar ... --help` em Java 8 e Java 11 no Windows e no Linux, além do broker simulado no Linux. Os testes com janelas e o broker não fazem parte de `run_tests.py`. Os comandos detalhados abaixo usam shell Linux; no Windows, o separador do classpath é `;`, em vez de `:`.

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
