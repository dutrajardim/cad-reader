# CAD Reader — guia de uso

Aplicação Java com interfaces desktop e Web que consulta ocorrências e recursos do CAD pelos filtros autorizados ao usuário. Mostra a carga inicial e as atualizações, registra uma cronologia local e pode publicar em um fórum do Discord, com um tópico por viatura/recurso. Não altera ocorrências, não empenha viaturas e não preenche REDS automaticamente.

Os tópicos do Discord usam o prefixo normalizado da viatura: `UR05471 (2BBM)` e `ur5471 (JUATUB)` compartilham o tópico `VTR UR05471`; `ABT054` gera `VTR ABT00054`. O número tem no mínimo cinco dígitos, preservando números maiores. A identidade do tópico independe do ID do recurso no CAD. Nomes fora do padrão de letras seguidas de número são preservados em maiúsculas. Associações locais antigas são reaproveitadas quando reconhecidas pelo prefixo original ou ID do recurso; tópicos duplicados já existentes não são mesclados. Se ambos mudaram antes dessa migração, o registro antigo contém somente o hash e pode não ser reconhecido. Viaturas com o mesmo prefixo normalizado compartilham o tópico no mesmo webhook, mesmo com sufixos de unidade diferentes.

## Baixar e executar (Windows e Linux)

A aplicação tem interface gráfica. Informe usuário e senha do CAD na própria janela, depois de abrir o programa.

Requisitos: Java 11 atualizado ou superior no `PATH`, ambiente gráfico e acesso de rede ao CAD. O mesmo JAR funciona no Windows e no Linux. Para executar, não é necessário instalar Maven ou Python. O Java não está incluído no download; no Linux, use uma instalação com suporte à interface gráfica, em vez de somente o pacote headless.

### Baixar e abrir no Windows

Abra o **PowerShell** na pasta onde deseja guardar a aplicação e execute:

```powershell
$ErrorActionPreference = 'Stop'
Invoke-WebRequest -Uri 'https://github.com/dutrajardim/cad-reader/releases/latest/download/cad-reader.jar' -OutFile 'cad-reader.jar'
java -jar .\cad-reader.jar
```

O comando baixa a versão mais recente e abre a janela do CAD Reader. Preencha usuário e senha na interface e clique em **1. Conectar e buscar filtros**. Se o download falhar, a execução é interrompida.

Para abrir novamente sem baixar, use no PowerShell ou no Prompt de Comando:

```powershell
java -jar .\cad-reader.jar
```

Se `java` não for reconhecido, instale Java 11 ou superior, configure seu diretório `bin` no `PATH` e abra um novo terminal. Confira com `java -version`.

### Baixar e abrir no Linux

Dentro de uma sessão gráfica, abra um terminal na pasta onde deseja guardar a aplicação e execute (requer `curl`):

```sh
curl --fail --location 'https://github.com/dutrajardim/cad-reader/releases/latest/download/cad-reader.jar' --output cad-reader.jar &&
java -jar ./cad-reader.jar
```

O comando abre a janela após concluir o download com sucesso. Preencha usuário e senha na interface e clique em **1. Conectar e buscar filtros**.

Para abrir novamente sem baixar:

```sh
java -jar ./cad-reader.jar
```

Se `java` não for encontrado, instale Java 11 ou superior com suporte gráfico e verifique o `PATH` com `java -version`.

### Download manual e atualizações

A publicação exige o token em **Configurar bot** para procurar tópicos existentes antes de criar outro. A busca identifica o fórum pelo webhook e considera tópicos ativos e arquivados, com normalização dos nomes antigos. Não é necessário clicar em **Iniciar bot** para essa busca. O bot precisa de acesso ao fórum e leitura do histórico. Se a busca falhar, o envio permanece pendente; após corrigir token/permissões, use **Reenviar pendentes**. Tópicos encontrados são associados localmente para os próximos envios. Não há coordenação de criação simultânea entre PCs nem mesclagem de duplicados existentes. Tópicos arquivados encontrados são reutilizados pelo webhook; se estiverem bloqueados ou o Discord recusar o envio, a fila pausa.

