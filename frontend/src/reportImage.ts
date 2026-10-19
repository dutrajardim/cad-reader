import type {CountRow,SummaryReport} from './types'
import {cadDate} from './format'
/** Imagem de compartilhamento renderizada localmente, sem imagens/fontes ou serviços externos. */
export async function reportImage(report:SummaryReport):Promise<Blob>{
 const groups=[{title:'Ocorrências por natureza',rows:report.natures},{title:'Ocorrências por unidade de área — BM',rows:report.areas}]
 const height=420+groups.reduce((sum,group)=>sum+120+Math.min(group.rows.length,12)*64,0)
 const canvas=document.createElement('canvas');canvas.width=1200;canvas.height=height
 const ctx=canvas.getContext('2d');if(!ctx)throw new Error('O navegador não disponibilizou a geração da imagem.')
 ctx.fillStyle='#f2f5f8';ctx.fillRect(0,0,1200,height);ctx.fillStyle='#101c2e';ctx.fillRect(0,0,1200,160)
 const text=(value:string,x:number,y:number,size:number,color='#273f54',bold=false,max=1080)=>{ctx.font=`${bold?'600':'400'} ${size}px sans-serif`;ctx.fillStyle=color;let label=value;if(ctx.measureText(label).width>max){while(label.length&&ctx.measureText(label+'…').width>max)label=label.slice(0,-1);label+='…'}ctx.fillText(label,x,y)}
 text('CAD Reader · Somatório',48,55,30,'#fff',true);text(report.filterName,48,106,28,'#d2e6f0',false);text('Consultado em '+cadDate(report.consultedAt)+' · filtro '+report.filterId,48,141,19,'#b5cad9')
 text(String(report.total),48,232,58,'#176a8c',true);text('ocorrências únicas',230,222,25);text(report.limited?'ATENÇÃO: resultado possivelmente incompleto.':'Consulta pontual · sem atualização automática',48,278,20,report.limited?'#a94c38':'#61788d')
 let y=312
 for(const group of groups){
  text(group.title,48,y+25,26,'#273f54',true);y+=65
  const rows=[...group.rows].sort((a,b)=>b.count-a.count||a.label.localeCompare(b.label,'pt-BR')).slice(0,12)
  const max=Math.max(1,...rows.map(row=>row.count))
  for(const row of rows){text(row.label,48,y+18,20,'#273f54',false,1020);text(String(row.count),1090,y+18,22,'#176a8c',true,80);ctx.fillStyle='#e2eaf0';ctx.fillRect(48,y+30,1080,12);ctx.fillStyle='#176a8c';ctx.fillRect(48,y+30,1080*row.count/max,12);y+=64}
  if(!rows.length){text('Nenhum registro nesta consulta.',48,y+20,19,'#71869a');y+=32}
  if(group.rows.length>12)text(`Exibidas 12 de ${group.rows.length} categorias. O texto contém todas.`,48,y+20,17,'#6e8295')
  y+=35
 }
 text('Uma ocorrência pode aparecer em mais de uma natureza ou área.',48,height-60,18,'#6e8295');text('Área ausente não é substituída pela unidade de atendimento.',48,height-30,18,'#6e8295')
 return new Promise((resolve,reject)=>canvas.toBlob(blob=>blob?resolve(blob):reject(new Error('Falha ao gerar PNG.')),'image/png'))
}
export function reportTop(rows:CountRow[]){return [...rows].sort((a,b)=>b.count-a.count||a.label.localeCompare(b.label,'pt-BR'))}
