import {ref} from 'vue'
let csrfToken=''
let csrfRequest:Promise<void>|null=null
export const user=ref<{username:string;roles:string[]}|null>(null)
export class ApiError extends Error { constructor(message:string,public status:number){super(message)} }
export interface Page<T=Record<string, unknown>> { rows:T[]; total:number; page:number; size?:number }
export function query(params:Record<string,unknown>){const p=new URLSearchParams();Object.entries(params).forEach(([k,v])=>{if(v!==undefined&&v!==null&&String(v).trim()!=='')p.set(k,String(v))});return p.toString()}
async function request(url:string,opts:RequestInit={}){
  try{return await fetch(url,{...opts,credentials:'same-origin'})}
  catch(e){if(e instanceof DOMException&&e.name==='AbortError')throw e;throw new ApiError('サーバーに接続できません。接続とコンテナの起動状態を確認してください。',0)}
}
export async function refreshCsrf(){
  if(!csrfRequest)csrfRequest=(async()=>{
    const res=await request('/api/auth/csrf');
    if(!res.ok)throw new ApiError('認証情報を取得できません。しばらくしてから再度お試しください。',res.status)
    const data=await res.json();if(!data.token)throw new ApiError('認証情報が不正です。画面を再読み込みしてください。',502)
    csrfToken=data.token
  })().finally(()=>{csrfRequest=null})
  return csrfRequest
}
export async function api<T=any>(path:string,opts:RequestInit={},retried=false):Promise<T>{
  const headers=new Headers(opts.headers)
  if(opts.body&&!(opts.body instanceof FormData)&&!headers.has('Content-Type'))headers.set('Content-Type','application/json')
  const unsafe=!['GET','HEAD','OPTIONS'].includes((opts.method||'GET').toUpperCase())
  if(unsafe){if(!csrfToken)await refreshCsrf();headers.set('X-XSRF-TOKEN',csrfToken)}
  const res=await request('/api'+path,{...opts,headers})
  if(unsafe&&!path.startsWith('/auth/')&&!path.startsWith('/notifications'))window.dispatchEvent(new Event('business-operation-complete'))
  if(!res.ok){
    let info:any={};try{info=await res.json()}catch{}
    // Only retry a request rejected by the CSRF filter before reaching a controller.
    if(unsafe&&res.status===403&&info.code==='CSRF_INVALID'&&!retried){await refreshCsrf();return api<T>(path,opts,true)}
    if(res.status===401&&path!=='/auth/login'){
      user.value=null;csrfToken=''
      if(path!=='/auth/me')window.dispatchEvent(new Event('session-expired'))
    }
    const fallback=res.status===401?'再度ログインしてください。':res.status===403?'この操作を行う権限がありません。':res.status===502||res.status===503?'バックエンドが起動中、または接続できません。':`リクエストに失敗しました (${res.status})`
    throw new ApiError(info.error||info.message||fallback,res.status)
  }
  if(res.status===204)return undefined as T
  return (res.headers.get('content-type')?.includes('application/json')?await res.json():await res.blob()) as T
}
export async function login(username:string,password:string){
  await refreshCsrf();await api('/auth/login',{method:'POST',body:new URLSearchParams({username,password}),headers:{'Content-Type':'application/x-www-form-urlencoded'}})
  csrfToken='';await currentUser()
}
export async function logout(){await api('/auth/logout',{method:'POST'});csrfToken='';user.value=null}
export async function currentUser(){const me=await api<{username:string;roles:string[]}>('/auth/me');user.value=me;return me}
export async function download(path:string,fileName:string){const blob=await api<Blob>(path);const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download=fileName;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)}
export function errorMessage(e:unknown){return e instanceof Error?e.message:String(e)}
