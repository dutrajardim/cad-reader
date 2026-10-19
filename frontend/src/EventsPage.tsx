import { memo, useEffect, useMemo, useState } from 'react'
import Help from './Help'
import { changes } from './api'
import { pollVisible } from './pollVisible'
import { normalizeSearch } from './callFilters'
const delivery=(e:Activity)=>e.title.includes('publicação confirmada')?'Publicada':e.title.includes('PAUSADA')?'Fila pausada':e.details.includes('Publicação: não enviada')?'Não enviada':e.details.includes('Publicação: somente local')?'Somente local':e.details.includes('Publicação: encaminhada')?'Encaminhada':''
type Activity={id:number;at:string;category:string;title:string;details:string}
const EventRow=memo(function EventRow({event:e}:{event:Activity}){
 const [open,setOpen]=useState(false)
 return <details className="card" onToggle={event=>setOpen(event.currentTarget.open)}><summary><time>{new Date(e.at).toLocaleString('pt-BR')}</time><span className="count">{e.category==='evento'?'Atualização':'Registro'}</span>{delivery(e)&&<span className="delivery-label">{delivery(e)}</span>}<strong>{e.title}</strong></summary>{open&&e.details&&<pre>{e.details}</pre>}</details>
})
export default function EventsPage({publicationStatus}:{publicationStatus?:string}){
 const [limit,setLimit]=useState(100)
 const [items,setItems]=useState<Activity[]>([]),[error,setError]=useState(''),[search,setSearch]=useState(''),[kind,setKind]=useState('all')
 useEffect(()=>{let after=0;const read=changes();return pollVisible(async signal=>{
  try{const next=await read<Activity[]>(`events?after=${after}`,signal);if(signal.aborted)return;setError('');if(!next?.length)return
   after=Math.max(after,...next.map(e=>e.id));const incoming=new Set(next.map(e=>e.id));setItems(old=>[...next,...old.filter(e=>!incoming.has(e.id))].slice(0,1000))
  }catch{if(!signal.aborted)setError('Não foi possível atualizar os eventos.')}
 })},[])
 const visible=useMemo(()=>items.filter(e=>(kind==='all'||(kind==='publication'?/publica|discord|pendente|enviad/i.test(e.title+' '+e.details):e.category===kind))&&normalizeSearch(e.title+' '+e.details).includes(normalizeSearch(search))),[items,kind,search])

 return <main className="admin-page"><div className="section-heading"><h2>Eventos e publicações</h2><p>{publicationStatus}</p></div><div className="event-filters"><label>Buscar evento<input value={search} onChange={e=>{setSearch(e.target.value);setLimit(100)}} placeholder="Viatura, ocorrência, mensagem…"/></label><label>Exibir<select value={kind} onChange={e=>{setKind(e.target.value);setLimit(100)}}><option value="all">Todos</option><option value="evento">Atualizações de ocorrência</option><option value="publication">Publicação / Discord</option><option value="log">Registros da aplicação</option></select></label></div><Help>Últimos 1.000 registros desta execução, mais recentes primeiro. Encaminhado à fila não significa publicado; as confirmações e falhas aparecem nos registros do publicador.</Help>{error&&<p role="alert">{error}</p>}<div className="event-list">{visible.slice(0,limit).map(e=><EventRow key={e.id} event={e}/>)}{visible.length>limit&&<button className="button secondary" onClick={()=>setLimit(n=>n+100)}>Mostrar mais eventos ({visible.length-limit})</button>}{!visible.length&&<div className="card">Nenhum evento para estes filtros.</div>}</div></main>
}
