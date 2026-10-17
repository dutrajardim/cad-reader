# Desenvolvimento

## Compilação e escopo

JDK 11+ para compilar, Maven e Swing; execução em Java 8 ou superior. Não depende das classes descompiladas do projeto principal. `mvn package` produz o JAR executável. Sem argumentos, o ponto de entrada abre a interface; com opções CAD, executa o leitor no terminal.

A aplicação observa estados recebidos do CAD. Não escreve atendimentos, não executa triggers remotos, não sincroniza mensagens humanas do Discord e não confirma ciência individual dos militares.

## Repositório independente e build

A raiz publicada é o conteúdo de `java-cad-reader`. Compilação: `mvn clean verify`, com JDK 11+ e Maven 3.6.3+. O `maven.compiler.release=8` verifica também a compatibilidade com as APIs do Java 8 ao compilar em JDKs mais novos. A entrada do JAR é `br.gov.mg.sids.reader.CadXmppReader`; OkHttp e suas dependências são incorporados ao JAR pelo Maven Shade; não é necessário instalar bibliotecas no computador de execução.

O workflow `.github/workflows/build.yml` valida Linux/Windows, disponibiliza o JAR como artefato e publica esse mesmo arquivo como `cad-reader.jar` em uma Release por commit de `main`. O endereço de download da Release mais recente permanece fixo; pull requests não publicam. Veja [README.md](README.md) para publicação/download e [TESTES.md](TESTES.md) para os cenários automatizados. As referências ao cliente original abaixo são contexto histórico da análise; o código original não é distribuído nem necessário para o build.

## Responsabilidades

| Classe | Responsabilidade |
| --- | --- |
| `CadXmppReader` | TCP, TLS, zlib, SASL, bind, pedidos CAD, snapshots, atualizações, ping e timeout |
| `ReaderOptions` | Validação de opções e padrões do cliente |
| `CadInputBuffer` | Leitura em blocos com no máximo um refill, sem bloqueio por available() do zlib |
| `CadXml` | Parser StAX/DOM seguro, namespaces, leitura de filhos diretos e coleções |
| `CadListener` | Callbacks de log, catálogo de filtros e mensagens CAD |
| `CadReaderWindow` | Interface Swing, sessão, perfis, histórico CSV e prévia |
| `FilterSelector` | Combo editável com busca local por nome/ID no mesmo campo, opções Nome (ID), restauração por ID e seleção restrita ao catálogo |
| `CadMonitor` | Associação chamada/recurso/empenho, aplicação do filtro, comparação de estados e formatação |
| `CadResourceFilter` | Critérios de unidade e tipo do filtro selecionado, conforme FiltroRecurso.avaliar |
| `DiscordPublisher` | Webhook, fila persistente, tópicos por recurso, deduplicação e bloqueio local |
| `ProfileStore` | Cifragem e recuperação de webhook, token do bot e senha CAD opcional |
| `AppFiles` | Arquivos atômicos, permissões POSIX, hash e escape JSON |

## Fluxo CAD

Na interface, a caixa de diagnóstico TLS começa selecionada por decisão de configuração da instalação: `connect()` converte a seleção em `insecure=true`. O modo terminal mantém validação por padrão. A opção não é persistida no perfil.

1. Abre TCP e stream XMPP para o domínio configurado, com remetente técnico `ie@domínio`, como `NettyXMPPConstants.DEFAULT_INITIATING_ENTITY_JID` do cliente original. `ReaderOptions` converte o usuário para maiúsculas (equivalente ao `upperCaseOnly` de `TelaAcessoView`); não transforma a senha. O usuário SASL é independente desse remetente técnico.
2. Negocia STARTTLS, autenticação SASL PLAIN, compressão zlib quando oferecida e bind. O servidor pode oferecer compressão antes da autenticação. Cada mudança reinicia a stream XML; o parser evita antecipar bytes através dessas fronteiras. A leitura fica limitada a um byte durante a negociação; após o bind, passa a usar blocos.
3. Usa o JID retornado pelo bind como remetente das mensagens CAD.
4. Envia `envIniciarSessao` → `envRecuperarCacheAntesValidarPreRequisitos` → `envValidarPreRequisitos`.
5. Na interface, recupera `envRecuperarCacheAposValidarPreRequisitos`, mostra o catálogo e aguarda seleção. `applyFilters` permite assinar filtros na mesma sessão. No terminal, IDs fornecidos antecipadamente permitem seguir diretamente à assinatura após validar pré-requisitos.
6. Envia `envAlterarFiltroChamada` e `envAlterarFiltroRecurso`; os sucessos disparam `envRecuperarResumosChamadasAtivas` e `envRecuperarResumosRecursosParaEmpenho`.
7. Processa `envInformarAlteracaoChamada` e `envInformarAlteracaoRecurso` e mantém pings periódicos.

