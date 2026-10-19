import type {Call,CountRow} from './types'
export function areaSummary(calls:Call[]):CountRow[]{
 const counts=new Map<string,{label:string;count:number}>()
 for(const call of calls){const seen=new Set<string>();const areas=call.areas?.length?call.areas:[{id:'',name:'Área BM não informada'}]
  for(const area of areas){const id=area.id||'@'+area.name;if(seen.has(id))continue;seen.add(id);const existing=counts.get(id);if(existing)existing.count++;else counts.set(id,{label:area.id?`${area.name} (${area.id})`:area.name,count:1})}
 }
 return [...counts.values()].sort((a,b)=>b.count-a.count||a.label.localeCompare(b.label,'pt-BR'))
}
