# Bot Discord: relatórios CAD e limpeza de tópicos

O bot é opcional e roda dentro da aplicação Java aberta no computador. Usa um token de bot próprio, diferente da URL do webhook. O webhook continua publicando atualizações das viaturas; o bot atende comandos. Não é necessário hospedar uma página, abrir uma porta pública nem habilitar o intent de conteúdo de mensagens.

## Configurar

1. No [Discord Developer Portal](https://discord.com/developers/applications), crie uma aplicação e obtenha seu token na seção **Bot**. Não use token da sua conta pessoal.
2. Na instalação/OAuth2, use os escopos **bot** e **applications.commands**, para instalação em servidor. Conceda ao bot **Ver canais**, **Ler histórico de mensagens**, **Enviar mensagens**, **Enviar mensagens em tópicos** e **Gerenciar mensagens** nos locais necessários. O bot não precisa ser administrador. Não configure uma URL de endpoint de interações: esta implementação recebe comandos pelo Gateway.
3. Ative o modo desenvolvedor do Discord para copiar o ID do servidor, o ID do fórum das viaturas e, opcionalmente, o ID de um cargo autorizado.
4. Abra o CAD-Reader e clique em **Configurar bot**. Preencha os dados e confirme; isso pode ser feito antes de conectar ao CAD e não inicia o bot. **Cancelar** preserva os valores anteriores. Use **Salvar perfil** para guardar o token criptografado e os IDs.
5. Conecte ao CAD, aguarde o catálogo de filtros e clique em **Iniciar bot**. O indicador **Bot:** mostra conexão, retomada, falha ou parada. O início registra `/cad-reader` apenas no servidor informado.
6. Nas configurações do servidor Discord, em **Integrações**, autorize o cargo desejado a usar o comando da aplicação. O comando é registrado restrito por padrão. A autorização do Discord e a autorização local precisam permitir o uso.

Administradores e membros com **Gerenciar servidor** podem consultar. Outros membros precisam ter o cargo configurado no Java. Para limpar, o membro precisa também de **Gerenciar mensagens** no tópico. Para **Publicar aqui**, precisa poder publicar no destino. Ajuste também o acesso dos cargos aos canais: os relatórios seguem as permissões CAD da conta conectada no computador, não as credenciais individuais de quem executa o comando no Discord.

O token pode ser salvo no perfil local com a mesma senha de proteção dos demais segredos; é criptografado e recuperado ao abrir o perfil. Salvar o perfil não inicia o bot automaticamente. Use **Parar bot** para encerrá-lo. Desconectar do CAD também encerra o bot. Execute uma única instância por token; há trava contra duplicação no mesmo computador, mas não entre computadores diferentes.

## Consultar um filtro

Use `/cad-reader totais-por-natureza filtro:` e digite parte do nome. As sugestões mostram **Nome (ID)** dos filtros disponíveis à conta CAD conectada. O Discord mostra até 25 sugestões; refine a busca quando necessário.

A resposta é privada e contém:

- Data/hora da consulta, no horário de Brasília.
- Total de chamadas únicas e quantitativo por natureza.
- Abaixo, total e naturezas por **unidade de área do atendimento BM**.

A unidade de área não é substituída pela unidade responsável pelo atendimento. Quando o catálogo não contém o nome, aparece o ID; quando o campo não veio, aparece área não informada. Uma chamada com várias naturezas pode aparecer em mais de uma categoria. O total geral conta cada ID de chamada uma única vez. Ao atingir 1.000 chamadas, o relatório avisa que pode estar incompleto.

Clique em **Publicar aqui** para tornar o resultado visível no mesmo canal ou tópico do comando. O resultado existente é publicado sem consultar o CAD novamente; relatórios longos são divididos em mensagens, sem corte de conteúdo. Menções automáticas ficam desativadas. Só quem pediu pode usar o botão, uma única vez, por até dez minutos. **Cancelar** descarta a ação de publicação.

A consulta é de leitura, com `envRecuperarResumosChamadasAtivas` e o ID do filtro solicitado. Não envia `envAlterarFiltroChamada`, não cria outra conexão CAD e sua resposta fica separada do monitor/publicador das viaturas. Consultas simultâneas do mesmo filtro compartilham o pedido; o último resultado é reaproveitado por 30 segundos, mantendo o horário original. Só uma consulta de relatório fica em andamento; outro filtro deve aguardar. Não há consultas periódicas de relatório.

A separação do monitoramento foi verificada com broker simulado. O comportamento do servidor de produção para consultas de filtros distintos ainda precisa ser observado no primeiro uso autorizado; a implementação não troca o filtro ativo.

## Apagar mensagens antigas de um tópico

Dentro do tópico de uma viatura, use:

`/cad-reader limpar antes-de:2026-10-09`

Esse exemplo seleciona mensagens **anteriores a 09/10/2026 às 00:00, horário de Brasília**. Mensagens do próprio dia 9 em diante permanecem. Escolha a data desejada, por exemplo três dias atrás. Datas futuras são recusadas.

O bot primeiro consulta as mensagens, apresenta a quantidade e oferece **Confirmar exclusão** e **Cancelar**. A confirmação pertence ao autor do comando, expira em dez minutos e só pode ser usada uma vez.

**A limpeza apaga mensagens de todos os autores, incluindo publicações CAD, textos, fotos e outras mídias. É irreversível.** Não há cópia dessas mídias no CSV do reader. O corte usa a data de publicação da mensagem no Discord, não a data do registro CAD ou do encerramento da ocorrência. Uma ocorrência antiga ainda em aberto pode ter seu contexto antigo apagado: o prazo de três dias reduz a exposição de mensagens recentes, mas não identifica ocorrências ativas.

A limpeza só funciona dentro de tópicos do fórum configurado. Não exclui o fórum nem o tópico como objeto; mensagens iniciais, fixadas e de equipes também entram na seleção por data, se o Discord permitir excluí-las. O bot não altera o CAD, a fila de publicações, os arquivos CSV ou o estado de acompanhamento. Pendências antigas reenviadas depois podem reaparecer no tópico.

A prévia pagina de 100 em 100 mensagens, buscando diretamente as anteriores ao corte, com limite de 20.000 por operação. Se exceder esse limite, recusa a operação sem apagar nada. Após confirmar, mensagens recentes elegíveis são apagadas em lotes de até 100; mensagens com mais de 14 dias são apagadas individualmente, conforme a API Discord. A quantidade de requisições depende do volume de mensagens; isso não gera consultas ao CAD.

Em erro ou falta de permissão, a operação para e informa o progresso confirmado. Uma falha de rede pode ocorrer após o Discord aceitar um envio/exclusão; confira o tópico antes de repetir. O bot respeita respostas HTTP 429 e não repete automaticamente publicações após falhas ambíguas. Se uma limpeza longa ultrapassar a validade da resposta privada do Discord, ela pode terminar sem conseguir editar essa resposta; o log local informa quando a limpeza conclui. Para interromper, use **Parar bot**; uma requisição em andamento ainda pode concluir.

## Referências

- [Comandos de aplicação](https://docs.discord.com/developers/interactions/application-commands)
- [Interações e respostas privadas](https://docs.discord.com/developers/interactions/receiving-and-responding)
- [Consulta e exclusão de mensagens](https://docs.discord.com/developers/resources/message)
- [Gateway e retomada de sessão](https://docs.discord.com/developers/events/gateway)

O botão **Ajuda**, ao lado dos controles do bot, apresenta os passos de instalação, permissões e exemplos de comandos dentro da própria aplicação. A configuração fica bloqueada enquanto o bot está ativo ou encerrando; espere a parada terminar antes de reiniciar ou alterar os dados.

## Falha HTTP 403 ao iniciar

O log informa a etapa recusada e o código numérico do Discord, quando presente, sem exibir token ou resposta bruta. Se ocorrer ao **acessar o fórum**, confira a instalação do bot no servidor, o ID do canal de fórum e a permissão **Ver canal**, inclusive as regras da categoria. Se ocorrer ao **registrar /cad-reader**, confira o ID do servidor e a instalação no servidor (**Guild Install**) com os escopos `bot` e `applications.commands`. Permissões da sua conta e do webhook não concedem acesso ao bot automaticamente. Não é necessário conceder Administrador para resolver o acesso ao fórum. Se ocorrer HTTP 401, confira o token da seção Bot no Developer Portal.
