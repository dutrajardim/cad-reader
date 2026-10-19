import Icon from './Icon'
import type {RoutingStatus,Snapshot} from './types'
export default function ConnectionIndicators({state,routing,unavailable,openCad,openRouting}:{state:Snapshot;routing:RoutingStatus;unavailable:boolean;openCad:()=>void;openRouting:()=>void}){
 const cad=unavailable?'Sem comunicação com a aplicação Java':state.ready?'CAD em acompanhamento':state.connected?(state.callFilterId?'CAD carregando':'CAD conectado · aguardando monitoramento'):'CAD desconectado'
 const valhalla=unavailable?'Estado do Valhalla indisponível':routing.connected?'Valhalla conectado':'Valhalla desconectado'
 return <div className="connection-indicators">{[{label:cad,icon:'cad' as const,tone:unavailable?'offline':state.ready?'online':state.connected?'pending':'offline',action:openCad},{label:valhalla,icon:'route' as const,tone:!unavailable&&routing.connected?'online':'offline',action:openRouting}].map(item=><span key={item.icon} className={'status-indicator '+item.tone}><button aria-label={item.label} onClick={item.action}><Icon name={item.icon}/><i/></button><span role="tooltip">{item.label}</span></span>)}</div>
}
