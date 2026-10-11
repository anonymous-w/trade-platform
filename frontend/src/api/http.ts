let csrfToken=''
export interface Page<T=Record<string, unknown>> { rows:T[]; total:number; page:number; size?:number }
export function query(params:Record<string,unknown>){const p=new URLSearchParams(); Object.entries(params).forEach(([k,v])=>{if(v!==undefined && v!==null && String(v).trim()!=='')p.set(k,String(v))});return p.toString()}
export async function refreshCsrf(){const res=await fetch('/api/auth/csrf',{credentials:'same-origin'});if(!res.ok)throw new Error('CSRF initialization failed');csrfToken=(await res.json()).token}
export async function api<T=any>(path:string,opts:RequestInit={}):Promise<T>{
  const headers=new Headers(opts.headers);if(opts.body && !(opts.body instanceof FormData) && !headers.has('Content-Type'))headers.set('Content-Type','application/json');
  const method=(opts.method||'GET').toUpperCase();if(!['GET','HEAD','OPTIONS'].includes(method)){
    if(!csrfToken)await refreshCsrf();headers.set('X-XSRF-TOKEN',csrfToken)
  }
  const res=await fetch('/api'+path,{...opts,headers,credentials:'same-origin'});
  if(!res.ok){let message=`HTTP ${res.status}`;try{const info=await res.json(); message=info.error||info.message||message}catch{};throw new Error(message)}
  if(res.status===204)return undefined as T;
  const ct=res.headers.get('content-type')||'';return (ct.includes('application/json')?await res.json():await res.blob()) as T
}
export async function login(user:string,password:string){
  await refreshCsrf();const body=new URLSearchParams({username:user,password});await api('/auth/login',{method:'POST',body,headers:{'Content-Type':'application/x-www-form-urlencoded'}});await refreshCsrf()
}
export async function logout(){await api('/auth/logout',{method:'POST'});csrfToken='';await refreshCsrf()}
export async function currentUser(){return await api<{username:string,roles:string[]}>('/auth/me')}
export async function download(path:string,fileName:string){const blob=await api<Blob>(path);const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download=fileName;a.click();URL.revokeObjectURL(url)}
export function errorMessage(e:unknown){return e instanceof Error?e.message:String(e)}
