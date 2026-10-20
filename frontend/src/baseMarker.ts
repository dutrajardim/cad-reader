/** Escudo próprio com capacete de bombeiro; não reproduz o brasão oficial do CBMMG. */
export const stationSymbol='<svg viewBox="0 0 40 40" aria-hidden="true" focusable="false"><path d="M20 2 36 8v12c0 9-9 15-16 18C13 35 4 29 4 20V8Z" fill="#dc2626" stroke="#fff" stroke-width="2" stroke-linejoin="round"/><path d="M20 6 32 10.5V20c0 6.5-6.5 11.5-12 14-5.5-2.5-12-7.5-12-14v-9.5Z" fill="none" stroke="#facc15" stroke-width="1.2" stroke-linejoin="round"/><path d="M11 22v-2a9 9 0 0 1 18 0v2Z" fill="#facc15" stroke="#7f1d1d" stroke-width="1.2"/><path d="M18 12h4v10h-4Z" fill="#fef3c7" stroke="#a16207" stroke-width=".8"/><path d="M9 22h22l1 3H8Z" fill="#fde047" stroke="#7f1d1d" stroke-width="1.2" stroke-linejoin="round"/><path d="M14 25v3h12v-3" fill="none" stroke="#fff" stroke-width="1.5" stroke-linejoin="round"/></svg>'
export function baseMarkerHtml(name:string){
 const escaped=name.replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]!))
 return `<span class="station-marker">${stationSymbol}</span><span class="station-label">${escaped}</span>`
}
