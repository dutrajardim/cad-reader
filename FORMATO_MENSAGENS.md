# Apresentação das mensagens

A prioridade é acompanhar o atendimento em tempo real. Cada tópico representa uma viatura, cada bloco representa uma atualização ou parte de histórico. Um lote pode conter vários blocos em ordem de recebimento.

## Título

Formato: **ícone CHAMADA número • tipo da atualização**.

Exemplos:

- 🚨 **CHAMADA 2026482447100 • DESPACHADO**
- 📍 **CHAMADA 2026482447100 • NO LOCAL**
- 📝 **CHAMADA 2026482447100 • HISTÓRICO DA OCORRÊNCIA**
- ☎️ **CHAMADA 2026482447100 • CONTATOS • ENVOLVIDOS NO DESPACHO**

Ícones identificam o tipo de atualização. A cor é calculada de forma estável pelos últimos oito dígitos da chamada e permanece igual entre seus eventos; números diferentes podem gerar cores semelhantes ou iguais. Sem número, usa-se cinza. Atualizações gerais de ocorrência são identificadas como **OCORRÊNCIA • situação** ou **DADOS DA OCORRÊNCIA ATUALIZADOS**, evitando apresentá-las como novo despacho.

## Corpo e horários

O despacho apresenta contexto completo disponível: unidade, estados, endereço, referência, natureza e navegação. Atualizações seguintes mostram campos alterados. Links de navegação aparecem somente na primeira disponibilidade ou em mudança do endereço/coordenadas; alterações apenas da referência ou complemento não os repetem. Um campo omitido não foi necessariamente removido; remoções são indicadas como `CAMPO: removido`.

- **HORÁRIO CAD**: horário do estado recebido do CAD.
- **REGISTRO CAD**: horário de inclusão do histórico.
- **CAD • Recebido** no rodapé, com timestamp Discord: horário em que o leitor recebeu o evento.

Históricos podem chegar depois do estado e conter registros mais antigos. Não interprete a ordem de recebimento como ordem garantida das ações no CAD. Números da chamada permanecem nos títulos mesmo quando vários chamados se intercalam no mesmo tópico.

Textos extensos de histórico são divididos em partes identificadas no título. Contatos são publicados em blocos curtos, com nome, telefone e indicação de solicitante, quando disponíveis pela consulta autorizada.

## Consolidação futura

Não há metadados JSON visíveis nem um contrato de IDs técnicos dentro da mensagem. Uma futura consolidação baseada no fórum deverá:

1. Ler somente mensagens de webhooks autorizados e percorrer todos os embeds.
2. Identificar o chamado pelo número no título e a viatura pelo tópico.
3. Distinguir estados, históricos e contatos pelos tipos/rótulos apresentados.
4. Ordenar pelo horário CAD quando disponível; sinalizar uso do recebimento quando não houver horário de origem.
5. Reconhecer históricos repetidos entre viaturas comparando chamado, data, texto e partes, sem apagar registros diferentes.
6. Informar que a cobertura corresponde às mensagens publicadas e acessíveis no fórum.

Para consolidação estruturada mais precisa, uma integração futura poderá usar uma base mantida pelo leitor. Os IDs internos e fingerprints atuais permanecem locais; não devem ser inferidos do texto do Discord. A consolidação de conversas ainda não está implementada. O bot disponível consulta relatórios diretamente pela sessão CAD e limpa mensagens por data; veja [BOT_DISCORD.md](BOT_DISCORD.md).

## Pendências e publicações existentes

Novos eventos usam o formato legível. Ao reenviar uma pendência com o antigo rodapé técnico, o publicador troca apenas esse rodapé por **CAD • Recebido**, preservando o conteúdo. Mensagens já publicadas não são editadas automaticamente.

### Contexto inicial e novos registros de histórico

A primeira consulta de histórico de cada vínculo entre viatura e chamada aguarda todas as páginas e publica um bloco **HISTÓRICO INICIAL**, em ordem cronológica, com a data CAD de cada registro. Os registros já presentes no resumo entram nesse mesmo contexto, sem mensagens individuais repetidas. O texto é reproduzido, sem resumo automático. O contexto pode conter registros posteriores ao despacho que já existiam quando a consulta foi feita.

Após esse contexto, registros novos ou modificados geram eventos individuais **HISTÓRICO DA OCORRÊNCIA**; a janela de agrupamento de três segundos continua valendo. Conteúdo longo é dividido em partes para respeitar os limites do Discord, sem descarte. Se a consulta falhar após receber algum conteúdo, publica-se o disponível com indicação de consulta incompleta. Não são acrescentadas consultas ao CAD para fazer essa consolidação.