Pedidos CAD são filhos de `<message xmlns='jabber:client'>`, com namespace `http://sids.mg.gov.br/cad`, ID próprio e `dataHoraEnvio`. O relógio é alinhado ao timestamp do broker usando tempo monotônico. Respostas são correlacionadas por ID e tag; status diferente de zero interrompe a sessão nos pedidos essenciais. Consultas complementares de histórico recusadas ou vencidas geram diagnóstico sem interromper a sessão. Cada pedido possui prazo independente: receber pings não resolve um pedido CAD sem resposta. Padrões de versão/revisão são 4.4.55/279 e revisão mínima do broker 279; o terminal permite sobrescrevê-los.

As coleções do marshaller usam `<colecao><i>…</i></colecao>`. Leia os itens diretos com `collectionItems`, pois listas aninhadas também usam `i`. Atualizações usam entidades nomeadas. DTD e entidades externas estão desabilitados.

## Associação e eventos

`CadMonitor` mantém os últimos resumos por `idChamada` e `idRecurso` durante uma sessão. Os resumos são importados para um documento sem raiz, evitando que o cache retenha snapshots completos pela referência ao documento DOM original. Um índice de chamada para recursos permite recalcular somente os recursos afetados por cada mensagem. A coleção `resumosEmpenhosRecurso` relaciona cada recurso às chamadas por `idChamada`, com `situacaoEmpenhoRecurso` e `dataHoraSituacaoEmpenho`. A situação geral do recurso vem de `situacaoRecurso`.

`CadResourceFilter` lê `idsUnidades` e `idsTiposRecurso` do filtro selecionado no catálogo. O resumo precisa informar `idUnidadeServico` e `idTipoRecurso`. A unidade precisa estar incluída; tipos vazios permitem qualquer tipo conhecido, e tipos preenchidos restringem a lista. `CadMonitor` não aceita recursos antes de configurar o filtro. Um recurso que sai do escopo é removido do cache e do índice por chamada, sem publicar falso encerramento. Essa verificação cobre snapshots e eventos espontâneos, sem depender de o broker enviar somente os itens do filtro.

O endereço e a situação da ocorrência vêm da chamada relacionada. Natureza, complemento, referência e horário de solicitação são incluídos quando disponíveis. Horários numéricos em milissegundos são apresentados em `America/Sao_Paulo`; outros formatos são preservados. O horário de recebimento é UTC ISO-8601.

O fingerprint cobre os dados formatados e links, sem incluir o horário de recebimento. Repetir o mesmo estado na sessão não cria evento. A primeira carga é rotulada como situação atual e gera eventos com `publishable=false`. Somente `envInformarAlteracao*` processadas após receber ambas as cargas iniciais podem gerar eventos publicáveis. Atualizações intercaladas antes da referência completa apenas atualizam a referência local. A barreira é reiniciada com `setResourceFilter`. `DiscordPublisher.publish` também rejeita eventos não publicáveis, além da verificação na interface. Uma atualização de chamada também pode gerar uma mensagem para empenhos já conhecidos. Não há inferência de encerramento por ausência de item. O leitor não reconstrói estados intermediários perdidos enquanto estava desligado. Os registros de histórico anteriores disponíveis no CAD são recuperados por consulta quando há empenho acompanhado.

### Acompanhamento por chamada

O protocolo disponível do cliente original permite consultas pontuais por ID, mas não expõe uma operação identificada de assinatura/cancelamento individual de chamadas. Não se presume que consultar um resumo habilite notificações futuras. A aplicação mantém a seleção de filtro e a mesma conexão; não executa polling. As anotações `@Subscribe` do cliente original tratam o barramento de eventos local, não uma assinatura no servidor.

