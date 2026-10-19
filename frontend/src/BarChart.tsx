import type {CountRow} from './types'
export default function BarChart({items}:{items:(CountRow&{color?:string})[]}){const max=Math.max(1,...items.map(v=>v.count));return <div className="chart-bars">{items.map(v=><div key={v.label}><span>{v.label}</span><div><i style={{width:`${v.count/max*100}%`,background:v.color||'#176a8c'}}/></div><strong>{v.count}</strong></div>)}</div>}
