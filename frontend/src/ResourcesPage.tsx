import {useMemo,useState} from 'react'
import type {Snapshot} from './types'
import {normalizeSearch} from './callFilters'
import {assignmentState} from './assignmentState'
import {cadDate} from './format'
import Help from './Help'
import ResourceSummary from './ResourceSummaryPanel'
const status=(value:string)=>({D:'Disponível',DISPONIVEL:'Disponível',E:'Empenhado',EMPENHADO:'Empenhado',I:'Indisponível',INDISPONIVEL:'Indisponível'}[value]||value||'Não informado')
export default function ResourcesPage({state,openCall}:{state:Snapshot;openCall:(id:string)=>void}){
 const [search,setSearch]=useState(''),[unit,setUnit]=useState('all'),[limit,setLimit]=useState(50)
 const units=useMemo(()=>[...new Map(state.resources.map(r=>[r.unitId,r.unitName||r.unitId])).entries()],[state.resources])
 const resources=useMemo(()=>state.resources.filter(r=>(unit==='all'||r.unitId===unit)&&normalizeSearch(`${r.prefix} ${r.id} ${r.type} ${r.plate||''} ${r.unitName}`).includes(normalizeSearch(search))),[state.resources,search,unit])
 const numbers=new Map(state.calls.map(c=>[c.id,c.number||c.id]))
 return <main className="admin-page"><div className="section-heading"><h2>Viaturas</h2><Help>Recursos recebidos no filtro CAD, com situação, restrições e vínculos ativos conhecidos. Esta página reutiliza o estado local do Java.</Help></div>{!state.ready&&<p className="hint-box">{state.status}</p>}<div className="event-filters"><label>Buscar viatura<input value={search} onChange={e=>{setSearch(e.target.value);setLimit(50)}} placeholder="Prefixo, tipo, placa ou unidade"/></label><label>Unidade<select value={unit} onChange={e=>{setUnit(e.target.value);setLimit(50)}}><option value="all">Todas</option>{units.map(([id,name])=><option key={id} value={id}>{name}</option>)}</select></label></div><ResourceSummary resources={resources} ready={state.ready}/><p className="muted">{resources.length} recurso(s) recebido(s)</p><div className="data-table"><table><thead><tr><th>Viatura</th><th>Unidade / tipo</th><th>Situação</th><th>Restrição</th><th>Empenhos</th><th>Atualização CAD</th></tr></thead><tbody>{resources.slice(0,limit).map(r=><tr key={r.id}><td><strong>{r.prefix||r.id}</strong><small>{r.plate||'Placa não informada'} · ID {r.id}</small></td><td>{r.unitName||r.unitId}<small>{r.type||r.typeId}</small></td><td>{status(r.status)}</td><td>{r.restriction||'Nenhuma informada'}</td><td>{r.assignments?.length?r.assignments.map(a=><div className="resource-assignment" key={a.callId}><button className="text-button" onClick={()=>openCall(a.callId)}>{numbers.get(a.callId)||a.callId}</button><small>{assignmentState(a.status).label}</small></div>):'Sem vínculo observado'}</td><td>{cadDate(r.statusAt)}</td></tr>)}</tbody></table>{!resources.length&&<p className="table-empty">Nenhuma viatura para este recorte.</p>}</div>{resources.length>limit&&<button className="button secondary" onClick={()=>setLimit(v=>v+50)}>Mostrar mais viaturas</button>}</main>
}
