import type { AssignmentStatus, Call, Nature } from './types'
export type FilterSelection = string | string[]
export type CallFilters = { search: string; group: FilterSelection; nature: FilterSelection; assignment: 'all'|AssignmentStatus|AssignmentStatus[] }
export function includesSelection(selection: FilterSelection, value: string) { return Array.isArray(selection) ? selection.length===0 || selection.includes(value) : selection==='all' || selection===value }
export const normalizeSearch = (s: string) => s.normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().trim()
export function naturesOf(call: Call): Nature[] { return call.natures?.length ? call.natures : [{code:'',description:call.nature||'Natureza não informada'}] }
export function natureGroup(n: Nature) {return n.code.trim().toUpperCase().match(/^[A-Z]/)?.[0] || '?'}
export function natureKey(n: Nature) {return n.code.trim().toUpperCase() || '@'+n.description}
export function matchingNatures(call: Call, filters: Pick<CallFilters,'group'|'nature'>) {
  return naturesOf(call).filter(n=>(includesSelection(filters.group,natureGroup(n)))&&(includesSelection(filters.nature,natureKey(n))))
}
export function filterCalls(calls: Call[], filters: CallFilters) {
  const search=normalizeSearch(filters.search)
  return calls.filter(c=>matchingNatures(c,filters).length>0 && (includesSelection(filters.assignment,c.assignmentStatus))
    && normalizeSearch(`${c.number} ${c.id} ${c.nature} ${c.address} ${(c.assignedResources||[]).map(r=>r.prefix).join(' ')}`).includes(search))
}
export function natureOptions(calls: Call[], group: FilterSelection) {
  const items=new Map<string,Nature>()
  for(const c of calls)for(const n of naturesOf(c))if(includesSelection(group,natureGroup(n)))items.set(natureKey(n),n)
  return [...items.values()].sort((a,b)=>natureKey(a).localeCompare(natureKey(b),'pt-BR'))
}
export function groupOptions(calls: Call[]) {
  const counts=new Map<string,number>()
  for(const c of calls)for(const group of new Set(naturesOf(c).map(natureGroup)))counts.set(group,(counts.get(group)||0)+1)
  return [...counts.entries()].sort(([a],[b])=>a.localeCompare(b))
}
export function groupColor(group: string) {
  const colors=['#146882','#b34c18','#7253a0','#246e52','#a33f69','#6655b5','#986617','#137b83','#ad3e39','#416998']
  return group==='?'?'#697889':colors[(group.charCodeAt(0)-65+colors.length)%colors.length]
}
export function assignmentLabel(call: Call) {
  return ({linked:'Viatura vinculada',reported:'Empenho informado · viatura não identificada',unlinked:'Sem vínculo observado',unknown:'Vínculo ainda não confirmado'})[call.assignmentStatus] || 'Vínculo ainda não confirmado'
}
export function assignmentSymbol(call: Call) {return call.assignmentStatus==='linked'?'V':call.assignmentStatus==='unlinked'?'—':'?'}
