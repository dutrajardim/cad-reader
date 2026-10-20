let token = ''
export function sessionToken(value: string) { token = value }
export async function api<T>(path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const response = await fetch('/api/' + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? {} : { 'Content-Type': 'application/json', 'X-CAD-Token': token },
    body: body === undefined ? undefined : JSON.stringify(body), signal,
  })
  const result = await response.json()
  if (response.status === 401) window.dispatchEvent(new Event('cad-admin-expired'))
  if (!response.ok) throw new Error(result.error || 'Não foi possível concluir a operação.')
  return result as T
}
// Somente versões: não retém cópias dos eventos nem do estado operacional.
export function changes(){
const versions=new Map<string,string>()
return async function changed<T>(path:string,signal:AbortSignal):Promise<T|undefined>{
 const response=await fetch('/api/'+path,{signal,headers:versions.has(path)?{'If-None-Match':versions.get(path)!}:{}})
 if(response.status===304)return undefined
 const result=await response.json()
 if(response.status===401)window.dispatchEvent(new Event('cad-admin-expired'))
 if(!response.ok)throw new Error(result.error||'Não foi possível atualizar os dados locais.')
 if(signal.aborted)return undefined
 const etag=response.headers.get('ETag');if(etag){if(versions.size>30)versions.clear();versions.set(path,etag)}
 return result as T
}

}
