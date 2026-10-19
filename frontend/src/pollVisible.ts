/** Polling somente do servidor local: serial, pausado em segundo plano e cancelável. */
export function pollVisible(task:(signal:AbortSignal)=>Promise<void>,delay=3000){
 let stopped=false,running=false,timer:ReturnType<typeof setTimeout>|undefined,controller:AbortController|undefined
 const tick=async()=>{
  if(stopped||document.hidden||running)return
  running=true;controller=new AbortController()
  try{await task(controller.signal)}finally{running=false;if(!stopped&&!document.hidden)timer=setTimeout(()=>void tick(),delay)}
 }
 const visibility=()=>{clearTimeout(timer);if(document.hidden)controller?.abort();else void tick()}
 document.addEventListener('visibilitychange',visibility);void tick()
 return()=>{stopped=true;clearTimeout(timer);controller?.abort();document.removeEventListener('visibilitychange',visibility)}
}
