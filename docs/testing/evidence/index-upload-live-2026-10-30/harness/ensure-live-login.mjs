import fs from 'node:fs/promises';import path from 'node:path';import {execFileSync} from 'node:child_process';
/** 仅复用固定合成账号，验证码正常识读，凭据驻留内存，不保存会话令牌。 */
export async function ensureLogin(context,page,out){
 const root=process.cwd(),base='http://127.0.0.1:5198';const account=JSON.parse(await fs.readFile(path.join(root,'tmp/local-acceptance/account.json'),'utf8'));
 const check=async()=>{const r=await context.request.get(base+'/api/v1/auth/me');const j=await r.json();return r.ok()&&j.data?.userId===account.userId&&j.data?.tenantId===account.tenantId};
 if(await check())return;
 await page.goto(base+'/login');await context.request.get(base+'/api/v1/auth/csrf');
 const script="$ErrorActionPreference='Stop'; $secure=(Get-Content -LiteralPath 'tmp/local-acceptance/credential.dpapi' -Raw).Trim() | ConvertTo-SecureString; $pointer=[IntPtr]::Zero; try { $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure); [Console]::Write([Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)) } finally { if($pointer -ne [IntPtr]::Zero){ [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) } }";
 let password=execFileSync('pwsh.exe',['-NoProfile','-NonInteractive','-Command',script],{encoding:'utf8',windowsHide:true,stdio:['ignore','pipe','pipe']});
 const loginId=Date.now();
 for(let attempt=1;attempt<=3;attempt++){
  const r=await context.request.get(base+'/api/v1/auth/captcha');if(!r.ok())throw new Error('CAPTCHA_UNAVAILABLE');
  const name=`login-${loginId}-${attempt}`;await fs.writeFile(path.join(out,name+'.png'),await r.body());console.log('CAPTCHA_READY',path.join(out,name+'.png'));
  let answer;for(let i=0;i<300;i++){try{answer=(await fs.readFile(path.join(out,name+'.txt'),'utf8')).trim();break}catch{await new Promise(r=>setTimeout(r,1000))}}
  if(!answer)throw new Error('Captcha timeout');
  const result=await page.evaluate(async data=>{const token=decodeURIComponent(document.cookie.split('; ').find(x=>x.startsWith('XSRF-TOKEN='))?.slice(11)||'');const r=await fetch('/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json','X-XSRF-TOKEN':token},body:JSON.stringify(data)});return {status:r.status,code:(await r.json()).error?.code}},{identifier:account.identifier,password,captcha:answer});
  if(result.status===200)break;if(result.code!=='CAPTCHA_INVALID')throw new Error('Login failed '+result.code);
 }
 password=undefined;if(!await check())throw new Error('Canonical account authentication failed');
}
