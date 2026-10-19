import { memo, useEffect, useMemo, useState } from 'react'
import { divIcon } from 'leaflet'
import { CircleMarker, LayersControl, MapContainer, Marker, Popup, Polyline, TileLayer, Tooltip, useMap, useMapEvents } from 'react-leaflet'
import { assignmentLabel, assignmentSymbol, groupColor, matchingNatures, natureGroup, naturesOf } from './callFilters'
import { natureIcon } from './natureIcons'
import { DEFAULT_MAP_VIEW } from './mapView'
import Help from './Help'
import Icon from './Icon'
import { assignmentState } from './assignmentState'
import type { CallFilters } from './callFilters'
import type { Base, Call, Route } from './types'
import 'leaflet/dist/leaflet.css'
export function located<T extends { lat: number | null; lon: number | null }>(c: T): c is T & { lat: number; lon: number } { return c.lat !== null && c.lon !== null }
export function google(base: Base, call: Call) {
  const query = new URLSearchParams({ api: '1', origin: located(base) ? `${base.lat},${base.lon}` : base.address || base.name, destination: `${call.lat},${call.lon}`, travelmode: 'driving' })
  return 'https://www.google.com/maps/dir/?' + query
}
const escapeHtml = (text: string) => text.replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]!))
function Focus({ selected, route, calls, fit, filterKey, layoutKey, selectionFocus }: { selected?: Call; route?: Route; calls: Call[]; fit: number; filterKey:string; layoutKey:string; selectionFocus:number }) {
  const map = useMap()
  useEffect(()=>{const node=map.getContainer();const observer=new ResizeObserver(()=>map.invalidateSize({pan:false}));observer.observe(node);map.invalidateSize({pan:false});return()=>observer.disconnect()},[map,layoutKey])
  useEffect(() => {
    if (route?.coordinates.length) map.fitBounds(route.coordinates.map(([lon, lat]) => [lat, lon]), { padding: [45,45], maxZoom:15, animate:false })
    else if (selected && located(selected)) map.setView([selected.lat,selected.lon],Math.max(14,map.getZoom()),{animate:false})
  }, [map,selected?.id,selected?.lat,selected?.lon,route,selectionFocus])
  useEffect(()=>{
    const points=calls.filter(located).map(c=>[c.lat,c.lon] as [number,number])
    if(points.length)map.fitBounds(points,{padding:[45,45],maxZoom:14,animate:false})
  },[map,fit,filterKey])
  return null
}
function ZoomIcons(){
 const map=useMapEvents({zoomend:()=>update()})
 function update(){map.getContainer().classList.toggle('show-nature-icons',map.getZoom()>=12)}
 useEffect(update,[map])
 return null
}
function ViewControls(){
 const map=useMap()
 return <div className="map-view-controls" onDoubleClick={e=>e.stopPropagation()}><button aria-label="Centralizar área de articulação — Contagem" title="Centralizar área de articulação — Contagem" onClick={e=>{e.stopPropagation();map.setView([DEFAULT_MAP_VIEW.lat,DEFAULT_MAP_VIEW.lon],DEFAULT_MAP_VIEW.zoom,{animate:false})}}><Icon name="calls"/></button><Help>O enquadramento automático mostra as ocorrências filtradas, inclusive atendimentos distantes. Este botão centraliza Contagem com zoom 14. O recebimento de atualizações não reposiciona continuamente o mapa.</Help></div>
}
const MapTiles=memo(function MapTiles(){return <LayersControl position="bottomright" collapsed>
  <LayersControl.BaseLayer checked name="Ruas · OpenStreetMap"><TileLayer attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors' url="https://tile.openstreetmap.org/{z}/{x}/{y}.png" updateWhenIdle keepBuffer={1}/></LayersControl.BaseLayer>
  <LayersControl.BaseLayer name="Satélite · Esri"><TileLayer attribution='Tiles &copy; Esri · Sources: Esri, Vantor, Earthstar Geographics and the GIS User Community · <a href="https://www.arcgis.com/home/item.html?id=10df2279f9684e4a9f6a7f08febac2a9">World Imagery</a>' url="https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}" maxNativeZoom={19} maxZoom={19} updateWhenIdle keepBuffer={1}/></LayersControl.BaseLayer>
</LayersControl>})
const CallPoint=memo(function CallPoint({call,selected,select,openDetails,group,natureKey}:{call:Call;openDetails:(id:string)=>void;selected:boolean;select:(id:string)=>void;group:CallFilters['group'];natureKey:CallFilters['nature']}){
 const nature=matchingNatures(call,{group,nature:natureKey})[0]||naturesOf(call)[0]
 const code=natureGroup(nature)
 const pictogram=natureIcon(nature)
 const label=`Ocorrência ${call.number||call.id} · ${call.nature||'Natureza não informada'} · ${assignmentLabel(call)}`
 const icon=useMemo(()=>{
  const vehicles=(call.assignedResources||[]).map(r=>{const state=assignmentState(r.status);return `<span class="map-vehicle ${state.tone}" title="${escapeHtml((r.prefix||'Recurso '+r.resourceId)+' · '+state.label)}"><b>${escapeHtml(state.symbol)}</b> ${escapeHtml(r.prefix||'Recurso '+r.resourceId)} <small>${escapeHtml(state.label)}</small></span>`}).join('')
  return divIcon({className:'call-map-icon',iconSize:[44,44],iconAnchor:[22,22],tooltipAnchor:[0,0],html:`<span class="nature-marker ${call.assignmentStatus} ${selected?'is-selected':''}" style="--nature-color:${groupColor(code)}"><b>${escapeHtml(code)}</b><em>${escapeHtml(assignmentSymbol(call))}</em></span>${pictogram?`<span class="nature-pictogram" role="img" aria-label="${escapeHtml(pictogram.label)}" title="${escapeHtml(nature.description)}">${pictogram.symbol}</span>`:''}${vehicles?`<span class="map-vehicles">${vehicles}</span>`:''}`})
 },[call,selected,code,pictogram,nature.description])
 const events=useMemo(()=>({click:()=>{select(call.id);openDetails(call.id)}}),[call.id,select,openDetails])
 return <Marker position={[call.lat!,call.lon!]} icon={icon} title={label} alt={label} keyboard bubblingMouseEvents={false} eventHandlers={events}>
  <Tooltip direction="auto" opacity={1} className="occurrence-tooltip"><div className="hover-details"><strong>{call.number||call.id} · {nature.code||'?'}</strong><p>{assignmentLabel(call)}</p></div></Tooltip>

 </Marker>
})
const OperationalMap=memo(function OperationalMap({ calls, bases, selected, select, openDetails, route, filters, fit, layoutKey, selectionFocus }: { calls: Call[]; bases: Base[]; selected?: Call; select: (id: string) => void; openDetails:(id:string)=>void; route?: Route; filters: CallFilters; fit: number; layoutKey:string; selectionFocus:number }) {
  const [initialView]=useState(()=>DEFAULT_MAP_VIEW)
  return <MapContainer center={[initialView.lat,initialView.lon]} zoom={initialView.zoom} className="operational-map" zoomControl zoomAnimation={false} fadeAnimation={false} markerZoomAnimation={false} doubleClickZoom>
    <MapTiles/><ViewControls/><ZoomIcons/>
    {calls.filter(located).map(call=><CallPoint key={call.id} call={call} selected={selected?.id===call.id} select={select} openDetails={openDetails} group={filters.group} natureKey={filters.nature}/>)}
    {bases.filter(located).map(base=><CircleMarker bubblingMouseEvents={false} key={base.id} center={[base.lat,base.lon]} radius={8} pathOptions={{color:'#fff',weight:2,fillColor:'#176a8c',fillOpacity:1}}>
      <Popup><strong>{base.name}</strong><br/>{base.address}<br/>{base.unitId?`Unidade CAD ${base.unitId}`:'Vínculo CAD pendente'}</Popup>
    </CircleMarker>)}
    {route?.coordinates.length?<Polyline positions={route.coordinates.map(([lon,lat])=>[lat,lon])} pathOptions={{color:'#176a8c',weight:5}}/>:null}
    <Focus selectionFocus={selectionFocus} selected={selected} route={route} calls={calls} fit={fit} filterKey={JSON.stringify(filters)} layoutKey={layoutKey}/>
  </MapContainer>
})
export default OperationalMap
