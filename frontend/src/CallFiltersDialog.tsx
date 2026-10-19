import {useEffect,useRef,useState} from 'react'
import {createPortal} from 'react-dom'
import {filterCalls,groupOptions,natureKey,natureOptions,normalizeSearch} from './callFilters'
import type {CallFilters,FilterSelection} from './callFilters'
import type {AssignmentStatus,Call} from './types'
import type {Option} from './SearchSelect'

function Choices({label,value,options,onChange,searchable=false}:{label:string;value:FilterSelection;options:Option[];onChange:(next:string[])=>void;searchable?:boolean}){
 const [query,setQuery]=useState('')
 const selected=Array.isArray(value)?value:value==='all'?[]:[value]
 const visible=options.filter(o=>normalizeSearch(o.label).includes(normalizeSearch(query)))
 return <fieldset className="filter-choices"><legend>{label}</legend>{searchable&&<input aria-label="Buscar natureza" placeholder="Digite para buscar natureza…" value={query} onChange={e=>setQuery(e.target.value)}/>}
  <label><input type="checkbox" checked={!selected.length} onChange={()=>onChange([])}/>Todas as opções</label>
  <div className="filter-choice-options">{visible.map(o=><label key={o.value}><input type="checkbox" checked={selected.includes(o.value)} onChange={()=>onChange(selected.includes(o.value)?selected.filter(v=>v!==o.value):[...selected,o.value])}/>{o.label}</label>)}{!visible.length&&<p>Nenhuma opção encontrada.</p>}</div>
 </fieldset>
}
export default function CallFiltersDialog({calls,filters,onClose,onApply}:{calls:Call[];filters:CallFilters;onClose:()=>void;onApply:(next:CallFilters)=>void}){
 const dialog=useRef<HTMLDialogElement>(null)
 const [draft,setDraft]=useState<CallFilters>(()=>({...filters}))
 useEffect(()=>{const node=dialog.current!;node.showModal();return()=>node.close()},[])
 const groups=groupOptions(calls)
 const natures=natureOptions(calls,draft.group)
 const assignments=Object.entries({linked:'Viatura identificada',reported:'Empenho informado, sem identificação',unlinked:'Sem vínculo observado',unknown:'Ainda não confirmado'}).map(([value,label])=>({value,label}))
 return createPortal(<dialog ref={dialog} className="call-details-dialog filters-dialog" aria-labelledby="filters-title" onCancel={onClose}>
  <form onSubmit={e=>{e.preventDefault();onApply(draft)}}>
   <div className="call-dialog-heading"><strong id="filters-title">Filtros de ocorrências</strong><button type="button" className="button secondary" onClick={onClose} aria-label="Fechar filtros">×</button></div>
   <div className="filters-dialog-body">
    <Choices label="Vínculo com viatura" value={draft.assignment} options={assignments} onChange={assignment=>setDraft({...draft,assignment:assignment as AssignmentStatus[]})}/>
    <Choices label="Grupo de natureza" value={draft.group} options={groups.map(([g,count])=>({value:g,label:`${g==='?'?'Sem código':'Grupo '+g} · ${count}`}))} onChange={group=>setDraft({...draft,group,nature:[]})}/>
    <Choices label="Natureza" searchable value={draft.nature} options={natures.map(n=>({value:natureKey(n),label:[n.code,n.description].filter(Boolean).join(' · ')}))} onChange={nature=>setDraft({...draft,nature})}/>
   </div>
   <div className="filters-dialog-actions"><span role="status">{filterCalls(calls,draft).length} ocorrência(s)</span><button type="button" className="text-button" onClick={()=>setDraft({search:'',group:[],nature:[],assignment:[]})}>Limpar filtros</button><button type="button" className="button secondary" onClick={onClose}>Cancelar</button><button type="submit" className="button primary">Concluir</button></div>
  </form>
 </dialog>,document.fullscreenElement||document.body)
}
