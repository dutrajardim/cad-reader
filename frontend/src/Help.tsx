import { useId } from 'react'
export default function Help({children}:{children:React.ReactNode}){const id=useId();return <span className="help"><button type="button" aria-label="Ajuda" aria-describedby={id}>?</button><span id={id} role="tooltip">{children}</span></span>}