Referências do código original:

- `../sids/mensagem/xmpp/client/chamada/EnvRecuperarResumoChamada.java` (EnvRecuperarResumoChamada, referência externa ao repositório): consulta por ID e versão lógica opcional.
- `../sids/mensagem/xmpp/client/chamada/EnvRecuperarChamada.java` (EnvRecuperarChamada, referência externa ao repositório): consulta por ID, sem opção de assinatura.
- `../sids/mensagem/xmpp/client/chamada/EnvAlterarFiltroChamada.java` (EnvAlterarFiltroChamada, referência externa ao repositório): filtro da sessão.
- `../sids/mensagem/xmpp/client/envio/ativo/EnvInformarAlteracaoChamada.java` (EnvInformarAlteracaoChamada, referência externa ao repositório): resumo e ações geradoras de notificação.
- `../sids/mensagem/xmpp/common/TipoAcaoGeraNotificacaoEnum.java` (TipoAcaoGeraNotificacaoEnum, referência externa ao repositório): ação específica `SALVAR_HISTORICO_CHAMADA`.

Fora do filtro de chamadas, receber o empenho não garante receber todas as mudanças da chamada. Também não se garante detectar uma edição antiga quando o broker não informar a ação específica nem alterar a quantidade/primeira página. Não há implementação de assinatura individual baseada em uma operação não confirmada.

### Consulta e difusão de histórico

`ResumoChamada.resumosHistoricosChamada` contém a primeira página. Para um empenho ativo acompanhado após a referência inicial, `CadMonitor` habilita destinatários por recurso/chamada, publica registros ainda não vistos e chama `setHistoryRequest`. Novos vínculos ativos consultam o contexto uma vez. Consultas posteriores exigem alteração de `quantidadeHistoricos`, descrição/data dos itens `resumosHistoricosChamada` ou o item `SALVAR_HISTORICO_CHAMADA` em `EnvInformarAlteracaoChamada.tiposAcoesGeramNotificacao`. A ação explícita cobre edições fora da primeira página, mesmo com quantidade inalterada. Mudanças gerais não disparam consulta. Uma coleção de IDs por mensagem evita repetir a consulta para várias viaturas na mesma chamada; a fila do leitor agrupa pedidos em andamento. Empenhos que já existiam na referência inicial não iniciam consulta por uma mudança geral de endereço/situação. Encerramento (`TR`/`TERMINADO`/`ENCERRADO`), remoção do vínculo ou saída do filtro removem o destinatário; respostas tardias não publicam para esses vínculos.

`CadXmppReader.requestHistory` serializa consultas por chamada em uma fila e agrupa solicitações concorrentes: uma chamada em consulta pode receber uma única repetição ao terminar. O XML usa `envRecuperarResumosHistoricosChamada`, atributos `currentPage` (inicia em 1) e `pageSize=50`, `idChamada`, todos os órgãos do enum original e ambos os tipos de histórico. Não escreve histórico no CAD. A versão lógica é omitida, como permitido pela consulta original sem restrição de versão. `PendingRequest.history` correlaciona a chamada com cada resposta; `readerCallId` é um atributo interno acrescentado somente ao DOM recebido, nunca enviado ao servidor. `paginationBean.totalSize/pageSize` controla páginas seguintes. Sem paginação, processa a página e diagnostica a limitação. Recusas/timeouts do histórico não encerram a sessão.

O monitor compara descrição/data por registro e destinatário; IDs são aproveitados quando presentes, com fallback por hash de data/descrição. Não exporta campos estruturados de autoria. Descrições livres são preservadas e podem conter dados pessoais. Textos longos são divididos em partes com horário CAD, chamada e viatura, evitando truncamento pelo embed. A deduplicação do monitor vale para a sessão atual; uma nova sessão pode republicar contexto histórico após uma atualização, conforme os fingerprints persistidos e os vínculos existentes.

A interface registra CSV antes de encaminhar o evento ao publicador. Campos são citados e protegidos contra interpretação de prefixos de fórmula comuns. A tabela conserva 5.000 eventos; o arquivo é cumulativo e não possui rotação automática.

## Concorrência e ciclo de vida

