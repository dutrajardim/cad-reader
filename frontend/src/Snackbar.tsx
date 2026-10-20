import {useEffect,useState} from 'react'
import {createPortal} from 'react-dom'

type Notice={message:string;kind:'success'|'error'}
/** Avisos compartilhados pelas páginas; um aviso novo substitui o anterior. */
export function notify(message:string){
 window.dispatchEvent(new CustomEvent<Notice>('cad-confirmation',{detail:{message,kind:'success'}}))
}
export function notifyError(message:string){
 if(!message)return
 window.dispatchEvent(new CustomEvent<Notice>('cad-confirmation',{detail:{message,kind:'error'}}))
}
/** Erros de polling repetidos não reiniciam o aviso a cada atualização. */
export function useErrorSnackbar(message:string){
 useEffect(()=>{if(message)notifyError(message)},[message])
}
export default function Snackbar(){
 const [notice,setNotice]=useState<Notice|null>(null)
 const [hovered,setHovered]=useState(false),[focused,setFocused]=useState(false)
 const [target,setTarget]=useState<Element>(document.body)
 useEffect(()=>{
  const receive=(event:Event)=>{
   const value=(event as CustomEvent<Notice>).detail
   if(!value||typeof value.message!=='string')return
   if(!value.message){setHovered(false);setFocused(false)}
   setTarget(document.querySelector('dialog[open]')||document.fullscreenElement||document.body)
   setNotice(value.message?{...value}:null)
  }
  const retarget=()=>setTarget(document.querySelector('dialog[open]')||document.fullscreenElement||document.body)
  const observer=new MutationObserver(retarget)
  observer.observe(document.body,{subtree:true,attributes:true,attributeFilter:['open'],childList:true})
  window.addEventListener('cad-confirmation',receive)
  document.addEventListener('fullscreenchange',retarget)
  return()=>{observer.disconnect();window.removeEventListener('cad-confirmation',receive);document.removeEventListener('fullscreenchange',retarget)}
 },[])
 useEffect(()=>{
  if(!notice||hovered||focused)return
  const timer=setTimeout(()=>setNotice(null),6000)
  return()=>clearTimeout(timer)
 },[notice,hovered,focused])
 if(!notice)return null
 const error=notice.kind==='error'
 return createPortal(<div className={'snackbar'+(error?' snackbar-error':'')} role={error?'alert':'status'} aria-live={error?'assertive':'polite'} aria-atomic="true" onMouseEnter={()=>setHovered(true)} onMouseLeave={()=>setHovered(false)} onFocusCapture={()=>setFocused(true)} onBlurCapture={event=>{if(!event.currentTarget.contains(event.relatedTarget))setFocused(false)}}><span className="snackbar-check" aria-hidden="true">{error?'!':'✓'}</span><span className="snackbar-message">{notice.message}</span><button type="button" aria-label="Fechar aviso" onClick={()=>{setNotice(null);setHovered(false);setFocused(false)}}>×</button></div>,target)
}
