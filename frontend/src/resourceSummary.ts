import type {Resource} from './types'
import {normalizeSearch} from './callFilters'
export function resourceSummary(resources:Resource[]){
 const counts={total:resources.length,available:0,assigned:0,unavailable:0,other:0,restricted:0,availableUnrestricted:0,availableRestricted:0}
 for(const r of resources){
  const state=normalizeSearch(r.status).toUpperCase()
  const linked=!!r.assignments?.some(a=>!['TR','TERMINADO','ENCERRADO',''].includes(a.status))
  if(r.restriction?.trim())counts.restricted++
  if(linked||['E','EMPENHADO','EMPENHADA'].includes(state))counts.assigned++
  else if(['D','DISPONIVEL'].includes(state)){counts.available++;if(r.restriction?.trim())counts.availableRestricted++;else counts.availableUnrestricted++}
  else if(['I','INDISPONIVEL'].includes(state))counts.unavailable++
  else counts.other++
 }
 return counts
}
