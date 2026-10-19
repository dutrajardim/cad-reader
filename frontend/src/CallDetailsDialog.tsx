import {useEffect,useRef,type ReactNode} from 'react'
import {createPortal} from 'react-dom'
import CallDetailsPanel from './CallDetailsPanel'

export default function CallDetailsDialog({id,onClose,children}:{id:string;onClose:()=>void;children?:ReactNode}){
 const dialog=useRef<HTMLDialogElement>(null)
 useEffect(()=>{const node=dialog.current!;node.showModal();return()=>node.close()},[])
 return createPortal(<dialog ref={dialog} className="call-details-dialog" aria-label="Detalhes e histórico da ocorrência" onCancel={onClose} onClick={e=>{if(e.target===e.currentTarget){const r=e.currentTarget.getBoundingClientRect();if(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom)onClose()}}}>
  <div className="call-dialog-heading"><strong>Detalhes e histórico recebido</strong><button type="button" className="button secondary" autoFocus onClick={onClose} aria-label="Fechar detalhes da ocorrência" title="Fechar detalhes da ocorrência">×</button></div>
  <CallDetailsPanel id={id} comparison={children}/>
 </dialog>,document.fullscreenElement||document.body)
}
