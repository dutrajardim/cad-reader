import {useMemo} from 'react'
import type {Resource} from './types'
import {resourceSummary} from './resourceSummary'
import Help from './Help'
export default function ResourceSummary({resources,ready,compact=false}:{resources:Resource[];ready:boolean;compact?:boolean}){
 const summary=useMemo(()=>resourceSummary(resources),[resources])
 const content=<><div className="resource-summary-counts">{[['Disponíveis sem restrição informada',summary.availableUnrestricted],['Disponíveis com restrição',summary.availableRestricted],['Empenhadas',summary.assigned],['Indisponíveis',summary.unavailable],['Outras / não informadas',summary.other]].map(([label,count])=><div key={label}><strong>{count}</strong><span>{label}</span></div>)}</div><p>{summary.total} viaturas · {summary.available} disponíveis no total · {summary.restricted} com restrição em qualquer situação</p>{!ready&&<p className="danger">Dados da última atualização; disponibilidade não confirmada.</p>}</>
 return compact?<details className="resource-summary compact"><summary>Situação das viaturas <b>{ready?`${summary.availableUnrestricted} disponíveis sem restrição informada · ${summary.availableRestricted} disponíveis com restrição / ${summary.total}`:`Disponibilidade não confirmada · ${summary.total} recursos`}</b></summary>{content}</details>:<section className="card resource-summary"><h3>Situação das viaturas</h3><Help>Resumo dos recursos recebidos no filtro CAD. As disponíveis são separadas conforme o motivo de restrição enviado pelo CAD. Um empenho ativo conhecido prevalece na classificação. Disponível sem restrição informada não confirma equipe ou aptidão operacional.</Help>{content}</section>
}
