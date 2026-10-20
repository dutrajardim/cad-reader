/** Regressão visual real de Leaflet. Execute após npm run build, com Chrome e Node 22+. */
import assert from 'node:assert/strict'
import {createServer} from 'node:http'
import {spawn} from 'node:child_process'
import {once} from 'node:events'
import {mkdtemp,readFile,rm} from 'node:fs/promises'
import {tmpdir} from 'node:os'
import {dirname,join,resolve} from 'node:path'
import {fileURLToPath} from 'node:url'

const dist=resolve(dirname(fileURLToPath(import.meta.url)),'../dist')
const home=await mkdtemp(join(tmpdir(),'cad-marker-transition-'))
let status='EC',revision=1,chrome,ws,sequence=0
const pending=new Map(),errors=[]
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms))
const server=createServer(async(request,response)=>{
 try {
  const pathname=new URL(request.url,'http://localhost').pathname
  if(pathname==='/api/state'){
   const assignments=status?[{resourceId:'77',prefix:'ABT6584',status}]:[]
   response.setHeader('Content-Type','application/json')
   response.end(JSON.stringify({token:'fixture',settings:{server:'https://routing.invalid',bases:[],profiles:[{id:'car',name:'Carro',costing:'auto'}]},routing:{connected:false,server:'',notice:''},state:{connected:true,ready:true,status:'Teste local',revision,updatedAt:'2026-10-19T12:00:00Z',scopeUnits:['7'],scopeTypes:[],resources:[],calls:[{id:'1',number:'TESTE',nature:'Teste de atualização',natures:[{code:'X01',description:'Teste'}],status:'A',lat:-19.932,lon:-44.054,address:'Local fictício',updatedAt:'2026-10-19T12:00:00Z',assignedResources:assignments,assignmentStatus:status?'linked':'unlinked'}]}}));return
  }
  if(pathname!=='/'&&!pathname.startsWith('/assets/')){response.writeHead(404).end();return}
  const name=pathname==='/'?'index.html':pathname.slice(1)
  const file=resolve(dist,name)
  if(!file.startsWith(dist+'/')){response.writeHead(404).end();return}
  const bytes=await readFile(file)
  response.setHeader('Content-Type',name.endsWith('.js')?'text/javascript':name.endsWith('.css')?'text/css':name.endsWith('.png')?'image/png':'text/html')
  response.end(bytes)
 }catch{response.writeHead(404).end()}
})
const evaluate=async expression=>{
 const result=await command('Runtime.evaluate',{expression,returnByValue:true})
 if(result.exceptionDetails)throw new Error(result.exceptionDetails.text)
 return result.result.value
}
const command=(method,params={})=>new Promise((resolve,reject)=>{
 const id=++sequence;pending.set(id,{resolve,reject});ws.send(JSON.stringify({id,method,params}))
})
const labels=()=>evaluate(`JSON.stringify({labels:[...document.querySelectorAll('.map-vehicle')].map(n=>n.textContent),markers:document.querySelectorAll('.call-map-icon').length,previousConnected:window.previousMarker?.isConnected,position:document.querySelector('.call-map-icon')?.style.transform})`).then(JSON.parse)
async function waitFor(predicate){
 for(let attempt=0;attempt<30;attempt++){const value=await predicate();if(value)return value;await pause(250)}
 throw new Error('A atualização esperada não apareceu no mapa')
}
try {
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
 const url=`http://127.0.0.1:${server.address().port}`
 chrome=spawn(process.env.CAD_CHROME||'google-chrome-stable',['--headless=new','--no-sandbox','--disable-dev-shm-usage','--disable-background-networking','--no-first-run','--remote-debugging-port=0','--user-data-dir='+home,'--window-size=1280,900',url],{stdio:'ignore'})
 let launchError;chrome.on('error',error=>{launchError=error})
 const port=await waitFor(async()=>{if(launchError)throw launchError;try{return (await readFile(join(home,'DevToolsActivePort'),'utf8')).split('\n')[0]}catch{return false}})
 const pages=await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()
 ws=new WebSocket(pages.find(page=>page.type==='page').webSocketDebuggerUrl)
 await new Promise((resolve,reject)=>{ws.onopen=resolve;ws.onerror=reject})
 ws.onmessage=event=>{
  const result=JSON.parse(event.data)
  if(result.id){const listener=pending.get(result.id);if(listener){pending.delete(result.id);result.error?listener.reject(result.error):listener.resolve(result.result)}}
  else if(result.method==='Runtime.exceptionThrown')errors.push(result.params.exceptionDetails.text)
 }
 await command('Runtime.enable')
 await waitFor(async()=>{const view=await labels();return view.labels.length===1&&view.labels[0].includes('Em controle')})
 const initial=await labels();assert.equal(initial.markers,1)
 await evaluate('window.previousMarker=document.querySelector(".call-map-icon")')
 status='NL';revision++
 await waitFor(async()=>{const view=await labels();return view.labels.length===1&&view.labels[0].includes('No local')})
 const current=await labels()
 assert.equal(current.previousConnected,false,'A camada antiga precisa ser removida do DOM')
 assert.equal(current.markers,1,'Não deve acumular marcadores na mesma posição')
 assert.equal(current.position,initial.position,'A atualização não deve mover o mapa')
 assert(!current.labels.some(label=>label.includes('Em controle')),'O estado anterior não pode permanecer visível')
 await evaluate('window.previousMarker=document.querySelector(".call-map-icon")')
 await pause(3500)
 assert.equal((await labels()).previousConnected,true,'Polling sem alterações deve preservar o marcador')
 const transitions=[
  ['DP','Despachada'],['AC','A caminho'],['DESLOCAMENTO','Em deslocamento'],
  ['ED','Em direção'],['LI','Local intermediário'],['EC','Em controle'],
  ['ER','Em retorno'],['EN','Em encerramento'],['SU','Suspensa'],
  ['NL','No local'],['NO_LOCAL','No local'],['NOVO_ESTADO','NOVO ESTADO'],
 ]
 for(const [nextStatus,label] of transitions){
  const previous=await labels()
  await evaluate('window.previousMarker=document.querySelector(".call-map-icon")')
  status=nextStatus;revision++
  await waitFor(async()=>{const view=await labels();return view.labels.length===1&&view.labels[0].includes(label)})
  // NO_LOCAL e NL têm a mesma representação: espere um poll mesmo sem trocar o texto.
  if(nextStatus==='NO_LOCAL')await pause(3200)
  const view=await labels()
  assert.equal(view.markers,1,`${nextStatus}: não acumular marcadores`)
  assert.equal(view.labels.length,1,`${nextStatus}: apenas uma etiqueta atual`)
  assert.equal(view.position,initial.position,`${nextStatus}: preservar posição`)
  assert.equal(view.previousConnected,view.labels[0]===previous.labels[0],`${nextStatus}: substituir a camada somente quando a representação mudar`)
  console.log(`PASS etiqueta ${nextStatus}: ${label}`)
 }
 await evaluate('window.previousMarker=document.querySelector(".call-map-icon")')
 status='';revision++
 await waitFor(async()=>{const view=await labels();return view.labels.length===0})
 assert.equal((await labels()).markers,1,'Encerrar vínculo mantém apenas o marcador da ocorrência')
 assert.equal((await labels()).previousConnected,false,'A etiqueta finalizada deve ser removida com a camada antiga')
 assert.deepEqual(errors,[])
 console.log('PASS navegador: todos os estados reconhecidos, alias NO_LOCAL e estado desconhecido; encerramento, substituição sem duplicatas e posição preservada')
}finally{
 if(ws)ws.close()
 if(chrome&&chrome.exitCode===null){const closed=once(chrome,'close');chrome.kill();await Promise.race([closed,pause(5000)])}
 await new Promise(resolve=>server.close(resolve))
 await rm(home,{recursive:true,force:true,maxRetries:3,retryDelay:100})
}
