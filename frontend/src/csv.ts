import type { Base, VehicleProfile } from './types'
export const unitColumns = ['id','nome','id_unidade_cad','endereco','latitude','longitude','fonte','consultado_em','observacoes']
export const profileColumns = ['id','nome','modo','altura_m','largura_m','comprimento_m','peso_t','carga_eixo_t']
export type CsvResult<T> = { rows: T[]; errors: string[]; warnings: string[] }
const normalize = (s: string) => s.trim().normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase()
// Campos textuais são protegidos ao abrir no Excel; a importação reverte somente esse escape.
const unprotect = (s: string) => /^'(?:'|[=+@\-\t\r\n])/.test(s) ? s.slice(1) : s
function parse(text: string): { headers: string[]; rows: { line: number; cells: string[] }[] } {
  if (text.length > 262144) throw new Error('O CSV deve ter até 256 KB.')
  text = text.replace(/^\uFEFF/, '')
  if (text.startsWith('sep=;\r\n') || text.startsWith('sep=,\r\n') || text.startsWith('sep=;\n') || text.startsWith('sep=,\n')) text = text.slice(text.indexOf('\n') + 1)
  // Detecta o separador somente no cabeçalho, ignorando o conteúdo entre aspas.
  let inQuote = false, semicolons = 0, commas = 0
  for (let i=0;i<text.length;i++) {
    const c=text[i]
    if (c==='"') { if(inQuote && text[i+1]==='"') i++; else inQuote=!inQuote }
    if(!inQuote && (c==='\r'||c==='\n')) break
    if(!inQuote && c===';') semicolons++
    if(!inQuote && c===',') commas++
  }
  const delimiter = semicolons >= commas ? ';' : ','
  const rows: { line: number; cells: string[] }[] = []
  let cells: string[] = [], cell='', quoted=false, closed=false, line=1, start=1
  const finishCell = () => { cells.push(unprotect(cell)); cell=''; closed=false }
  const finishRow = () => { finishCell(); if(cells.some(c=>c.trim())) rows.push({line:start,cells}); cells=[] }
  for (let i=0;i<text.length;i++) {
    const c=text[i]
    if(quoted) {
      if(c==='"') { if(text[i+1]==='"') {cell+='"';i++} else {quoted=false;closed=true} }
      else {cell+=c; if(c==='\n')line++}
    } else if(c===delimiter) finishCell()
    else if(c==='\r'||c==='\n') { if(c==='\r'&&text[i+1]==='\n')i++; finishRow(); line++;start=line }
    else if(c==='"' && cell==='' && !closed) quoted=true
    else if(c==='"'||closed) throw new Error(`Linha ${line}: aspas ou conteúdo após aspas inválidos.`)
    else cell+=c
  }
  if(quoted) throw new Error(`Linha ${start}: campo com aspas não foi fechado.`)
  if(cell!==''||cells.length||closed)finishRow()
  if(!rows.length)throw new Error('CSV vazio. Use o modelo de cabeçalho disponível na exportação.')
  const headers=rows.shift()!.cells.map(normalize)
  if(headers.some(h=>!h)||new Set(headers).size!==headers.length) throw new Error('Há colunas vazias ou repetidas no cabeçalho.')
  return {headers,rows}
}
function num(raw: string, field: string, min: number, max: number, optional = true): number | null {
  raw=raw.trim()
  if(!raw && optional)return null
  const normalized=raw.replace(/^([+-]?\d+),(\d+)$/, '$1.$2')
  if(!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$/i.test(normalized))throw new Error(`${field}: número inválido.`)
  const value=Number(normalized)
  if(!Number.isFinite(value)||value<min||value>max)throw new Error(`${field}: fora do intervalo ${min} a ${max}.`)
  return value
}
function text(raw: string, field: string, max: number, required=false) {
  raw=raw.trim()
  if((required&&!raw)||raw.length>max)throw new Error(`${field}: ${required?'obrigatório, ':''}máximo de ${max} caracteres.`)
  return raw
}
function sourceUrl(raw: string) {
  const value=text(raw,'Fonte',600)
  if(!value)return ''
  try {const u=new URL(value);if(!['http:','https:'].includes(u.protocol)||u.username||u.password)throw new Error();return value}
  catch {throw new Error('Fonte: informe uma URL HTTP/HTTPS sem credenciais.')}
}
function date(raw: string) {
  const value=raw.trim()
  if(value && (!/^\d{4}-\d{2}-\d{2}$/.test(value)||Number.isNaN(Date.parse(value))||new Date(value).toISOString().slice(0,10)!==value))throw new Error('Data de consulta: use AAAA-MM-DD válido.')
  return value
}
const aliases: Record<string,string> = {name:'nome',unitid:'id_unidade_cad',address:'endereco',lat:'latitude',lon:'longitude',sourceurl:'fonte',checkedat:'consultado_em',notes:'observacoes',costing:'modo',height:'altura_m',width:'largura_m',length:'comprimento_m',weight:'peso_t',axle_load:'carga_eixo_t'}
function read<T>(input: string, columns: string[], convert: (v: Record<string,string>)=>T): CsvResult<T> {
  const result: CsvResult<T>={rows:[],errors:[],warnings:[]}
  try {
    const parsed=parse(input), headers=parsed.headers.map(h=>Object.hasOwn(aliases,h)?aliases[h]:h)
    if(new Set(headers).size!==headers.length)throw new Error('Cabeçalho repete um campo usando nomes diferentes.')
    if(!headers.includes('nome'))throw new Error('Cabeçalho obrigatório ausente: nome.')
    if(columns===profileColumns && !headers.includes('modo'))throw new Error('Cabeçalho obrigatório ausente: modo.')
    for(const h of headers)if(!columns.includes(h))result.warnings.push(`Coluna ignorada: ${h}.`)
    const ids=new Set<string>()
    for(const row of parsed.rows) {
      try {
        if(row.cells.length!==headers.length)throw new Error(`esperadas ${headers.length} colunas, recebidas ${row.cells.length}.`)
        const record=Object.fromEntries(columns.map(k=>[k,'']))
        headers.forEach((key,i)=>record[key]=row.cells[i])
        const id=text(record.id,'ID',80)||crypto.randomUUID()
        if(ids.has(id))throw new Error('ID repetido no arquivo.')
        ids.add(id);record.id=id
        result.rows.push(convert(record))
      } catch(e) {result.errors.push(`Linha ${row.line}: ${(e as Error).message}`)}
    }
    if(!parsed.rows.length)result.errors.push('O CSV não contém registros.')
  } catch(e) {result.errors.push((e as Error).message)}
  return result
}
export function importUnits(input: string): CsvResult<Base> {
  const result=read(input,unitColumns,v=>{
    const unitId=text(v.id_unidade_cad,'ID da unidade CAD',30)
    if(unitId&&!/^\d+$/.test(unitId))throw new Error('ID da unidade CAD deve ser numérico ou vazio.')
    const lat=num(v.latitude,'Latitude',-90,90), lon=num(v.longitude,'Longitude',-180,180)
    if((lat===null)!==(lon===null))throw new Error('Informe latitude e longitude juntas ou deixe ambas vazias.')
    const address=text(v.endereco,'Endereço',600)
    if(lat===null&&!address)throw new Error('Informe endereço ou coordenadas da unidade.')
    return {id:v.id,name:text(v.nome,'Nome',120,true),unitId,lat,lon,address,sourceUrl:sourceUrl(v.fonte),checkedAt:date(v.consultado_em),notes:text(v.observacoes,'Observações',2000)}
  })
  for(const unit of result.rows) {
    if(!unit.unitId)result.warnings.push(`${unit.name}: falta vincular o ID da unidade CAD.`)
    if(unit.lat===null)result.warnings.push(`${unit.name}: coordenadas pendentes; disponível por endereço no Google Maps.`)
  }
  if(result.rows.length>200)result.errors.push('Máximo de 200 unidades por cadastro.')
  return result
}
export function importProfiles(input: string): CsvResult<VehicleProfile> {
  const result=read(input,profileColumns,v=>{
    const mode=normalize(v.modo)
    const costing=mode==='auto'||mode==='carro'?'auto':mode==='truck'||mode==='caminhao'?'truck':null
    if(!costing)throw new Error('Modo deve ser auto/carro ou truck/caminhão.')
    const profile: VehicleProfile={id:v.id,name:text(v.nome,'Nome',100,true),costing}
    const fields=[['altura_m','height'],['largura_m','width'],['comprimento_m','length'],['peso_t','weight'],['carga_eixo_t','axle_load']] as const
    for(const [column,key]of fields) {const value=num(v[column],column,Number.MIN_VALUE,1000);if(value!==null)profile[key]=value}
    return profile
  })
  if(result.rows.length>30)result.errors.push('Máximo de 30 perfis por cadastro.')
  return result
}
function cell(value: string|number|null|undefined) {
  let s=value==null?'':String(value)
  if(typeof value==='string' && /^(?:'|[=+@\-\t\r\n])/.test(s))s="'"+s
  return /[;",\r\n]/.test(s)?'"'+s.replace(/"/g,'""')+'"':s
}
function csv(columns: string[], rows: (string|number|null|undefined)[][]) {
  return '\uFEFF'+[columns.join(';'),...rows.map(row=>row.map(cell).join(';'))].join('\r\n')+'\r\n'
}
export function exportUnits(rows: Base[]) {return csv(unitColumns,rows.map(b=>[b.id,b.name,b.unitId,b.address,b.lat,b.lon,b.sourceUrl,b.checkedAt,b.notes]))}
export function exportProfiles(rows: VehicleProfile[]) {return csv(profileColumns,rows.map(p=>[p.id,p.name,p.costing,p.height,p.width,p.length,p.weight,p.axle_load]))}
export function mergeById<T extends { id: string }>(current: T[], imported: T[], mode: 'merge'|'replace'): T[] {
  if(mode==='replace')return [...imported]
  const combined=new Map(current.map(row=>[row.id,row]));for(const row of imported)combined.set(row.id,row)
  return [...combined.values()]
}
export function downloadCsv(name: string, content: string) {
  const url=URL.createObjectURL(new Blob([content],{type:'text/csv;charset=utf-8'}))
  const anchor=document.createElement('a');anchor.href=url;anchor.download=name;anchor.click()
  setTimeout(()=>URL.revokeObjectURL(url),1000)
}
