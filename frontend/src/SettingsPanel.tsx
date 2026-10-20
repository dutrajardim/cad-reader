import { useState } from 'react'
import type { Base, Settings, Snapshot, VehicleProfile } from './types'
import CsvTransfer from './CsvTransfer'
import Help from './Help'
import {notifyError as setError} from './Snackbar'
import { exportProfiles, exportUnits, importProfiles, importUnits } from './csv'
import { located } from './OperationalMap'
const fields = [['height','Altura (m)'],['width','Largura (m)'],['length','Comprimento (m)'],['weight','Peso (t)'],['axle_load','Carga por eixo (t)']] as const
const emptyBase = (): Base => ({id:'',name:'',unitId:'',lat:null,lon:null,address:'',sourceUrl:'',checkedAt:'',notes:''})
export default function SettingsPanel({ section, settings, state, busy, save }: { section: 'units'|'profiles'; settings: Settings; state: Snapshot; busy: boolean; save: (s: Settings) => Promise<void> }) {
  const [bases, setBases] = useState<Base[]>(() => structuredClone(settings.bases))
  const [profiles, setProfiles] = useState<VehicleProfile[]>(() => structuredClone(settings.profiles))
  const [base, setBase] = useState<Base>(emptyBase)
  const [lat, setLat] = useState(''), [lon, setLon] = useState('')
  const [profile, setProfile] = useState<VehicleProfile>({ id: '', name: '', costing: 'truck' })
  function addBase() {
    // O mesmo validador do CSV é usado no formulário, preservando as regras de importação.
    const next = {...base,id:base.id||crypto.randomUUID(),lat:lat.trim()?Number(lat.replace(',','.')):null,lon:lon.trim()?Number(lon.replace(',','.')):null}
    if((lat.trim()&&!Number.isFinite(next.lat))||(lon.trim()&&!Number.isFinite(next.lon))) {setError('Informe coordenadas numéricas válidas.');return}
    const checked=importUnits(exportUnits([next]))
    if(checked.errors.length) {setError(checked.errors.join(' '));return}
    const list=[...bases.filter(b=>b.id!==next.id),checked.rows[0]]
    if(list.length>200){setError('Máximo de 200 unidades.');return}
    setBases(list);setBase(emptyBase());setLat('');setLon('');setError('')
  }
  const units = [...new Map(state.resources.map(r => [r.unitId, r.unitName || r.unitId])).entries()]
  return <section className="settings-view">
    <div className="section-heading"><div><span className="eyebrow">CADASTRO LOCAL</span><h2>{section==='units'?'Unidades e bases':'Perfis de veículo'}</h2><Help>{section==='units'?'Cadastre endereços, associe unidades CAD e confirme as coordenadas de saída.':'Defina os parâmetros de deslocamento dos recursos; os perfis não alteram o CAD.'}</Help></div></div>
    {section==='units' ? <>
      <CsvTransfer items={bases} read={importUnits} write={exportUnits} apply={setBases} filename="unidades.csv" limit={200} disabled={busy}/>
      <p className="catalog-link"><a href="/assets/unidades-cbmmg-rmbh.csv" download>Baixar CSV das seis unidades pesquisadas do CBMMG ↧</a><span>Seis unidades com coordenadas. IDs CAD pendentes; confirme os portões de saída.</span></p>
      <div className="admin-grid"><section className="card"><h3>{base.id?'Editar unidade / base':'Nova unidade / base'}</h3><Help>O endereço permite abrir no Google Maps. Coordenadas são necessárias para o mapa e o Valhalla. O vínculo CAD pode ser preenchido depois.</Help>
        <div className="form-grid">
          <label>Nome da base<input value={base.name} onChange={e=>setBase({...base,name:e.target.value})} placeholder="Ex.: Contagem — Sede" maxLength={120}/></label>
          <label>ID da unidade CAD (opcional)<input list="cad-units" value={base.unitId} onChange={e=>setBase({...base,unitId:e.target.value})} placeholder="Preencher quando confirmado"/></label><datalist id="cad-units">{units.map(([id,n])=><option key={id} value={id}>{n}</option>)}</datalist>
          <label className="full-width">Endereço<input value={base.address||''} onChange={e=>setBase({...base,address:e.target.value})} placeholder="Logradouro, número, bairro, cidade e estado" maxLength={600}/></label>
          <label>Latitude<input type="number" step="any" min="-90" max="90" value={lat} onChange={e=>setLat(e.target.value)} placeholder="Pendente"/></label>
          <label>Longitude<input type="number" step="any" min="-180" max="180" value={lon} onChange={e=>setLon(e.target.value)} placeholder="Pendente"/></label>
          <label className="full-width">Fonte do endereço (URL)<input type="url" value={base.sourceUrl||''} onChange={e=>setBase({...base,sourceUrl:e.target.value})} placeholder="https://..."/></label>
          <label>Data da consulta<input type="date" value={base.checkedAt||''} onChange={e=>setBase({...base,checkedAt:e.target.value})}/></label>
          <label className="full-width">Observações<textarea value={base.notes||''} onChange={e=>setBase({...base,notes:e.target.value})} maxLength={2000}/></label>
        </div><button className="button secondary" onClick={addBase}>{base.id?'Atualizar base na lista':'+ Adicionar base à lista'}</button>{base.id&&<button className="text-button" onClick={()=>{setBase(emptyBase());setLat('');setLon('')}}>Cancelar edição</button>}
      </section><section className="card"><h3>Unidades cadastradas <span className="count">{bases.length}</span></h3><Help>A exportação inclui a lista exibida, mesmo antes de salvar.</Help>
        <div className="item-list">{bases.length===0&&<p className="empty-inline">Nenhuma base cadastrada. Importe um CSV ou preencha o formulário.</p>}{bases.map(b=><article className="saved-item unit-item" key={b.id}><div><strong>{b.name}</strong><p>{b.address||'Endereço não informado'}</p><small>{b.unitId?`Unidade CAD ${b.unitId}`:'Vínculo CAD pendente'} · {located(b)?`${b.lat}, ${b.lon}`:'Coordenadas pendentes'}</small>{b.notes&&<small>{b.notes}</small>}{b.sourceUrl&&<small><a href={b.sourceUrl} target="_blank" rel="noopener noreferrer">Fonte do endereço ↗</a>{b.checkedAt&&` · consultada em ${b.checkedAt}`}</small>}</div><div className="item-buttons"><button className="text-button" onClick={()=>{setBase({...b});setLat(b.lat===null?'':String(b.lat));setLon(b.lon===null?'':String(b.lon))}}>Editar</button><button className="text-button danger" onClick={()=>setBases(bases.filter(x=>x.id!==b.id))}>Remover</button></div></article>)}</div>
      </section></div>
    </> : <>
      <CsvTransfer items={profiles} read={importProfiles} write={exportProfiles} apply={setProfiles} filename="perfis-veiculo.csv" limit={30} disabled={busy}/>
      <div className="admin-grid"><section className="card"><h3>{profile.id?'Editar perfil':'Novo perfil de veículo'}</h3><Help>Campos vazios usam os padrões do servidor Valhalla. Altura, largura e comprimento em metros; peso e carga por eixo em toneladas.</Help>
        <div className="form-grid"><label>Nome do perfil<input value={profile.name} onChange={e=>setProfile({...profile,name:e.target.value})} placeholder="Ex.: ABTS da unidade" maxLength={100}/></label><label>Modo<select value={profile.costing} onChange={e=>setProfile({...profile,costing:e.target.value as 'auto'|'truck'})}><option value="auto">Carro</option><option value="truck">Caminhão</option></select></label>
          {fields.map(([key,label])=><label key={key}>{label}<input type="number" step="any" min="0.01" value={profile[key]??''} onChange={e=>setProfile({...profile,[key]:e.target.value===''?undefined:Number(e.target.value)})} placeholder="Padrão do servidor"/></label>)}
        </div><button className="button secondary" onClick={()=>{
          const next={...profile,id:profile.id||crypto.randomUUID()}
          if(fields.some(([key])=>next[key]!==undefined&&!Number.isFinite(next[key]))){setError('Características devem ser números positivos.');return}
          const checked=importProfiles(exportProfiles([next]));if(checked.errors.length){setError(checked.errors.join(' '));return}
          const list=[...profiles.filter(p=>p.id!==next.id),checked.rows[0]];if(list.length>30){setError('Máximo de 30 perfis.');return}
          setProfiles(list);setProfile({id:'',name:'',costing:'truck'});setError('')
        }}>{profile.id?'Atualizar perfil na lista':'+ Adicionar perfil à lista'}</button>{profile.id&&<button className="text-button" onClick={()=>setProfile({id:'',name:'',costing:'truck'})}>Cancelar edição</button>}
      </section><section className="card"><h3>Perfis cadastrados <span className="count">{profiles.length}</span></h3><div className="item-list">{profiles.map(p=><article className="saved-item" key={p.id}><div><strong>{p.name}</strong><small>{p.costing==='auto'?'Carro':'Caminhão'} · {fields.filter(([key])=>p[key]!==undefined).map(([key,label])=>`${label}: ${p[key]}`).join(' · ')||'Parâmetros padrão do servidor'}</small></div><div className="item-buttons"><button className="text-button" onClick={()=>setProfile({...p})}>Editar</button><button className="text-button danger" onClick={()=>setProfiles(profiles.filter(x=>x.id!==p.id))}>Remover</button></div></article>)}</div><Help>Perfis de rota são independentes do tipo do recurso CAD e não definem aptidão operacional automaticamente.</Help></section></div>
    </>}
    <div className="save-bar"><Help>Importação e edição alteram a lista. Salve para gravar esta aba no computador; a outra aba será preservada.</Help><button className="button primary" disabled={busy} onClick={async()=>{try{await save(section==='units'?{...settings,bases}:{...settings,profiles});setError('')}catch(e){setError((e as Error).message)}}}>{busy?'Salvando…':'Salvar cadastro'}</button></div>
  </section>
}
