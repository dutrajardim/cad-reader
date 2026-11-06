import React, { useEffect, useMemo, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import OperationalMap, { google, located } from './OperationalMap'
import AdminPortal from './AdminPortal'
import Snackbar,{notifyError as setNotice,useErrorSnackbar} from './Snackbar'
import CallFiltersDialog from './CallFiltersDialog'
import SearchSelect from './SearchSelect'
import Help from './Help'
import Dashboard from './Dashboard'
import ReportDashboard from './ReportDashboard'
import CallDetailsDialog from './CallDetailsDialog'
import ResourcesPage from './ResourcesPage'
import ConnectionIndicators from './ConnectionIndicators'
import Icon from './Icon'
import type {IconName} from './Icon'
import { assignmentLabel, assignmentSymbol, filterCalls, groupColor, natureGroup, natureKey, naturesOf } from './callFilters'
import type { CallFilters } from './callFilters'
import { pollVisible } from './pollVisible'
import MapLegend from './MapLegend'
import { api, changes, sessionToken } from './api'
import type { Bootstrap, Route, RouteResult, Settings } from './types'
import './styles.css'

const available = (s: string) => s === 'D' || s === 'DISPONIVEL'
const stateLabel = (s: string) => ({ A: 'Atribuída', CRIADA: 'Atribuída', E: 'Empenhada', EMPENHADA: 'Empenhada' }[s] || s || 'Não informada')
type Tab='map'|'dashboard'|'resources'|'report'
const navigation:{id:Tab;label:string;icon:IconName}[]=[{id:'dashboard',label:'Dashboard',icon:'dashboard'},{id:'report',label:'Somatório',icon:'activity'},{id:'map',label:'Mapa operacional',icon:'map'},{id:'resources',label:'Viaturas',icon:'truck'}]
function App() {
  const [data, setData] = useState<Bootstrap>()
  const [settings, setSettings] = useState<Settings>()
  const [tab, setTab] = useState<Tab>('map')
  const [detailId,setDetailId]=useState('')
  const [filtersExpanded,setFiltersExpanded]=useState(false),[selectionFocus,setSelectionFocus]=useState(0)
  function focusCall(id:string){setSelectedId(id);setShownRoute(undefined);setSelectionFocus(v=>v+1)}
  function openCall(id:string){setSelectedId(id);setDetailId(id)}
  function openMap(id:string){setDetailId('');setSearch('');setGroup('all');setNature('all');setAssignment('all');setSelectedId(id);setTab('map')}
  const [search, setSearch] = useState(''), [selectedId, setSelectedId] = useState('')
  const [error, setError] = useState('')
  useErrorSnackbar(error)
  const busy = false
  const [calculating, setCalculating] = useState(false), [progress, setProgress] = useState('')
  const [profileId, setProfileId] = useState('all')
  const [group, setGroup] = useState<CallFilters['group']>('all'), [nature, setNature] = useState<CallFilters['nature']>('all')
  const [assignment, setAssignment] = useState<CallFilters['assignment']>('all'), [fit, setFit] = useState(0)
  const [results, setResults] = useState<RouteResult[]>([]), [shownRoute, setShownRoute] = useState<Route>()
  const run = useRef(0), abort = useRef<AbortController | null>(null)
  const [showFilters,setShowFilters]=useState(true),[fullscreen,setFullscreen]=useState(false)
  const workspace=useRef<HTMLElement>(null), priorPanels=useRef({filters:true})
  useEffect(()=>{const changed=()=>{const full=document.fullscreenElement===workspace.current;setFullscreen(full);if(!full){setShowFilters(priorPanels.current.filters)}};document.addEventListener('fullscreenchange',changed);return()=>document.removeEventListener('fullscreenchange',changed)},[])
  async function toggleFullscreen(){try{if(document.fullscreenElement)await document.exitFullscreen();else {priorPanels.current={filters:showFilters};await workspace.current?.requestFullscreen();setShowFilters(false)}}catch{setNotice('O navegador não permitiu a tela cheia.')}}
  async function showRoute(baseId:string,pid:string){if(!selected)return;const id=run.current;try{const route=await api<Route>('route',{callId:selected.id,baseId,profileId:pid});if(run.current===id){openMap(selected.id);setShownRoute(route)}}catch(e){if(run.current===id)setNotice((e as Error).message)}}
  useEffect(() => {
    const read=changes()
    return pollVisible(async signal=>{
      try {
        const next=await read<Bootstrap>('state',signal)
        if(signal.aborted)return
        setError('')
        if(!next)return
        sessionToken(next.token)
        setData(current=>{
          if(!current||current.token!==next.token)return next
          if(current.state.revision===next.state.revision)return {...next,state:current.state}
          // Preserve os objetos sem alteração para não recriar ícones e popups Leaflet.
          const old=new Map(current.state.calls.map(c=>[c.id,c]))
          next.state.calls=next.state.calls.map(c=>{const previous=old.get(c.id);return previous&&JSON.stringify(previous)===JSON.stringify(c)?previous:c})
          return next
        })
        setSettings(current=>current&&JSON.stringify(current)===JSON.stringify(next.settings)?current:next.settings)
      }catch(e){if(!signal.aborted)setError('Sem comunicação com o CAD-Reader. Mantenha o CAD Reader em execução. '+(e as Error).message)}
    })
  }, [])
  const filters = useMemo(() => ({ search, group, nature, assignment }), [search, group, nature, assignment])
  const calls = useMemo(() => filterCalls(data?.state.calls || [], filters), [data?.state.calls, filters])
  const selected = data?.state.calls.find(c => c.id === selectedId)
  useEffect(() => {
    run.current++; abort.current?.abort(); setResults([]); setShownRoute(undefined); setCalculating(false); setProgress('')
  }, [selectedId, selected?.lat, selected?.lon, settings, profileId, data?.state.ready, data?.routing.connected])
  useEffect(() => () => abort.current?.abort(), [])
  async function compare() {
    if (!selected || !settings || !data?.routing.connected) return
    const controller = new AbortController(); abort.current?.abort(); abort.current = controller
    const currentRun = ++run.current
    setCalculating(true); setResults([]); setShownRoute(undefined); setNotice('')
    const profiles = settings.profiles.filter(p => profileId === 'all' || p.id === profileId)
    const next: RouteResult[] = []
    try {
      for (const profile of profiles) {
        if (currentRun !== run.current) return
        setProgress(`Matriz · ${profile.name}`)
        try {
          const batch = await api<RouteResult[]>('matrix', {callId:selected.id, profileId:profile.id}, controller.signal)
          next.push(...batch)
        } catch(e) {
          if(controller.signal.aborted)return
          setNotice((e as Error).message)
          next.push(...settings.bases.filter(located).map(base=>({baseId:base.id,profileId:profile.id,error:(e as Error).message})))
        }
        if(currentRun!==run.current)return
        setResults([...next])
        if(profile!==profiles[profiles.length-1])await new Promise(resolve=>setTimeout(resolve,1100))
      }
    } finally { if (currentRun === run.current) { setCalculating(false); setProgress('') } }
  }
  if (!data || !settings) return <main className="loading"><img className="brand-image" src="/assets/server-cad-reader.png" alt="CAD Reader"/><h1>CAD Reader</h1><p>{error?'Aguardando comunicação com o CAD Reader…':'Abrindo o mapa operacional…'}</p><p className="muted">Inicie o servidor web do CAD Reader.</p></main>
  const profiles = settings.profiles.filter(p => profileId === 'all' || p.id === profileId)
  const activeFilters=[group,nature,assignment].filter(v=>Array.isArray(v)?v.length>0:v!=='all').length
  const selectedResources = data.state.resources
  const bases = [...settings.bases].sort((a,b) => {
    const time = (id: string) => Math.min(...results.filter(r => r.baseId === id && r.route).map(r => r.route!.seconds), Infinity)
    return time(a.id) - time(b.id)
  })
  return <div className="app-shell">
    <header className="app-header"><div className="brand"><img className="brand-image" src="/assets/server-cad-reader.png" alt=""/><div><strong>CAD <span>Reader</span></strong><small>MAPA OPERACIONAL</small></div></div>
      <nav aria-label="Principal">{navigation.map(item=><button key={item.id} className={tab===item.id?'active':''} onClick={()=>setTab(item.id)} aria-current={tab===item.id?'page':undefined} title={item.label}><Icon name={item.icon}/><span>{item.label}</span></button>)}</nav>
      <a className="button secondary" href="/admin">Administração</a>
      <ConnectionIndicators state={data.state} routing={data.routing} unavailable={!!error} openCad={()=>{window.location.href='/admin'}} openRouting={()=>{window.location.href='/admin'}}/>

    </header>
    <div className="monitoring-scope" aria-label="Filtros do acompanhamento">
      <span className="monitoring-scope-status">{error?'Sem comunicação':!data.state.connected?'Desconectado':data.state.ready?'Acompanhando':data.state.callFilterId?'Carregando':'Aguardando monitoramento'}</span>
      <span><strong>Chamadas:</strong> {data.state.callFilterName||data.state.callFilterId||'Não selecionado'}</span>
      <span><strong>Recursos:</strong> {data.state.resourceFilterName||data.state.resourceFilterId||'Não selecionado'}</span>
    </div>
    <div className="status-strip"><span>{data.state.calls.length} ocorrências · {data.state.resources.length} viaturas <Help>{data.state.status}</Help></span><span>{data.state.updatedAt ? 'Atualizado ' + new Date(data.state.updatedAt).toLocaleTimeString('pt-BR') : 'Configure a sessão na administração'}</span></div>
    {tab==='resources'&&<ResourcesPage state={data.state} openCall={openCall}/>}
    <div hidden={tab!=='report'}><ReportDashboard key={data.token} active={tab==='report'}/></div>
    {tab==='dashboard'&&<Dashboard calls={data.state.calls} resources={data.state.resources} ready={data.state.ready}/>}
    {tab === 'map' && <>
      <main ref={workspace} className={'workspace options-hidden '+(!showFilters?'filters-hidden ':'')+(fullscreen?'is-fullscreen':'')}>

        {showFilters&&<aside className="call-panel"><div className="panel-title"><h2>Ocorrências</h2><span className="count">{calls.length} / {data.state.calls.length}</span></div>
          <label className="search"><span className="sr-only">Buscar ocorrência</span><input value={search} onChange={e => setSearch(e.target.value)} placeholder="Número, natureza, endereço ou viatura" /></label>
          <div className="filter-toolbar"><button className="filter-toggle" aria-haspopup="dialog" onClick={()=>setFiltersExpanded(true)}><Icon name="settings"/> Filtros {activeFilters>0&&<span className="count">{activeFilters} ativos</span>}<span className="filter-chevron">{filtersExpanded?'▴':'▾'}</span></button><button className="text-button" disabled={!calls.some(located)&&!settings.bases.some(located)} title="Enquadrar ocorrências e unidades" onClick={()=>setFit(fit+1)}>Ajustar mapa</button></div>
          {filtersExpanded&&<CallFiltersDialog calls={data.state.calls} filters={filters} onClose={()=>setFiltersExpanded(false)} onApply={next=>{setSearch(next.search);setGroup(next.group);setNature(next.nature);setAssignment(next.assignment);setFiltersExpanded(false)}}/>}
          <div className="scope-note">{calls.filter(c=>!located(c)).length} sem coordenadas <Help>Os filtros atualizam a lista e o mapa ao concluir a janela. Selecione várias opções e use a busca para encontrar naturezas.</Help></div>
          <div className="call-list">{calls.map(c => <article key={c.id} className={'call-item ' + (c.id === selectedId ? 'selected' : '')} onClick={()=>focusCall(c.id)}><button className="call-focus" onClick={e => {e.stopPropagation();focusCall(c.id)}} aria-label={located(c)?`Focar ocorrência ${c.number||c.id} no mapa`:`Selecionar ocorrência ${c.number||c.id}, sem coordenadas`}><div><strong>{c.number || c.id}</strong><span className="call-status">{stateLabel(c.status)}</span></div><h3>{c.nature || 'Natureza não informada'}</h3><div className="nature-tags">{naturesOf(c).map(n=><span key={natureKey(n)} style={{borderColor:groupColor(natureGroup(n)),color:groupColor(natureGroup(n))}}>{n.code||'Sem código'}</span>)}</div><p>{c.address || 'Endereço não informado'}</p><span className={'assignment-tag '+c.assignmentStatus}>{assignmentSymbol(c)} · {assignmentLabel(c)}</span>{c.assignedResources?.length>0&&<p className="linked-prefixes">{c.assignedResources.map(r=>r.prefix||r.resourceId).join(' · ')}</p>}</button><div className="call-item-actions"><small>{located(c)?'Clique no cartão para focar no mapa':'Sem coordenadas no CAD'}</small><button className="text-button" onClick={e=>{e.stopPropagation();openCall(c.id)}}>Ver detalhes →</button></div></article>)}
            {calls.length === 0 && <div className="empty-state"><span>◎</span><h3>{data.state.calls.length ? 'Nenhum resultado' : 'Aguardando ocorrências'}</h3><p>{data.state.calls.length ? 'Altere ou limpe os filtros para ver outras ocorrências.' : 'Conecte ao CAD e inicie o monitoramento na administração. As ocorrências do filtro aparecerão aqui.'}</p></div>}
          </div><div className="panel-footer">Consulta local · nenhuma alteração no CAD</div>
        </aside>}
        <section className="map-area" aria-label="Mapa das ocorrências e bases"><OperationalMap calls={calls} bases={settings.bases} selected={selected} select={setSelectedId} openDetails={openCall} route={shownRoute} selectionFocus={selectionFocus} filters={filters} fit={fit} layoutKey={`${showFilters}-${fullscreen}`} />
          <div className="map-tools"><button onClick={()=>setShowFilters(v=>!v)} aria-pressed={showFilters} title={showFilters?'Ocultar filtros':'Mostrar filtros'}><Icon name="hide"/><span className="sr-only">{showFilters?'Ocultar filtros':'Mostrar filtros'}</span></button><button onClick={()=>void toggleFullscreen()} title={fullscreen?'Sair da tela cheia':'Tela cheia'}><Icon name="expand"/><span className="sr-only">{fullscreen?'Sair da tela cheia':'Tela cheia'}</span></button><Help>Passe o mouse para um resumo. Clique no marcador para abrir os detalhes. Na lista, clique no cartão para focar no mapa ou em “Ver detalhes” para abrir a modal. Esc sai da tela cheia.</Help></div><MapLegend/>

        </section>

      </main>
    </>}
    {detailId&&<CallDetailsDialog key={`${data.token}-${detailId}`} id={detailId} onClose={()=>setDetailId('')}>        <section className="comparison-panel" aria-label="Comparar unidades">
          <div className="comparison-controls"><SearchSelect label="Perfil de deslocamento" value={profileId} onChange={setProfileId} options={[{value:'all',label:'Comparar todos os perfis'},...settings.profiles.map(p=>({value:p.id,label:p.name}))]}/>
            <button className="button primary" disabled={!selected || !located(selected) || !data.state.ready || !data.routing.connected || settings.bases.filter(located).length === 0 || calculating || busy} onClick={() => void compare()}>{calculating ? 'Calculando percursos…' : 'Comparar unidades'}</button>
            {calculating && <div className="progress"><span>{progress}</span><button className="text-button" onClick={() => { run.current++; abort.current?.abort(); setCalculating(false); setProgress('') }}>Interromper</button></div>}
          </div>
          {!data.routing.connected && <div className="hint-box"><button className="text-button" onClick={()=>{setDetailId('');window.location.assign('/admin')}}>Conectar Valhalla nas configurações</button><Help>Google Maps está disponível sem Valhalla.</Help></div>}
          {selected && !located(selected) && <p className="hint-box">Esta ocorrência não tem coordenadas válidas. A comparação fica indisponível.</p>}
          <div className="base-list">{bases.map(base => {
            const resourceList = selectedResources.filter(r => r.unitId === base.unitId)
            const eligible = resourceList.filter(r => available(r.status) && !r.restriction)
            const inScope = data.state.scopeUnits.includes(base.unitId)
            return <article className="base-card" key={base.id}><div className="base-heading"><h3>{base.name}</h3><span>UN. {base.unitId}</span></div>
              <p className={'availability ' + (eligible.length && data.state.ready ? 'available' : '')}>{!base.unitId ? 'Sem vínculo com unidade CAD' : !data.state.ready ? 'Disponibilidade não confirmada' : !inScope ? 'Unidade fora do filtro CAD' : `${eligible.length} recurso(s) disponível(is) sem restrição informada`}</p>
              <p className="resource-names">{resourceList.map(r => `${r.prefix} · ${r.type}${r.restriction ? ' (restrição)' : available(r.status) ? '' : r.status === 'E' || r.status === 'EMPENHADO' ? ' (empenhado)' : ' (indisponível/não confirmado)'}`).join(' / ') || 'Nenhum recurso recebido para este recorte.'}</p>
              {!located(base) && <p className="hint-box">Sem coordenadas: complete o cadastro para calcular no Valhalla.</p>}
              {profiles.map(p => { const result = results.find(r => r.baseId === base.id && r.profileId === p.id); return <div key={p.id} className="route-row"><span>{p.name}</span>{result?.route ? <button className="route-value" onClick={() => void showRoute(base.id,p.id)} title="Mostrar trajeto no mapa">{Math.ceil(result.route.seconds / 60)} min <small>· {result.route.kilometers.toFixed(1)} km ↗</small></button> : result?.error ? <span className="route-error" title={result.error}>Sem cálculo ⓘ</span> : <span className="route-pending">—</span>}</div> })}
              {selected && located(selected) && (located(base) || base.address) ? <a className="google-link" href={google(base, selected)} target="_blank" rel="noopener noreferrer">Abrir no Google Maps <span>↗</span></a> : <span className="google-link disabled">Selecione uma ocorrência com coordenadas</span>}
            </article>
          })}{bases.length === 0 && <div className="empty-state"><span>⌖</span><h3>Cadastre a primeira base</h3><p>Informe nome, unidade CAD e coordenadas em “Unidades”.</p><button className="button secondary" onClick={() => {setDetailId('');window.location.assign('/admin')}}>Cadastrar unidades</button></div>}</div>
          <Help>Estimativas da base à ocorrência: não incluem mobilização nem trânsito em tempo real. Google Maps calcula uma rota própria no modo carro. Recursos são limitados pelos filtros CAD e perfil não comprova aptidão operacional. Serviço público Valhalla sujeito a limites e disponibilidade.</Help>
        </section></CallDetailsDialog>}
  </div>
}
createRoot(document.getElementById('root')!).render(<React.StrictMode><Snackbar/>{window.location.pathname.startsWith('/admin')?<AdminPortal/>:<App/>}</React.StrictMode>)
