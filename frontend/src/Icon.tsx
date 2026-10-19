export type IconName='map'|'dashboard'|'calls'|'truck'|'events'|'activity'|'units'|'profiles'|'settings'|'cad'|'route'|'hide'|'expand'|'info'|'arrow'
const paths:Record<IconName,React.ReactNode>={
 map:<><path d="m3 6 6-3 6 3 6-3v15l-6 3-6-3-6 3Z"/><path d="M9 3v15M15 6v15"/></>,
 dashboard:<><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="11" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="18" width="7" height="3" rx="1"/></>,
 calls:<><path d="M20 10c0 6-8 11-8 11S4 16 4 10a8 8 0 1 1 16 0Z"/><circle cx="12" cy="10" r="2"/></>,
 truck:<><path d="M3 6h11v11H3ZM14 10h4l3 4v3h-7"/><circle cx="7" cy="18" r="2"/><circle cx="18" cy="18" r="2"/></>,
 events:<><path d="M8 3h13M8 9h13M8 15h13M8 21h13M3 3h.01M3 9h.01M3 15h.01M3 21h.01"/></>,
 activity:<path d="M2 12h5l3-8 4 16 3-8h5"/>,
 units:<><path d="M3 21V7l9-4 9 4v14ZM8 21v-7h8v7M8 8h.01M16 8h.01"/></>,
 profiles:<><circle cx="9" cy="7" r="4"/><path d="M2 21v-3a7 7 0 0 1 11-6M17 10v12M13 16h8"/></>,
 settings:<><circle cx="12" cy="12" r="3"/><path d="m9 3-1 3-3 1-2 3 2 2-1 4 3 2 3-1 2 4 3-1 1-3 4-1 1-4-3-2 1-3-4-2-3 1Z"/></>,
 cad:<><rect x="3" y="3" width="18" height="13" rx="2"/><path d="M8 21h8M12 16v5M6 10h3l2-3 3 6 2-3h2"/></>,
 route:<><circle cx="5" cy="5" r="2"/><circle cx="19" cy="19" r="2"/><path d="M7 5h9a4 4 0 0 1 0 8H8a4 4 0 0 0 0 8h4"/></>,
 hide:<><path d="M4 4h16v16H4ZM9 4v16M15 9l-3 3 3 3"/></>,
 expand:<path d="M8 3H3v5M16 3h5v5M3 16v5h5M21 16v5h-5"/>,
 info:<><circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7h.01"/></>,
 arrow:<path d="M4 12h16M14 6l6 6-6 6"/>
}
export default function Icon({name}:{name:IconName}){return <svg className="app-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{paths[name]}</svg>}