Referência das consultas: [tópicos ativos](https://docs.discord.com/developers/resources/guild#list-active-guild-threads) e [tópicos públicos arquivados](https://docs.discord.com/developers/resources/channel#list-public-archived-threads).

Para transportar um perfil, primeiro use **Salvar perfil** e depois **Exportar perfil**, informando sua senha de proteção. No outro computador, escolha um nome de perfil local, informe a mesma senha e use **Importar perfil** para selecionar o arquivo. Depois clique em **Abrir perfil**. A exportação inclui somente as configurações salvas; alterações ainda não salvas não são incluídas. Webhook, token do bot e senha CAD opcional permanecem criptografados; as demais preferências ficam em texto simples, como no perfil local. Histórico, fila e associações de tópicos do Discord não são transportados.

Também é possível abrir a [Release mais recente](https://github.com/dutrajardim/cad-reader/releases/latest) no navegador e baixar **cad-reader.jar** em **Assets**. Não é necessário extrair ZIP. Os comandos acima pressupõem um repositório público; um repositório privado exige autenticação para baixar.

O download estará disponível depois que este workflow for enviado para `main` e a primeira execução concluir a publicação com sucesso. Para atualizar, feche a aplicação e repita o comando de download e abertura na mesma pasta: ele substitui o JAR local pela versão mais recente. Os perfis e históricos ficam na pasta pessoal do usuário e são preservados.

Nos dois sistemas, mantenha o terminal e a aplicação abertos durante o uso. O computador precisa permanecer ligado, com a aplicação conectada, para acompanhar atualizações. Abrir o JAR sem argumentos já inicia a interface gráfica.

Não é necessário criar arquivo `.env` nem configurar variáveis de ambiente com credenciais. Nenhuma credencial CAD/Discord é necessária para compilar ou executar o GitHub Actions.

### Compilar localmente

Requer JDK 11+, Maven 3.6.3+ e Node.js 22 com npm no `PATH`. Execute na raiz do repositório, que corresponde ao conteúdo de `java-cad-reader`:

```sh
npm ci --prefix frontend
npm run build --prefix frontend
mvn --batch-mode --no-transfer-progress clean verify
java -jar target/java-cad-reader-1.0.0.jar
```

O JAR contém a aplicação, a interface Web compilada e o manifesto executável; não precisa das pastas do projeto original que ficam fora deste repositório.

## Builds e versões automáticas

Repositório: [dutrajardim/cad-reader](https://github.com/dutrajardim/cad-reader).

O workflow executa em pushes para `main`, pull requests destinados a `main` e manualmente pelo botão **Run workflow**. Os testes sem interface usam transportes falsos e sockets locais; o broker simulado roda no Linux. Testes que exigem janelas continuam separados, conforme [TESTES.md](TESTES.md). Somente depois de ambos os sistemas passarem, o job de artefato recompila o mesmo commit e guarda o JAR no Actions por **30 dias**, conforme a retenção permitida pelo repositório. Em pushes e execuções manuais de `main`, o job de publicação usa esse mesmo artefato para criar uma **GitHub Release por commit**, com tag `build-<SHA>` e anexo **cad-reader.jar**. A Release é marcada como a mais recente, mantendo fixo o endereço `/releases/latest/download/cad-reader.jar`. Releases permanecem disponíveis até serem removidas. Pull requests e execuções de outras branches não publicam Releases; reexecuções de commits que já não são o topo de `main` também não publicam. Reexecutar o mesmo commit atualiza seu anexo existente.

O job de publicação usa o `GITHUB_TOKEN` automático com `contents: write`; os demais jobs têm somente leitura. Não precisa cadastrar um token pessoal.

Referências: [links de Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases), [publicação com GitHub CLI](https://cli.github.com/manual/gh_release_create), [build Maven no GitHub Actions](https://docs.github.com/en/actions/use-cases-and-examples/building-and-testing/building-and-testing-java-with-maven) e [download e retenção de artefatos](https://docs.github.com/en/actions/tutorials/store-and-share-data).

## Conectar e escolher filtros

1. Preencha servidor/domínio, porta, usuário e senha do CAD. O usuário é convertido para maiúsculas, como na tela original do CAD; a senha é preservada literalmente. O padrão da interface é `cad.sids.mg.gov.br:7001`.
2. Digite a senha diretamente no campo **Senha CAD**. O botão **Mostrar** permite conferi-la na tela; desative-o após a conferência. Clique em **1. Conectar e buscar filtros**. A aplicação estabelece TLS, autentica, inicia a sessão CAD e valida os pré-requisitos.
3. Escolha os filtros de chamadas e recursos disponíveis para sua unidade. As opções aparecem como **Nome (ID)**. Digite diretamente no campo de cada filtro para reduzir as opções por parte do nome ou pelo ID; a busca ignora acentos e maiúsculas. Selecione uma opção na lista ou pressione Enter quando houver um único resultado. Apague o texto para mostrar novamente o catálogo completo. A busca precisa ser confirmada como uma opção válida; texto livre ou sem resultado não pode ser usado como filtro. ID da unidade e ID do filtro são coisas diferentes.
4. Configure a publicação, se desejar, antes de clicar em **2. Iniciar monitoramento**. Durante o monitoramento a opção de publicar fica bloqueada; para alterá-la, desconecte. Com publicação desmarcada, os eventos ficam apenas na interface e no histórico local.
5. Para trocar filtros, usuário ou destino Discord, desconecte e inicie outra sessão.

A opção **Desativar validação TLS (diagnóstico)** vem marcada por padrão na interface para conectar à instalação CAD utilizada. A conexão continua cifrada, mas não verifica o certificado nem a identidade do servidor. Desmarque a opção quando o certificado for confiável para o Java. O servidor pode recusar credenciais, versões ou filtros. A aba **Conexão e publicação** mostra a etapa e o erro. Não existe reconexão automática: após uma interrupção, conecte novamente e selecione os filtros.

## Preparar o Discord

No servidor Discord da unidade, crie um **canal de fórum** e um webhook para esse canal em suas configurações de integrações. A criação do webhook depende das permissões da conta administradora. Copie a **URL completa do webhook**, não um token de usuário ou token de bot.

Cole a URL na interface, marque **Publicar no fórum Discord** e inicie o monitoramento. O webhook cria um tópico **VTR PREFIXO** na primeira publicação e usa o mesmo tópico nas seguintes atualizações e chamadas dessa viatura. O agrupamento usa o prefixo, mesmo se o CAD mudar o ID do recurso. Mensagens têm uma linha de resumo legível na listagem do fórum, além dos detalhes do atendimento. **A carga inicial não é publicada no Discord**, incluindo viaturas disponíveis, indisponíveis ou já empenhadas. Ela serve de referência local. Depois que as duas cargas (chamadas e recursos) chegam, somente mudanças efetivas geram publicações **ATUALIZAÇÃO**. Repetições do mesmo estado não publicam. Uma mudança real para disponível ou indisponível continua sendo uma atualização publicável. Um canal de texto comum não oferece o mesmo fluxo automático de criação de tópicos.

Não é necessário fornecer credenciais Discord de cada integrante à aplicação. O webhook dá à aplicação acesso de publicação; cada integrante usa sua própria conta Discord para acessar o fórum, conforme as permissões do servidor. Fotos, textos e mídias podem ser enviados pelos próprios integrantes nos tópicos conforme as permissões e limites do Discord. Essas conversas não são copiadas para o CAD nem para o CSV da aplicação. Notificações e silenciamento são gerenciados no Discord por cada integrante; a aplicação não controla as preferências individuais e não envia menções automáticas.

Mantenha **um publicador por unidade/webhook**. A aplicação bloqueia dois publicadores com o mesmo webhook na mesma pasta de dados do computador. Instalações em outros computadores não compartilham esse bloqueio e podem duplicar notificações. O vínculo entre prefixo e tópico fica salvo nesse computador. Viaturas com o mesmo prefixo no mesmo webhook compartilham o tópico; use um destino por unidade. Sem prefixo, o ID do recurso identifica o tópico. Vínculos anteriores por ID são aproveitados quando disponíveis; tópicos já criados não são renomeados ou removidos automaticamente. A aplicação não procura pelo nome tópicos criados manualmente.

## Agrupamento de publicações

As atualizações da **mesma viatura e chamada** recebidas em até três segundos são agrupadas em uma mensagem. A janela começa no primeiro evento e não é renovada pelas chegadas seguintes. Por exemplo, o empenho, o endereço recebido logo depois e o histórico podem aparecer juntos, com um bloco para cada evento, na ordem de recebimento e com seus horários. Estados intermediários não são substituídos pelo último estado.

Viaturas diferentes ou chamadas diferentes não compartilham a mesma mensagem. Atualizações gerais do recurso sem chamada formam um grupo separado. Uma atualização isolada também aguarda a janela antes de ser enviada. Não é necessário habilitar uma opção: o agrupamento faz parte da publicação normal.

Quando o lote atinge os limites de quantidade/tamanho dos blocos do Discord, ele é dividido em mensagens; o trecho já completo pode ser enviado antes dos três segundos e os eventos seguintes abrem outro lote. O agrupamento não corta os eventos para fazê-los caber juntos. Os limites de formatação individual das mensagens continuam valendo. A janela não é uma garantia de entrega em três segundos: rede, fila e limites HTTP 429 podem atrasar a publicação.

A tabela e o CSV continuam recebendo **cada evento individualmente**, sem esperar o agrupamento. Cada atualização é incorporada ao arquivo de fila antes da espera. Se fechar ou desconectar durante a janela, o lote permanece salvo; ao iniciar novamente com o mesmo destino e filtro, use **Reenviar pendentes** para retomá-lo. O reenvio não antecipa a janela de lotes ainda em coleta na sessão atual. Pendências anteriores ao agrupamento permanecem compatíveis e são reenviadas individualmente.

## Respeito ao filtro de recursos

Os dois filtros são enviados ao servidor CAD: `envAlterarFiltroChamada` e `envAlterarFiltroRecurso`. As cargas iniciais também são pedidas com os respectivos IDs. Portanto, o leitor não baixa deliberadamente todas as ocorrências para depois escolher localmente. Os eventos espontâneos podem ser mais amplos que os filtros; por isso existe uma conferência adicional local do filtro de recursos antes de publicar.

O filtro de chamadas continua necessário no fluxo atual para receber os resumos da ocorrência, incluindo endereço, natureza e alterações. Publicar somente chamadas ligadas às viaturas é uma regra de publicação e não substitui essa assinatura. Se uma chamada vinculada não chegar pelo filtro escolhido, seus dados completos podem não estar disponíveis, mesmo que o recurso informe o número da chamada. A consulta complementar de histórico por ID não substitui o recebimento contínuo dos demais dados da ocorrência. O protocolo do cliente original disponível contém consultas por ID (`envRecuperarResumoChamada`/`envRecuperarChamada`), mas não foi encontrada uma operação de assinatura/cancelamento individual que garanta notificações futuras. Portanto, a aplicação mantém o filtro de chamadas e não cria conexões extras nem tenta substituir o filtro por consultas periódicas. Sem o evento da chamada, ou se uma edição antiga não vier sinalizada nem mudar os registros/quantidade do resumo, não é possível garantir detectar a alteração do histórico.

A aplicação aplica localmente os critérios do filtro escolhido: o recurso precisa pertencer a uma das unidades listadas no filtro e, quando houver restrição de tipos, a um dos tipos permitidos. A regra vale para carga inicial e eventos espontâneos, pois o servidor pode enviar resumos de outros recursos. Recursos sem identificação suficiente de unidade/tipo não são publicados. Se um recurso deixa de pertencer ao filtro, deixa de gerar mensagens, inclusive quando a ocorrência associada muda. O log informa o filtro aplicado ao iniciar.

Pendências antigas identificadas como **SITUAÇÃO ATUAL** são preservadas com extensão `.initial-state`, fora da fila. Essa separação não apaga mensagens já publicadas no Discord. Envios pendentes de atualizações continuam disponíveis para retomada e também são conferidos contra o filtro atual. Arquivos de outro filtro ou de versões que não registravam unidade/tipo são preservados com extensão `.outside-filter`, fora da fila de reenvio. O histórico local e tópicos já publicados não são apagados por essa conferência.

## Ler as mensagens e consultar para REDS

O tópico identifica a viatura. O título de cada bloco identifica o número da chamada e a atualização, sem repetir esses dados no corpo ou em um texto acima do bloco. No novo empenho/despacho, a mensagem apresenta o contexto completo. As atualizações seguintes mostram somente os campos alterados e o horário do estado CAD; os links de navegação aparecem quando o endereço ou as coordenadas mudam, incluindo sua primeira disponibilidade. Mudanças apenas de referência ou complemento não repetem os links. Dados que chegam depois de um despacho sem endereço também são apresentados. O histórico local/CSV conserva o estado completo de cada evento.

Os campos disponíveis são:

- **UNIDADE**: descrição e ID da unidade à qual o recurso pertence.
- **RECURSO**: disponível, indisponível, empenhado etc.
- **EMPENHO**: despachado, a caminho, no local, em encerramento, encerrado etc.
- **OCORRÊNCIA**: situação da chamada informada pelo CAD.
- **ENDEREÇO**, complemento, referência e natureza, quando recebidos.
- Horário do estado no CAD e horário de recebimento da mensagem.

“Em encerramento” e “encerrado” são estados diferentes. Um recurso sumir de uma lista não é interpretado como encerramento. O endereço pode chegar depois do empenho, ou não estar no filtro selecionado; nesse caso a mensagem informa a ausência. Quando a chamada chega, os empenhos conhecidos recebem os dados relacionados. O resumo de chamada não contém contatos; após um novo empenho, o leitor consulta separadamente os envolvidos, conforme descrito abaixo. Campos estruturados de autoria e identificação de militares não são exportados. O texto livre do histórico é reproduzido como recebido e pode conter essas informações.

### Contatos dos envolvidos no despacho

Quando uma chamada recebe um novo empenho em uma viatura acompanhada, o leitor consulta seus envolvidos na conexão existente. Se o servidor autorizar, publica **CONTATOS • ENVOLVIDOS NO DESPACHO**, com o número do chamado e uma lista resumida de todos os envolvidos com nome ou telefone preenchido. Quem estiver marcado como solicitante aparece como **Solicitante**. Apenas nome, telefone e essa indicação são utilizados; CPF, documentos e outros campos cadastrais não entram na publicação. Listas grandes são divididas em blocos.

O telefone permanece visível para copiar e usar no discador. Quando contém DDD e um número brasileiro reconhecível, aparece também **WhatsApp**, usando o [formato oficial de link](https://faq.whatsapp.com/5913398998672934/?locale=pt_BR). O link não verifica se o número tem conta no WhatsApp. Números sem DDD não recebem um DDD inventado. Não há botão de ligação nesta versão.

A consulta é compartilhada entre viaturas da mesma chamada, paginada quando necessário e executada na mesma fila serial do histórico. Não abre outra conexão nem faz consultas periódicas. Os contatos ficam em memória durante a sessão para atender novos empenhos da mesma chamada; não há atualização automática do cadastro de envolvidos. A carga inicial de viaturas não provoca essa consulta.

Os contatos podem ser agrupados com o despacho na janela de três segundos ou aparecer em um complemento depois dele. A resposta só é publicada para vínculos ainda ativos e dentro do filtro. Se não houver envolvidos com nome/telefone, não é criada uma mensagem vazia. Se a consulta for recusada ou expirar, a aba **Conexão e publicação** mostra `[ENVOLVIDOS]`; o monitoramento continua e não há repetição automática da consulta para aquela chamada na sessão. A aceitação depende das permissões CAD do usuário; não é necessário configurar uma credencial adicional.

Os contatos publicados também ficam no CSV e nas pendências locais correspondentes.

### Histórico da ocorrência no tópico da viatura

Quando um novo empenho é recebido após a carga inicial, o tópico recebe o estado do empenho e os registros de histórico da ocorrência. A aplicação aproveita os registros presentes no resumo e consulta as demais páginas pelo pedido de leitura `envRecuperarResumosHistoricosChamada`, equivalente à consulta de resumos do cliente original. A consulta respeita as permissões CAD da conta conectada.

Enquanto o empenho permanecer ativo, a aplicação consulta novamente o histórico somente quando o CAD sinaliza `SALVAR_HISTORICO_CHAMADA`, a quantidade de históricos muda ou o conteúdo/data dos registros presentes no resumo muda. Alterações apenas de endereço, natureza/classificação, situação da ocorrência ou estado do empenho não provocam nova consulta. Uma alteração recebida para uma chamada com várias viaturas gera uma consulta compartilhada, com publicação nos tópicos vinculados. Não há consultas periódicas. Registros repetidos não geram novas mensagens na sessão; textos novos ou modificados são publicados com **HISTÓRICO DA OCORRÊNCIA**, número da chamada e horário do registro CAD. Textos longos são divididos em partes. Se uma viatura já estava empenhada na carga inicial, não há publicação nesse momento; seu histórico passa a ser consultado quando chega um sinal de alteração do histórico ou um novo vínculo de empenho; uma alteração geral da chamada não inicia essa consulta por si só. Encerramento do empenho ou saída do filtro interrompe o envio de histórico para aquele vínculo.

Uma consulta de histórico recusada ou sem resposta é indicada por `[HISTORICO]` e não encerra o monitoramento. Sem metadados de paginação, somente a página recebida é processada e o log informa a limitação. O estado da viatura pode chegar antes do histórico; não há garantia de uma mensagem única contendo ambos. Registros obtidos por consulta podem ser anteriores à abertura do leitor e servem de contexto para o atendimento; isso não publica a situação inicial das outras viaturas.

Chamadas recebidas também aparecem no histórico local quando não há viatura associada. No Discord, as publicações são organizadas pelos recursos: uma chamada sem associação não cria um tópico próprio.

Selecione um evento na tabela para consultar o conteúdo. **Exportar CSV** copia a cronologia local completa, incluindo sessões anteriores, para o destino escolhido. A tabela mantém até 5.000 eventos em memória e recebe atualizações em lotes. Em uma carga muito intensa, a fila visual também é limitada aos eventos mais recentes; o histórico CSV continua registrando os eventos recebidos, mesmo que nem todos apareçam na tabela. Pesquise pelo número da chamada ou prefixo no Discord ou no CSV. Os registros representam estados recebidos durante a sessão e históricos retornados pelas consultas; não substituem a conferência dos dados no CAD para elaboração do REDS.

## Identificar atualizações no Discord

O título de cada bloco mostra o número da chamada e o tipo de atualização. O ícone identifica o tipo de atualização. A cor é fixa por chamada, calculada a partir dos últimos oito dígitos: despacho, histórico, contatos e demais atualizações da mesma chamada usam a mesma cor, inclusive em tópicos de outras viaturas. Cores parecidas ou iguais entre chamadas diferentes são possíveis; o número continua sendo a identificação principal. Eventos sem número usam cinza.

- 🚨 **DESPACHADO** — contexto completo do chamado.
- 🚒 **A CAMINHO**, **EM DIREÇÃO** e **EM RETORNO**.
- 📍 **NO LOCAL** e **NO LOCAL INTERMEDIÁRIO**.
- ⏳ **EM ENCERRAMENTO**.
- ✅ **ENCERRADO**.
- 📝 **HISTÓRICO DA OCORRÊNCIA** — texto do registro.
- ☎️ **CONTATOS • ENVOLVIDOS NO DESPACHO**.

O tópico identifica a viatura; o corpo traz somente os campos alterados depois do despacho. O horário de origem aparece como **HORÁRIO CAD** ou **REGISTRO CAD**. O rodapé curto **CAD • Recebido** acompanha o horário de recebimento exibido pelo Discord. Histórico recebido agora pode ter sido registrado antes; use seu horário CAD para entender a cronologia.

Não há JSON ou identificadores técnicos no rodapé. [FORMATO_MENSAGENS.md](FORMATO_MENSAGENS.md) descreve a apresentação e seus limites para uma futura consolidação. O bot opcional oferece relatórios por filtro e limpeza de mensagens; veja [BOT_DISCORD.md](BOT_DISCORD.md). Pendências antigas com o rodapé técnico são convertidas para o rodapé curto ao reenviar; mensagens já publicadas permanecem como estão.

## Endereço e navegação

As mensagens com endereço incluem links para **Google Maps** e **Waze**. Coordenadas válidas têm prioridade; quando não estão disponíveis, os links pesquisam o endereço recebido. Confirme o destino no navegador de trânsito.

## Salvar configurações

No campo **Perfil local**, selecione um perfil existente ou digite um nome novo (letras sem acento, números, `_` ou `-`). A senha de proteção fica ao lado da configuração do perfil.

- **Salvar perfil** guarda servidor, porta, usuário e filtros selecionados. O webhook é salvo criptografado quando preenchido.
- **Salvar senha CAD criptografada (opcional)** permite guardar também a senha CAD. Marque a opção e digite a senha antes de salvar. Sem a opção, a senha CAD não é persistida; salvar desmarcada remove uma senha anteriormente salva.
- **Abrir perfil** usa a senha de proteção para recuperar os segredos e preencher os campos, ocultos. Não conecta automaticamente. Conecte em seguida para atualizar o catálogo; os filtros salvos são selecionados por ID.
- **Remover perfil** pede confirmação e apaga somente o arquivo do perfil escolhido. Não apaga histórico, tópicos nem mensagens pendentes e não encerra uma sessão já conectada.

Use uma senha de proteção de pelo menos oito caracteres, preferencialmente longa e diferente da senha CAD. Webhook, token do bot e senha CAD opcional usam AES-GCM/PBKDF2, cada segredo com sal e IV próprios. A senha de proteção nunca é gravada. Utilizar a mesma senha CAD como proteção é tecnicamente possível digitando-a nos dois campos, mas a aplicação mantém os campos separados: se essa senha for descoberta, ela permite tanto entrar no CAD quanto abrir os segredos do perfil. Alterar a senha no CAD não muda a proteção do arquivo local; para isso, abra com a senha antiga e salve novamente com a nova senha de proteção.

O log informa quais segredos foram salvos ou recuperados. Perfis antigos que guardavam somente o webhook continuam compatíveis. Sem senha CAD salva, digite-a para conectar. A autenticação bem-sucedida e a desconexão limpam o campo CAD; se quiser salvar a senha depois disso, digite-a novamente antes de salvar. Em falha de conexão ou autenticação, o campo permanece para conferência. Abrir um perfil preenche a senha salva, ou limpa o campo quando não há senha armazenada.

Salvar com o webhook vazio remove o webhook desse perfil. A publicação precisa ser habilitada novamente a cada abertura. A opção de desativar validação TLS começa marcada a cada abertura da interface e não é restaurada pelo perfil. Abrir e salvar perfis executam a criptografia em segundo plano para manter a interface responsiva.

Os dados ficam em `~/.java-cad-reader/`:

| Caminho | Conteúdo |
| --- | --- |
| `perfis/*.properties` | Preferências, webhook cifrado e senha CAD cifrada opcional |
| `historico.csv` | Cronologia operacional local |
| `discord-*/topics.properties` | Tópicos e último estado confirmado por empenho/recurso |
| `discord-*/*.pending` | Mensagens/lotes não confirmados aguardando envio ou retomada |
| `discord-*/*.outside-filter` | Envios isolados por incompatibilidade ou falta de identificação do escopo |
| `discord-*/*.initial-state` | Cargas iniciais antigas preservadas fora da fila |
| `discord-*/publisher.lock` | Bloqueio de publicação simultânea local |

O token do webhook não é incluído no histórico nem na fila; a fila e o CSV contêm dados operacionais em texto simples. Em sistemas POSIX, arquivos gravados pela aplicação recebem permissão de leitura/escrita somente para o proprietário. Use a pasta de dados de uma conta pessoal do sistema operacional.

## Falhas de publicação

O rodapé mostra o estado atual da publicação e a contagem de **Pendentes**, correspondente às mensagens/lotes aguardando envio, não à quantidade de eventos dentro deles. Durante a janela, o estado indica que as atualizações estão sendo agrupadas. A tabela inclui a referência inicial, que não entra na fila Discord. A presença de um evento confirma recebimento do CAD; a entrega ao Discord é confirmada por **publicação confirmada**, com viatura, chamada, quantidade de eventos agrupados e tópico no log. Atualizações locais de ocorrência sem viatura associada não entram na fila Discord.

A fila é persistida antes do envio. Ao receber HTTP 429 (limite de envio), a aplicação aguarda `Retry-After`/`retry_after` e retoma automaticamente, na ordem de gravação. Clicar em **Reenviar pendentes** não antecipa esse prazo. Um limite sem prazo válido usa uma espera de cinco segundos.

Se o Discord confirmar que um tópico salvo não existe mais (código 10003 em resposta HTTP 400/404), o vínculo inválido é removido e a aplicação tenta criar um novo tópico para a viatura. Webhook inválido, falta de permissão ou falha de rede não acionam essa recriação.

As linhas `[OCORRENCIA] ATUALIZACAO` confirmam recebimento do CAD, não publicação. A aba de conexão mostra também `[PUBLICACAO]` para cada chamada recebida: referência inicial incompleta, ausência de viatura acompanhada com empenho ativo conhecido, ausência de mudança nos campos publicados ou quantidade de eventos gerados para os vínculos. Consultas de histórico são indicadas separadamente nessa decisão. Evento gerado não é confirmação de entrega; procure `Discord: publicação confirmada`. Se a publicação estiver desativada na sessão, o diagnóstico informa que o evento ficou somente local.

**Limpar pendentes** descarta, após confirmação, as mensagens que aguardavam envio para o webhook em uso. Não apaga o CSV, os tópicos ou mensagens já publicadas. Novas atualizações recebidas depois do clique são preservadas e a publicação continua; não é necessário reconectar ao CAD. Um envio já em andamento pode terminar antes da limpeza. O descarte não pode ser desfeito pela aplicação. Limpar a fila não apaga a referência de estados já recebidos: uma repetição idêntica do CAD não recria uma mensagem descartada; somente uma nova mudança nos campos acompanhados gera outro evento. Para acessar a fila, inicie o monitoramento com publicação habilitada. A limpeza mantém o prazo de espera de um HTTP 429; ela não contorna o limite do Discord.

Cada envio Discord encerra sua conexão HTTP. Uma publicação após um período ocioso usa uma conexão nova. A mensagem de falha informa a **etapa** quando disponível: conexão TCP/TLS, envio do conteúdo, espera da resposta HTTP ou leitura da resposta. HTTP de sucesso sem identificação do canal é informado como confirmação incompleta. Essa informação ajuda a localizar o problema sem expor o webhook; não confirma por si só que a mensagem deixou de chegar ao Discord.

Uma **falha local/de rede (IOException)** indica que a operação não terminou com confirmação local. Pode envolver conexão, timeout ou acesso aos arquivos. O diagnóstico identifica timeout, DNS, TLS, conexão interrompida ou falha de arquivos quando a cadeia de exceções permite, sem mostrar a URL secreta. Se não houver causa identificável, não é possível concluir o motivo somente pela mensagem. O Discord pode ter aceitado o envio antes da interrupção; confira o tópico antes de reenviar. Enquanto a fila estiver pausada, novas atualizações continuam sendo guardadas e aumentam a contagem de pendências.

Nas demais falhas a fila fica **PAUSADA**, preservando as mensagens; o CAD continua sendo acompanhado. O diagnóstico mostra HTTP/código Discord quando disponíveis, sem expor a URL do webhook. Confira destino, permissões ou conexão conforme o erro, confira as mensagens já existentes no canal e use **Reenviar pendentes** após corrigir a causa. Pendências válidas de uma sessão anterior também aguardam retomada explícita.

Uma interrupção após o Discord aceitar a mensagem, mas antes de responder ou antes da gravação local, pode gerar duplicação no reenvio, inclusive na criação do tópico. Não há garantia de entrega exatamente uma vez. A deduplicação local elimina estados iguais ao último confirmado, mas não coordena instalações distintas. Não exclua a pasta de tópicos para resolver uma falha: isso pode criar novos tópicos para recursos já publicados.

Para arquitetura e testes, consulte [DESENVOLVIMENTO.md](DESENVOLVIMENTO.md) e [TESTES.md](TESTES.md).

### Contexto inicial e novos registros de histórico

A primeira consulta de histórico de cada vínculo entre viatura e chamada aguarda todas as páginas e publica um bloco **HISTÓRICO INICIAL**, em ordem cronológica, com a data CAD de cada registro. Os registros já presentes no resumo entram nesse mesmo contexto, sem mensagens individuais repetidas. O texto é reproduzido, sem resumo automático. O contexto pode conter registros posteriores ao despacho que já existiam quando a consulta foi feita.

Após esse contexto, registros novos ou modificados geram eventos individuais **HISTÓRICO DA OCORRÊNCIA**; a janela de agrupamento de três segundos continua valendo. Conteúdo longo é dividido em partes para respeitar os limites do Discord, sem descarte. Se a consulta falhar após receber algum conteúdo, publica-se o disponível com indicação de consulta incompleta. Não são acrescentadas consultas ao CAD para fazer essa consolidação.

O reader reconhece também os estados de empenho **EM CONTROLE** e **SUSPENSO**. O estado `ED` é apresentado como **EM DIREÇÃO**, conforme o nome no CAD original; não se presume um destino intermediário que não veio informado.

## Comandos do bot Discord

Use **Configurar bot** para informar token próprio, servidor, fórum e cargo autorizado, mesmo antes de conectar ao CAD. Depois da conexão CAD e da carga dos filtros, clique em **Iniciar bot**. O indicador **Bot:** mostra o estado da conexão; **Ajuda** explica a configuração e os comandos. O token pode ser salvo criptografado no perfil. A configuração no Discord, permissões e limites estão em [BOT_DISCORD.md](BOT_DISCORD.md).

- `/cad-reader totais-por-natureza filtro:` oferece busca por nome, total por natureza e detalhamento por unidade de área BM. A resposta é privada, com **Publicar aqui** para compartilhar no local do comando, sem nova consulta CAD.
- `/cad-reader limpar antes-de:AAAA-MM-DD` mostra uma prévia e pede confirmação para apagar mensagens de todos os autores anteriores à data, no tópico atual do fórum configurado. O corte é exclusivo, à meia-noite de Brasília; escolha a data desejada, por exemplo três dias atrás. Não identifica chamados ainda abertos.

O bot só funciona com a aplicação aberta e conectada. A limpeza é irreversível e inclui fotos e textos das equipes. Não altera dados no CAD.


## Acompanhamento e administração Web

1. A janela Java continua disponível e inicia também o servidor Web na porta **8080**. Use **Abrir mapa Web** para abrir o navegador; se a porta estiver ocupada, esse botão pode escolher outra porta e informar o endereço. Para executar sem ambiente gráfico, use `java -jar target/java-cad-reader-1.0.0.jar --web` (ou `--web 8090` para escolher a porta). O acompanhamento fica em `http://IP:8080/` e a administração em `http://IP:8080/admin`. O servidor escuta em todas as interfaces. Encerrar o JAR encerra o serviço; no modo desktop, fechar a janela também o encerra.
2. Em `/admin`, entre/crie um perfil, configure o CAD e o Discord, salve, conecte, selecione os dois filtros e inicie o monitoramento. A janela Java também continua usando o mesmo serviço de conexão. A Web usa a mesma sessão, sem consultas periódicas ao CAD. Ela verifica a versão do estado local a cada três segundos, sem sobrepor pedidos, e pausa quando a aba fica oculta. Sem alteração, o servidor responde HTTP 304 sem reenviar o retrato. A projeção em memória e os marcadores sem mudança são reaproveitados. A busca abrange apenas as ocorrências recebidas pelo filtro atual; itens sem coordenadas continuam na lista. Ocorrências classificadas deixam o mapa. Ao desconectar, o estado anterior é identificado como desatualizado e novos cálculos ficam bloqueados.
   Os filtros de grupo (primeira letra do código), natureza, vínculo com viatura e texto atualizam lista e mapa juntos. Os seletores aceitam pesquisa e mostram opções com resultados no recorte dos demais filtros. Passe o mouse no ponto para um resumo lateral, clique para abrir os detalhes e dê dois cliques para selecionar. Cor e letra identificam o grupo da natureza; **V** indica viatura identificada, **—** ausência de vínculo observado e **?** identificação pendente. Ausência de vínculo observado depende dos dados recebidos pelo CAD e não comprova ausência de atendimento.
3. Na administração, na aba **Unidades**, cadastre cada base com nome e endereço ou coordenadas. O ID da unidade CAD pode ser associado depois. Bases sem coordenadas ficam pendentes e não entram no mapa/Valhalla, mas podem abrir a rota por endereço no Google Maps. A administração de **Perfis de veículo** fica em outra aba. Os IDs conhecidos dos recursos recebidos são sugeridos. Clique em adicionar/atualizar para incluir o formulário na lista e em **Salvar cadastro** para gravar. A unidade administrativa pode ter mais de uma base; a origem adotada é sempre a base cadastrada, não uma posição GPS da viatura.
4. Os perfis iniciais **Carro padrão** e **Caminhão padrão** têm valores físicos explícitos, editáveis. É possível criar outros perfis. Campos não preenchidos em um perfil personalizado seguem os padrões do servidor. A comparação usa ocorrência, bases e perfis, sem seletor de tipo de recurso CAD. A seleção de perfil de rota é manual; não determina aptidão operacional automaticamente.
5. Selecione uma ocorrência. Cada base oferece **Abrir no Google Maps**, em outra aba, da base à ocorrência no modo carro, sem chave de API. O Google calcula sua própria rota e seus valores não são importados para a aplicação.
6. O Valhalla é **opcional e começa desconectado**, inclusive após reabrir a aplicação. Na administração, na página **Roteamento**, informe o endereço e clique em **Conectar** para habilitar os cálculos. É possível trocar a URL por um servidor próprio HTTP/HTTPS, inclusive na rede interna e com prefixo de caminho. Salvar outro endereço desconecta o anterior. Erros ou ausência do motor não bloqueiam o mapa, os cadastros, Google Maps, CAD ou Discord.
7. **Comparar unidades** usa a [matriz do Valhalla](https://valhalla.github.io/valhalla/api/matrix/api-reference/), com as bases nas origens e a ocorrência no destino: uma consulta por perfil a cada lote de até 25 bases, serializada e com cache de cinco minutos. Todos os resultados do lote são apresentados juntos. Pares inalcançáveis ou servidor sem suporte à matriz mostram erro, sem substituir a matriz por consultas individuais. Clique no tempo para consultar e desenhar somente esse trajeto; a geometria não é solicitada para todas as bases. Falhas de um percurso são exibidas sem inventar distância em linha reta ou substituir silenciosamente caminhão por carro. As estimativas não incluem mobilização e não pressupõem trânsito em tempo real. Recursos disponíveis são os que o CAD informou como disponíveis e sem restrição textual de empenho, limitados ao filtro de unidades/tipos; disponibilidade não comprova aptidão, equipe ou presença na base.

### Marcadores das bases

As bases usam um escudo vermelho com capacete de bombeiro amarelo no interior, sem chama, com nome permanente em tamanho reduzido abaixo. É uma representação visual de unidade/base, sem reproduzir o brasão oficial do CBMMG.

### Acesso administrativo e perfis

No primeiro acesso, informe um nome de perfil (letras sem acento, números, `_` ou `-`) e uma senha de pelo menos oito caracteres. Se o perfil existir, a senha recupera os dados salvos; se não existir, cria uma configuração nova. O nome do perfil é independente do usuário CAD. O perfil ativo fica reservado até reiniciar o JAR: sair do navegador, desconectar do CAD ou perder a conexão não permite que outro perfil assuma a administração. Outras sessões precisam do mesmo nome e senha. Sessões expiram em 12 horas; sair invalida somente a sessão atual.

Antes de ativar um perfil, você pode importar um arquivo `.properties` exportado pela aplicação. Escolha um **nome novo** e use como senha do perfil a senha capaz de recuperar os dados protegidos do arquivo. Se a descriptografia falhar, a importação é recusada sem criar o perfil. Arquivos sem dados protegidos aceitam a senha escolhida. O perfil original é preservado. A exportação mantém o formato criptográfico atual: somente webhook, token do bot e senha CAD opcional são cifrados. A senha de acesso Web é verificada por hash em arquivos separados em `~/.java-cad-reader/admin-auth/`, sem mudar o formato portátil dos perfis em `perfis/`.

As APIs de configuração, eventos e requisições exigem sessão autenticada, além das verificações de origem e token das operações POST. O acompanhamento continua sem login. A edição de conexão/Discord exige desconectar e parar o bot; versões da configuração impedem sobrescrita por uma sessão desatualizada. Se já houver uma sessão iniciada pela janela Java antes do primeiro login Web, desconecte-a para ativar o perfil Web. A configuração de bases e perfis de veículo continua compartilhada pela instalação.

Ao lado do símbolo da ocorrência aparecem os prefixos das viaturas vinculadas e os estados de empenho recebidos: no local, a caminho, em direção, em retorno e outros. As siglas seguem as mesmas interpretações de `CadMonitor.label`; códigos desconhecidos permanecem explícitos. As etiquetas não representam GPS das viaturas. A legenda começa recolhida e pode ser expandida.

O mapa tem controles para recolher os painéis de ocorrências/atendimento e entrar em tela cheia (Esc sai). Ao entrar em tela cheia, os painéis são ocultados e podem ser reabertos pelos controles. As explicações de funcionamento ficam nos ícones **?**, acessíveis por mouse ou foco do teclado.

A página **Dashboard** resume as ocorrências ativas do filtro CAD, com totais, grupos/naturezas e vínculo com viaturas. Ocorrências com várias naturezas podem aparecer em mais de uma categoria; os filtros visuais do mapa não alteram o dashboard. **Eventos**, na área administrativa autenticada, acompanha os mesmos eventos e registros da janela Java, incluindo eventos somente locais, encaminhamento à fila, confirmação de publicação e falhas/pausas. Mantém até 1.000 registros na memória desta execução, inclusive durante reconexões; não é uma importação do histórico antigo. A confirmação do Discord pode agrupar vários eventos da mesma viatura/chamada. As atualizações trazem somente eventos posteriores ao cursor local. A lista exibe 100 registros por vez, com opção de mostrar mais; os detalhes só são montados ao expandir um registro. **Administração** permite configurar e controlar CAD/Discord pelo navegador, incluindo filtros, bot, fila de publicações e perfis. Bases, perfis de veículo e conexão Valhalla também são administrados em `/admin`.

A janela **Detalhes e histórico recebido**, acessível pelo mapa e pelos vínculos de ocorrências em outras telas, organiza resumo, endereço/complemento/referência, viaturas vinculadas, **Histórico** e **Envolvidos**, com atalhos para o mapa. **Viaturas** lista os recursos recebidos, unidade, tipo, placa, situação, restrição e empenhos conhecidos. Esses dados são reaproveitados do fluxo Java: abrir esses detalhes ou navegar em Viaturas **não solicita histórico, envolvidos ou resumos ao CAD**. Se o conteúdo não chegou, não é preenchido por uma consulta automática. O cache de detalhes guarda até 50 ocorrências, 200 históricos e 100 contatos por ocorrência, 8.000 caracteres por campo de texto e 4 milhões de caracteres no total. Limites atingidos e consultas incompletas são sinalizados. O cache é limpo ao iniciar outra conexão/monitoramento e não é gravado em disco pela Web.

**Requisições CAD**, na área administrativa autenticada, observa os pontos reais de envio/resposta do leitor XMPP. Mostra requisições iniciadas, respostas CAD com status de sucesso, recusas, tempo limite, falhas de envio, interrupções, pendências e tempo médio das respostas bem-sucedidas. Cada página é uma requisição; pedidos locais agrupados na fila só entram no contador quando são iniciados. Relatórios do bot também entram. Atualizações espontâneas são contadas separadamente. Pings XMPP, autenticação TLS/SASL e consultas HTTP locais da Web não são contados como requisições CAD. Os contadores acumulam durante a execução do Java, incluindo reconexões; a tabela mantém até 300 requisições recentes. Não guarda corpos XML, senhas, tokens ou webhooks. Abrir o painel não envia solicitações ao CAD.

O cabeçalho tem indicadores por ícone para **CAD** e **Valhalla**, com descrição ao passar o mouse ou focar com o teclado. Os indicadores abrem a área de administração para consultar a comunicação CAD ou configurar o roteamento. O indicador Valhalla reflete a conexão explicitamente habilitada na aplicação; não faz sondagens periódicas ao serviço. Os ícones são SVG locais, sem pacote ou serviço externo adicional.

O mapa enquadra automaticamente as ocorrências e as bases cadastradas ao abrir e ao mudar os filtros, incluindo pontos distantes. Mesmo sem ocorrências recebidas, as bases com coordenadas entram no enquadramento. O controle **Mostrar todas as unidades** enquadra somente os quartéis; **Ajustar mapa** inclui ocorrências e bases. O botão com ícone **Centralizar área de articulação — Contagem** centraliza Contagem com zoom 14. Não é preciso remover os pontos distantes para usar esse atalho. O polling de atualizações não reposiciona continuamente o mapa. O antigo padrão salvo no navegador não substitui esse enquadramento automático.

O resumo **Situação das viaturas**, disponível no mapa (recolhível), Dashboard e Viaturas, agrupa disponíveis, empenhadas, indisponíveis e outros estados. Informa também restrições e disponíveis sem restrição informada; um vínculo ativo conhecido prevalece sobre uma situação de disponibilidade inconsistente. Ao desconectar, os dados são identificados como não confirmados.

O Dashboard mostra **ocorrências por unidade de área BM**, usando `idUnidadeArea` e o catálogo já recebido na sessão. Não usa a unidade responsável, a base da viatura ou atendimentos PM para preencher esse dado. Uma ocorrência com mais de uma área pode contar em cada área; ausência é exibida explicitamente.

A aba **Somatório** permite escolher qualquer filtro de ocorrências do catálogo e clicar em **Consultar filtro**, sem precisar iniciar o bot Discord. Reutiliza `CadSummary`, a mesma consulta do bot, mantendo o filtro de acompanhamento e o mapa intactos. A consulta só ocorre por ação explícita; bot e Web compartilham o pedido em andamento e cache de 30 segundos. Há limite de uma consulta Web em andamento. O resultado fica como retrato datado, inclusive ao navegar entre abas, sem atualização automática. O painel Comunicação registra a requisição quando ela é efetivamente iniciada, salvo reaproveitamento de cache. Limites de retorno/paginação incompleta são sinalizados.

O resultado oferece **Copiar texto**, **Copiar imagem** e **Baixar PNG** para colar/anexar manualmente no WhatsApp ou outro aplicativo. A imagem é gerada localmente em canvas, com totais e até 12 categorias por gráfico; o texto contém todos os agrupamentos, inclusive naturezas por área. Nada é enviado ao WhatsApp pela aplicação. A [cópia de PNG](https://developer.mozilla.org/en-US/docs/Web/API/Clipboard/write) depende de suporte/permissão do navegador; em caso de recusa, use o download. Se a cópia de texto não for permitida, a página oferece um campo selecionável para Ctrl+C.

O servidor predefinido é a demonstração pública do Valhalla mantida pela FOSSGIS. É necessário acesso à internet/proxy; não há garantia de disponibilidade nem uso ilimitado. Consulte a [política do serviço](https://valhalla.github.io/valhalla/#demo-server) antes de distribuir para outros usuários. A aplicação envia `X-Client-Id: cad-reader`. Consultas externas contêm coordenadas e parâmetros de veículo, sem histórico, contatos ou número de ocorrência. O controle de camadas permite alternar ruas (OpenStreetMap) e satélite ([Esri World Imagery](https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer)), sem chave configurada na aplicação. Somente a camada selecionada é carregada, com atribuição visível e sem download em massa; o navegador precisa conseguir acessá-los. O motor usa a configuração de proxy HTTPS/HTTP do Java; endereços loopback explícitos não passam por esse proxy. Para servidores da rede interna, ajuste também a configuração de proxy do ambiente conforme necessário.

O cadastro fica em `~/.java-cad-reader/mapa-operacional.json`, fora do repositório, independente dos perfis CAD/Discord. Não é incluído na exportação de perfis existente. Não há dados operacionais de demonstração embutidos na aplicação. Para usar o JAR pronto, não é necessário instalar Node.js, Docker ou Valhalla: apenas Java 11+, navegador e conectividade para os serviços externos escolhidos.

O frontend fica em `frontend/`, com React, TypeScript, Vite, Tailwind CSS e React Leaflet. O GitHub Actions compila o frontend antes do Maven e inclui os arquivos gerados no JAR. Para desenvolvimento com recarga, primeiro inicie o JAR com `--web` na porta 8080 e execute `npm run dev --prefix frontend`; o proxy do Vite encaminha `/api` ao Java. Não use o servidor Vite para distribuir a aplicação. Se a porta Java mudou por conflito, ajuste o destino no `vite.config.ts`.


### Importar e exportar unidades e perfis em CSV

As abas **Unidades** e **Perfis de veículo** possuem botões próprios de **Importar CSV** e **Exportar CSV**. Cada aba mantém seu rascunho ao alternar a navegação e salva apenas sua categoria, preservando o cadastro da outra aba. A exportação contém a lista exibida, incluindo alterações ainda não salvas; use-a também para obter um modelo com os cabeçalhos corretos.

A importação lê UTF-8 com ou sem BOM, separador `;` ou `,`, aspas duplas, aspas escapadas e quebras de linha dentro dos campos. Números podem usar ponto decimal ou vírgula decimal (entre aspas se o delimitador também for vírgula). Não use separadores de milhar. Arquivos de até 256 KB são aceitos. A prévia mostra erros por linha, registros válidos e campos pendentes; qualquer erro impede a aplicação do arquivo inteiro. Não há descarte silencioso das linhas inválidas.

- **Combinar:** adiciona IDs novos e atualiza registros com o mesmo ID; mantém os ausentes no arquivo.
- **Substituir:** usa apenas os registros do arquivo e informa quantos registros atuais ficarão de fora.
- **Aplicar à lista:** altera o rascunho. Clique em **Salvar cadastro** para persistir no computador. **Cancelar importação** mantém a lista anterior.

O `id` é local ao cadastro. Não o confunda com `id_unidade_cad`. Se `id` estiver vazio, um identificador será gerado; importar novamente um CSV sem IDs criará novos registros. Para transferir ou atualizar registros existentes, preserve os IDs exportados. O máximo é 200 unidades e 30 perfis.

Cabeçalho de unidades:

```csv
id;nome;id_unidade_cad;endereco;latitude;longitude;fonte;consultado_em;observacoes
```

`nome` é obrigatório. Informe endereço ou o par latitude/longitude. O vínculo CAD, as coordenadas e os metadados podem ficar pendentes; não são preenchidos com zero nem deduzidos pelo nome. Latitude/longitude precisam ser preenchidas juntas. A fonte deve ser URL HTTP/HTTPS e `consultado_em` usa `AAAA-MM-DD`.

Cabeçalho de perfis:

```csv
id;nome;modo;altura_m;largura_m;comprimento_m;peso_t;carga_eixo_t
```

`nome` e `modo` são obrigatórios. Modos aceitos: `auto`/`carro` e `truck`/`caminhão`. As características são opcionais e, quando presentes, devem ser positivas; medidas em metros e pesos em toneladas. Colunas técnicas antigas (`name`, `unitId`, `address`, `lat`, `lon`, `costing`, `height`, etc.) também são reconhecidas.

A aba **Unidades** oferece um download do [CSV com os seis endereços pesquisados do CBMMG](frontend/public/assets/unidades-cbmmg-rmbh.csv). Fontes e limitações estão em [Pesquisa das unidades da RMBH](docs/unidades-cbmmg-rmbh.md). O arquivo não é carregado automaticamente e não substitui seu cadastro. IDs CAD e coordenadas foram deixados vazios para confirmação local. O conteúdo exportado protege campos textuais contra interpretação como fórmulas por planilhas; a importação reverte esse escape.

Se aparecer `NoClassDefFoundError`/classes incompatíveis ao abrir o Valhalla, feche completamente o Java e reabra o JAR atualizado. Evite recompilar ou substituir o arquivo JAR em uso. O servidor local responde com orientação de reinício para falhas de carregamento de classes; isso não é um diagnóstico de indisponibilidade do serviço Valhalla nem exige alterar o proxy.
