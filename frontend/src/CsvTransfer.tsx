import { useRef, useState } from 'react'
import Help from './Help'
import { downloadCsv, mergeById } from './csv'
import type { CsvResult } from './csv'
export default function CsvTransfer<T extends { id: string; name: string }>({ items, read, write, apply, filename, limit, disabled }: {
  items: T[]; read: (text: string) => CsvResult<T>; write: (rows: T[]) => string; apply: (rows: T[]) => void; filename: string; limit: number; disabled: boolean
}) {
  const input = useRef<HTMLInputElement>(null)
  const [preview, setPreview] = useState<CsvResult<T>>()
  const [mode, setMode] = useState<'merge'|'replace'>('merge')
  const [notice, setNotice] = useState('')
  const combined = preview ? mergeById(items, preview.rows, mode) : []
  const updated = preview?.rows.filter(row => items.some(old => old.id === row.id)).length || 0
  const removed = mode === 'replace' ? items.filter(old => !preview?.rows.some(row => row.id === old.id)).length : 0
  return <section className="csv-transfer" aria-label="Transferência CSV">
    <div className="csv-actions"><div><strong>Importar e exportar CSV</strong><Help>UTF-8 · separador ponto e vírgula ou vírgula · números com ponto ou vírgula decimal. Importe, confira a prévia, aplique à lista e salve o cadastro. A exportação inclui a lista da aba.</Help></div>
      <button className="button secondary" disabled={disabled} onClick={() => input.current?.click()}>Importar CSV</button>
      <button className="button secondary" disabled={disabled} onClick={() => downloadCsv(filename, write(items))}>Exportar CSV</button>
      <input ref={input} type="file" accept=".csv,text/csv" aria-label="Arquivo CSV" hidden onChange={async e => {
        const file=e.target.files?.[0]; e.target.value=''; if(!file)return
        setNotice('');setMode('merge')
        try {if(file.size>262144)throw new Error('O CSV deve ter até 256 KB.');setPreview(read(await file.text()))}
        catch(error) {setPreview({rows:[],warnings:[],errors:[(error as Error).message]})}
      }} />
    </div>
    {notice && <p role="status" className="import-notice">{notice}</p>}
    {preview && <div className="csv-preview" role="region" aria-label="Prévia da importação CSV">
      <h3>Prévia da importação</h3><p>{preview.rows.length} registro(s) válido(s) · {preview.errors.length} erro(s). Nenhum dado foi alterado.</p>
      {preview.errors.length>0 && <div role="alert" className="csv-errors"><strong>Corrija o arquivo antes de importar.</strong><ul>{preview.errors.slice(0,30).map((e,i)=><li key={i}>{e}</li>)}</ul>{preview.errors.length>30&&<p>Mais {preview.errors.length-30} erro(s).</p>}</div>}
      {preview.warnings.length>0 && <details open><summary>Observações ({preview.warnings.length})</summary><ul>{preview.warnings.map((w,i)=><li key={i}>{w}</li>)}</ul></details>}
      {preview.rows.length>0 && <><div className="csv-table"><table><thead><tr><th>Nome</th><th>ID local</th><th>Ação ao combinar</th></tr></thead><tbody>{preview.rows.slice(0,200).map(row=><tr key={row.id}><td>{row.name}</td><td>{row.id}</td><td>{items.some(old=>old.id===row.id)?'Atualizar pelo ID':'Adicionar'}</td></tr>)}</tbody></table></div>
        <label>Como aplicar<select value={mode} onChange={e=>setMode(e.target.value as 'merge'|'replace')}><option value="merge">Combinar com a lista atual (mesmo ID atualiza)</option><option value="replace">Substituir a lista inteira pelo CSV</option></select></label>
        <p className={removed ? 'danger' : ''}>{mode==='merge'?`${preview.rows.length-updated} novo(s), ${updated} atualização(ões); registros ausentes serão mantidos.`:`A lista será substituída; ${removed} registro(s) atual(is) ficarão de fora.`}</p>
        {combined.length>limit&&<p role="alert" className="danger">A lista final excede o limite de {limit} registros.</p>}
      </>}
      <div className="csv-actions"><button className="button primary" disabled={disabled||!preview.rows.length||!!preview.errors.length||combined.length>limit} onClick={()=>{apply(combined);setNotice('CSV aplicado à lista. Clique em Salvar cadastro para gravar no computador.');setPreview(undefined)}}>Aplicar à lista</button><button className="text-button" onClick={()=>setPreview(undefined)}>Cancelar importação</button></div>
    </div>}
  </section>
}