Swing é atualizado no EDT. Conexão, seleção de filtros, preparação do publicador e criptografia de perfis rodam em executor; callbacks CAD chegam na thread de leitura. Um número de geração evita que dados de uma sessão anterior entrem na nova sessão após desconexão. Logs e tabela são encaminhados ao EDT em lotes a cada 150 ms (até 200 linhas de log e 250 eventos por ciclo). As filas visuais são limitadas a 2.000 logs e 5.000 eventos, descartando os mais antigos em sobrecarga; o CSV é gravado antes dessa fila visual. A tabela notifica o sorter uma vez por lote e preserva a seleção quando o evento ainda está visível. XML só é serializado para debug ou erros; o funcionamento normal evita esse custo. Cada publicador usa executor serial próprio e bloqueio de arquivo por ID do webhook dentro da pasta de dados.

Não há reconexão automática. Fechar/desconectar encerra o socket e o publicador; pendências persistidas permanecem para retomada explícita. Credenciais existem em memória enquanto necessárias; a senha CAD só é salva quando o usuário habilita a opção explícita de armazenamento cifrado. A implementação não garante eliminação de todas as cópias de strings de credenciais da memória da JVM.

## Discord e entrega

O destino aceito é uma URL HTTPS `discord.com/api[/vN]/webhooks/ID/TOKEN`. Não usa token de usuário Discord. O primeiro POST usa `thread_name`; os seguintes usam `thread_id` na query. `wait=true` pede confirmação e o `channel_id` da resposta fornece o identificador do tópico. `allowed_mentions.parse=[]` evita menções involuntárias. Mensagens usam uma linha `content` para prévia legível no fórum, embeds e links Markdown de navegação. A chave de tópico é o hash do prefixo aparado e em maiúsculas; sem prefixo, usa o ID do recurso. O mapa antigo por ID é consultado como fallback e associado à nova chave quando houver confirmação. O nome dos novos tópicos é `VTR PREFIXO`; tópicos existentes não são renomeados.

### Janela e persistência dos lotes

