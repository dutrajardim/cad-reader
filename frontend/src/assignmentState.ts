import { normalizeSearch } from './callFilters'
/** Mesmas siglas usadas por CadMonitor.label; estados desconhecidos permanecem explícitos. */
export function assignmentState(raw:string){
 const key=normalizeSearch(raw).replace(/[\s-]+/g,'_').toUpperCase()
 const known:Record<string,{label:string;symbol:string;tone:string}>={
 NL:{label:'No local',symbol:'●',tone:'onsite'},NO_LOCAL:{label:'No local',symbol:'●',tone:'onsite'},
 AC:{label:'A caminho',symbol:'→',tone:'moving'},A_CAMINHO:{label:'A caminho',symbol:'→',tone:'moving'},DESLOCAMENTO:{label:'Em deslocamento',symbol:'→',tone:'moving'},EM_DESLOCAMENTO:{label:'Em deslocamento',symbol:'→',tone:'moving'},
 ED:{label:'Em direção',symbol:'→',tone:'moving'},EM_DIRECAO:{label:'Em direção',symbol:'→',tone:'moving'},
 LI:{label:'Local intermediário',symbol:'●',tone:'intermediate'},NO_LOCAL_INTERMEDIARIO:{label:'Local intermediário',symbol:'●',tone:'intermediate'},
 ER:{label:'Em retorno',symbol:'↩',tone:'returning'},EM_RETORNO:{label:'Em retorno',symbol:'↩',tone:'returning'},
 DP:{label:'Despachada',symbol:'◷',tone:'dispatched'},DESPACHADO:{label:'Despachada',symbol:'◷',tone:'dispatched'},
 EC:{label:'Em controle',symbol:'◆',tone:'control'},EM_CONTROLE:{label:'Em controle',symbol:'◆',tone:'control'},
 EN:{label:'Em encerramento',symbol:'◷',tone:'closing'},ENCERRAMENTO:{label:'Em encerramento',symbol:'◷',tone:'closing'},EM_ENCERRAMENTO:{label:'Em encerramento',symbol:'◷',tone:'closing'},
 SU:{label:'Suspensa',symbol:'Ⅱ',tone:'suspended'},SUSPENSO:{label:'Suspensa',symbol:'Ⅱ',tone:'suspended'} }
 return known[key]||{label:raw.trim().replace(/_/g,' ')||'Estado não informado',symbol:'?',tone:'unknown'}
}