`publish()` agrupa eventos pela chave do tópico/prefixo, identidade da chamada obtida da chave do evento e escopo de unidade/tipo. Eventos sem chamada usam o grupo de recurso. Cada `OpenBatch` tem prazo monotônico fixo de 3.000 ms desde a primeira inclusão. O scheduler não bloqueia as threads CAD/EDT. `fits()` limita cada mensagem a dez embeds e 6.000 caracteres somados nos campos dos embeds, conforme os [limites do Discord](https://docs.discord.com/developers/resources/message#embed-limits). Ao exceder o limite ou o prazo, sela o lote anterior e abre outro. Lotes cheios podem sair antecipadamente. Cada embed conserva o título, conteúdo, links e timestamp do evento; a prévia informa a quantidade de atualizações e o último título.

Cada inclusão regrava atomicamente o mesmo `.pending`, com payloads completos `create`/`reuse`, `members` e fingerprints `member.N.*`. Assim o arquivo é suficiente para reenvio mesmo se o processo encerrar durante a janela. O CSV é gravado antes e permanece individual. A inclusão e a selagem compartilham o lock do publicador; HTTP ocorre fora desse lock. Um lote selado não recebe novas inclusões. O contador de pendências mede arquivos/mensagens, não membros. Arquivos usam timestamp, sequência e UUID para preservar a ordenação e evitar colisões na reabertura.

`flushBatch()` sela uma única vez. `drainQueue()` não antecipa um lote em coleta: ao atingir o prazo, sela-o sob o mesmo lock antes de ler o arquivo. HTTP 429 e falhas ambíguas preservam o payload completo, sem reagrupar lotes já enviados. A reabertura não precisa reconstruir objetos `Event`; os payloads persistidos são retomados explicitamente. Arquivos antigos sem `members` continuam válidos.

Após confirmação, grava o fingerprint do lote e os fingerprints individuais na ordem dos membros, conservando o último estado de cada chave. O fingerprint de um lote com vários eventos inclui sua identidade persistida: uma nova sequência real A→B não é suprimida por ser igual à sequência de um lote anterior. A comparação do lote protege a retomada do mesmo arquivo após confirmação local; as falhas ambíguas continuam sem garantia exatamente uma vez.

O construtor de pacote permite substituir a janela nos testes. Os testes de entrega anteriores usam zero para isolar recuperação/deduplicação; `ReaderBatchTest` verifica a janela real de três segundos e os demais casos com janelas menores.

Antes de enviar, grava um arquivo `.pending` sem o token, com unidade/tipo, chave do tópico, prefixo e dados da mensagem. O publicador da interface recebe o filtro selecionado e o verifica na inclusão e no envio. Ao abrir o publicador, cargas iniciais antigas identificadas por `SITUAÇÃO ATUAL` nos campos de título/prévia do payload de criação (não no texto livre do histórico) são preservadas como `.pending.initial-state`. Pendências incompatíveis ou sem metadados de escopo são renomeadas para `.pending.outside-filter` e excluídas do reenvio. A movimentação preserva os dados e não apaga publicações no Discord. Sucesso grava mapa de tópicos e último fingerprint confirmado, então remove a pendência. Falha bloqueia o envio das próximas mensagens até reenvio manual. HTTP 429 preserva a fila e agenda retomada no executor serial (`ScheduledExecutorService`), respeitando `Retry-After` ou `retry_after`, em segundos fracionários. O prazo mínimo é um segundo e o fallback é cinco segundos. Reenvio manual respeita o mesmo instante mínimo. Nenhuma espera ocupa o EDT ou a thread CAD. Falhas de rede e recusas permanentes mantêm retomada manual para evitar duplicação de envios ambíguos. O reenvio ordena nomes com timestamp e sequência. Antes da deduplicação verifica-se a existência de um vínculo de tópico local. Uma resposta HTTP 400/404 com código Discord 10003 para um tópico já mapeado remove os aliases desse vínculo e faz uma única tentativa de criação. Não trata 404 genérico ou 10015 (webhook inexistente) como tópico apagado. Falhas são registradas com mensagens fixas e códigos numéricos; corpos HTTP e mensagens de exceção de rede nunca são impressos. `status()` expõe o estado e o número de pendências ao rodapé da interface.

Não existe coordenação entre computadores nem garantia exatamente uma vez em falhas ambíguas. Alterar webhook/canal significa um destino com mapa independente; a rotação de token do mesmo webhook mantém o mapa pelo ID público.

O transporte é substituível no construtor de pacote `DiscordPublisher(..., Transport)` para testes locais. A implementação real usa `DiscordHttpTransport` com `HttpURLConnection`, `Connection: close`, timeout de conexão de 15 segundos e leitura de 25 segundos, sem seguir redirecionamentos. Cada lote encerra sua conexão HTTP, evitando manter conexões ociosas entre publicações. O corpo UTF-8 usa tamanho explícito e streaming; não há repetição automática programada de POST após falha ambígua. Nunca inclua URLs de webhook ou corpo de erro HTTP em logs.

`ProfileStore` deriva chave AES-256 por PBKDF2-HMAC-SHA256 (210.000 iterações), com sal aleatório de 16 bytes. Usa GCM com IV aleatório de 12 bytes e tag de 128 bits. Cada segredo (webhook, token do bot e senha CAD opcional) tem sal/IV próprios. Perfil salva sal, IV e ciphertext; senha de proteção é fornecida a cada abertura e nunca salva. Preferências e usuário permanecem em texto simples. O formato anterior de `webhook.encrypted` é compatível; a senha CAD usa `password.encrypted`. Salvar sem a opção CAD remove o segredo salvo. A lista editável enumera somente nomes válidos de arquivos `.properties`, sem decifrá-los; remoção afeta apenas o arquivo escolhido após confirmação. CSV e fila não são cifrados.

## Navegação

Maps e Waze recebem coordenadas válidas ou endereço, com prioridade para coordenadas. Não há cálculo de distância ou coleta de localização do integrante.

## Verificação

Consulte [TESTES.md](TESTES.md) para executar os testes, entender seus limites e validar uma instalação real. Novos campos devem ser confirmados nas entidades/protocolo CAD e testados com XML sanitizado. Não inclua senhas, webhook real ou XML operacional no repositório.

## Referências das integrações

- [Webhooks Discord](https://docs.discord.com/developers/resources/webhook): criação de tópicos, `thread_id` e confirmação com `wait`.
- [Google Maps URLs](https://developers.google.com/maps/documentation/urls/get-started).
- [Waze Deep Links](https://developers.google.com/waze/deeplinks).

- [Limites de envio Discord](https://github.com/discord/discord-api-docs/blob/main/developers/topics/rate-limits.mdx).
- [Códigos de erro Discord](https://github.com/discord/discord-api-docs/blob/main/developers/topics/opcodes-and-status-codes.mdx).

### Apresentação das atualizações no Discord

`CadMonitor` compara os campos do último estado por recurso/chamada para produzir `Event.discordBody` e `discordLinks`. `body` e `links` conservam o contexto completo para consulta local/CSV. Novo vínculo ou transição para despacho recebe contexto completo; as demais mensagens mostram diferenças, incluindo campos removidos. A chegada tardia do endereço completa o despacho. Links de navegação são repetidos apenas quando a localização muda. A comparação é reiniciada ao trocar o filtro.

O tópico contém o prefixo da viatura; o título do embed contém chamado e atualização. O payload não duplica o título em `content`. Históricos também omitem a linha redundante de viatura/chamado no Discord. Payloads já persistidos na fila conservam sua apresentação anterior.

O marshaller `sids/entidades/chamada/resumo/ResumoChamada.java` não inclui nome/telefone do solicitante. Os contatos são obtidos pela consulta específica de envolvidos; não é necessário recuperar a chamada completa.

### Consulta dos envolvidos no novo empenho

`CadMonitor.setParticipantsRequest` é conectado pela janela a `CadXmppReader.requestParticipants`. Ao detectar um novo vínculo ativo após a referência inicial, o monitor registra o recurso como destinatário dos contatos e solicita os envolvidos uma vez por chamada/sessão. As viaturas adicionais reutilizam o cache. Não há consulta por alterações gerais de estado, endereço ou histórico, nem atualização periódica do cadastro.

`ReadQuery` representa consultas paginadas de histórico e envolvidos numa única fila serial. Para envolvidos, envia `envRecuperarEnvolvidosChamada`, `idChamada`, `currentPage` e `pageSize=50`, conforme `sids/mensagem/xmpp/client/envolvido/EnvRecuperarEnvolvidosChamada.java`. O retorno é `rtnRecuperarEnvolvidosChamada`, coleção `envolvidosChamada`; itens podem usar `i` ou `envolvidoChamada`. O ID da requisição correlaciona a resposta e o atributo interno `readerCallId` a encaminha ao monitor. Paginação, recusa e timeout seguem o tratamento não fatal das consultas complementares.

A autorização é decidida pelo servidor. No cliente original, `hasPermission` exige `GESTAO_ENVOLVIDOS_CHAMADA` e uma das permissões de origem atividade/emergência. Não se executa nenhuma operação de escrita no cadastro. O cache importa apenas ID, nome, telefone e indicador `solicitante`; os demais campos recebidos são ignorados. `emitParticipants` deduplica os registros por envolvido/destinatário e divide listas em blocos. Respostas tardias são verificadas contra os vínculos ativos e o filtro atual antes da publicação. Telefone com DDD é normalizado para link `wa.me`, sem inferir DDD ausente.

### Descarte manual da fila

`DiscordPublisher.clearPending` marca a limpeza e sela os lotes existentes sob o lock de inclusão. A exclusão dos arquivos `.pending` ocorre no worker serial, após eventual HTTP em andamento terminar. Novos arquivos criados durante a operação são registrados em `arrivalsDuringClear`, preservados e reagendados conforme a janela original. Jobs antigos encontram lotes selados ou arquivos removidos e não reenviam o conteúdo descartado. A contagem diminui somente para arquivos efetivamente removidos. Erro de exclusão mantém a fila pausada e informa limpeza incompleta.

A limpeza conserva `topics.properties`, CSV, arquivos isolados e o prazo `retryNotBefore` de HTTP 429. A interface solicita confirmação antes de descartar e não bloqueia o EDT aguardando HTTP/exclusão. `networkReason` percorre tipos de exceções para diagnóstico seguro, sem registrar suas mensagens, que podem conter tokens do webhook.

### Apresentação e futura consolidação

[FORMATO_MENSAGENS.md](FORMATO_MENSAGENS.md) documenta títulos, rótulos e distinção entre horário CAD e recebimento. A apresentação prioriza o acompanhamento humano: número do chamado, tipo, ícone/cor e corpo compacto. O rodapé é `CAD • Recebido`, sem JSON ou IDs técnicos. `embedTextLength` inclui o rodapé na contagem. A identidade/deduplicação do publicador continua nos dados locais, sem expor hashes nas mensagens. `DiscordPublisher.readablePayload` substitui apenas o antigo rodapé `CAD-READER/1` no envio de pendências, preservando corpos livres e arquivos originais; também se aplica à recriação de tópico. Não há campos arbitrários ocultos no payload Discord para metadados de bot.

### Seleção pesquisável dos filtros

`FilterSelector` usa um `JComboBox` editável, com um único editor para digitação e seleção. O listener do documento reconstrói apenas o modelo de opções; durante essa operação, o editor não recebe substituições automáticas de texto, evitando modificar o documento enquanto ele notifica e preservando o cursor. Mudanças programáticas da seleção não são interpretadas como novas buscas. A seleção salva é restaurada por ID, mantendo o catálogo completo inicialmente. `selectedId` aceita somente uma opção do catálogo cuja apresentação corresponde ao texto confirmado no editor. Enter confirma um resultado único ou uma apresentação completa; a busca sem confirmação não fornece ID.

### Diagnóstico de decisão por chamada

`CadMonitor.setDiagnostics` recebe uma explicação ao concluir cada mensagem de alteração de chamada, com número/ID, quantidade de vínculos ativos acompanhados e quantidade de eventos de recurso gerados. A janela registra essas decisões como `[PUBLICACAO]`. O diagnóstico distingue barreira da carga inicial, falta de vínculo no índice local e deduplicação dos campos apresentados, sem fazer novas consultas ao servidor. Solicitação de histórico é informada, mas não equivale a histórico publicado. A janela registra também eventos somente locais quando não há publicador Discord. Entrega efetiva continua sendo confirmada exclusivamente pelo publicador. Limpar pendências não reinicia os fingerprints do monitor.

### Diagnóstico por etapa do envio HTTP

`DiscordHttpTransport.Failure` guarda uma etapa fixa: preparação, conexão TCP/TLS, envio do conteúdo, espera dos cabeçalhos ou leitura da resposta. A cadeia de causas é preservada para classificação por tipo, mas suas mensagens não são registradas. `DiscordPublisher` exibe etapa e categoria quando disponíveis. HTTP de sucesso sem `channel_id` é identificado como confirmação incompleta, preservando o arquivo pendente e pausando a fila. Fechar o publicador desconecta o transporte ativo. O transporte usa a validação TLS padrão do Java para HTTPS.

A troca de transporte evita reutilização de conexões ociosas e HTTP/2; não comprova a causa de falhas passadas nem impede indisponibilidade de rede/servidor. Há uma nova negociação TCP/TLS por lote, com custo adicional de conexão. O agrupamento de eventos continua reduzindo a quantidade de lotes. Não são feitos pings ou consultas extras ao Discord/CAD durante a ociosidade.

### Contexto inicial e novos registros de histórico

A primeira consulta de histórico de cada vínculo entre viatura e chamada aguarda todas as páginas e publica um bloco **HISTÓRICO INICIAL**, em ordem cronológica, com a data CAD de cada registro. Os registros já presentes no resumo entram nesse mesmo contexto, sem mensagens individuais repetidas. O texto é reproduzido, sem resumo automático. O contexto pode conter registros posteriores ao despacho que já existiam quando a consulta foi feita.

Após esse contexto, registros novos ou modificados geram eventos individuais **HISTÓRICO DA OCORRÊNCIA**; a janela de agrupamento de três segundos continua valendo. Conteúdo longo é dividido em partes para respeitar os limites do Discord, sem descarte. Se a consulta falhar após receber algum conteúdo, publica-se o disponível com indicação de consulta incompleta. Não são acrescentadas consultas ao CAD para fazer essa consolidação.

A cor dos embeds é determinística pelos últimos oito dígitos do número da chamada (hash convertido em matiz com saturação/brilho constantes); não depende do estado nem da viatura. Ícones continuam dependendo do tipo de evento. Links incrementais comparam o endereço e o destino de navegação anterior; referência/complemento não disparam sua reapresentação.

## Bot de comandos

`DiscordBot` recebe interações de `/cad-reader totais-por-natureza` e `/cad-reader limpar`; `DiscordBotGateway` usa WebSocket v10 via OkHttp compatível com Java 8, intent 0, heartbeat com ACK, sequência e retomada de sessão. Desconexões recuperáveis têm retentativa com atraso; erros terminais exigem reinício. Não há Message Content intent nem servidor HTTP público. `BotJson` interpreta JSON com limite de profundidade e `DiscordBotApi` executa REST com TLS padrão, timeout e espera de HTTP 429. Não usa o modo insecure do CAD. Tokens e URLs de interação não entram no log.

O registro é por servidor, via POST de um único comando, preservando outros comandos da aplicação. `default_member_permissions=0` exige configuração de acesso nas Integrações do Discord. O runtime aceita administradores/Gerenciar servidor ou o cargo configurado. Limpeza exige também Gerenciar mensagens; publicação confere permissão de envio do usuário e a API aplica as permissões do bot. Todas as interações precisam pertencer ao servidor configurado. Ações ficam em memória, vinculadas a usuário/canal, com UUID, expiração de dez minutos e consumo atômico; botões não são utilizáveis após reinício. Há lock local por aplicação Discord.

`CadSummary` mantém nomes de filtros e unidades vindos dos caches já recebidos. `CadXmppReader.requestFilterSummary` correlaciona a resposta por ID em mapa separado, antes do dispatch normal. Recusa/timeout dessa consulta não encerram o monitoramento. Não envia alteração de filtro nem alimenta `CadMonitor`. O serviço compartilha o future por filtro em andamento, limita a uma consulta de relatório e reaproveita o último resultado por 30s. Naturezas e áreas são deduplicadas por chamada; a área vem exclusivamente de `resumosAtendimentosChamada` do órgão BM, com fallback explícito para ID/ausência. O limiar de 1.000 registros gera aviso, sem inventar paginação não prevista pelo protocolo.

Operações longas usam um executor serial, separado dos dois workers que respondem interações. O relatório privado é dividido em páginas de até 1.800 caracteres e o botão publica a cópia já consultada, com mentions desativadas. A limpeza valida o parent_id do tópico e o guild_id, busca mensagens diretamente antes do snowflake correspondente ao início da data em America/Sao_Paulo, pagina até 20.000 e guarda os IDs da prévia. Nenhuma exclusão ocorre antes de confirmar. Na confirmação, valida novamente o tópico e o corte de cada ID; mensagens de menos de 14 dias usam bulk-delete até 100, com margem de 60s, e antigas usam DELETE individual. 404 individual significa já ausente; demais falhas param a operação com progresso confirmado. Não há retry de falhas ambíguas. HTTP 429 é respeitado. Não existem consultas CAD na limpeza nem cópia das mídias apagadas.

`CadReaderWindow` cria o serviço junto à sessão, fornece os caches, oferece configuração/início/parada e encerra o bot ao desconectar. `ProfileStore` guarda `botToken.encrypted` com sal/IV próprios, removendo sempre a chave de token em texto simples antes de salvar. IDs do servidor/fórum/cargo são preferências não secretas. Iniciar o bot é ação explícita; abrir perfil não inicia conexões externas.

### Controles do bot na interface

`Configurar bot` usa campos temporários e só aplica os valores ao confirmar, mesmo sem sessão CAD. `Iniciar bot` é uma ação separada, habilitada após receber o catálogo. `Parar bot` conserva a referência até terminar o fechamento em background, bloqueando início/configuração concorrentes. `refreshBotControls` roda no EDT e consulta `hasFilters()` sem copiar o catálogo a cada atualização. O indicador Bot mostra o estado exposto por `DiscordBotGateway` (conexão, retomada, recusa ou parada), além dos estados locais de início/encerramento. O botão Ajuda apresenta instruções de instalação e comandos. O perfil persiste IDs e token cifrado; cancelar o diálogo não altera dados e abrir um perfil não inicia o bot.

A distribuição usa bytecode Java 8 (`--release 8`), incluindo bibliotecas no JAR. Os testes podem compilar em JDK 8 ou mais recente; o CI compila a aplicação em JDK 11 e testa execução em Java 8 e Java 11 no Windows/Linux.
