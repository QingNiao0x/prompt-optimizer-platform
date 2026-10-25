import {createRequire} from 'node:module';
import {writeFile} from 'node:fs/promises';
const require=createRequire(new URL('../../apps/web/package.json',import.meta.url));
const {chromium,expect}=require('@playwright/test');
const browser=await chromium.connectOverCDP('http://127.0.0.1:9355');
const context=browser.contexts()[0],page=context.pages().find(p=>p.url().includes(':5185/'));
await page.bringToFront();
await context.grantPermissions(['clipboard-read','clipboard-write'],{origin:'http://127.0.0.1:5185'});
const targetSession=await context.newCDPSession(page);
const {targetInfo}=await targetSession.send('Target.getTargetInfo');
const browserSession=await browser.newBrowserCDPSession();
await browserSession.send('Browser.setPermission',{permission:{name:'clipboard-read'},setting:'granted',origin:'http://127.0.0.1:5185',browserContextId:targetInfo.browserContextId});
await expect(page.getByLabel('润色正文',{exact:true})).toBeVisible();
const visible=await page.getByLabel('润色正文',{exact:true}).innerText();
await page.getByRole('button',{name:'复制正文',exact:true}).click();
const result=await page.evaluate(async visible=>{
 const permissions=await Promise.all(['clipboard-read','clipboard-write'].map(async name=>({name,state:(await navigator.permissions.query({name})).state})));
 try {const text=await navigator.clipboard.readText();return {permissions,read:true,exact:text===visible,normalized:text.replace(/\r\n/g,'\n')===visible.replace(/\r\n/g,'\n'),visibleLength:visible.length,clipboardLength:text.length};}
 catch(error){return {permissions,read:false,errorName:error.name,errorMessage:error.message};}
},visible);
await writeFile('docs/testing/evidence/text-polish-2026-10-25/clipboard.json',JSON.stringify({verifiedAt:new Date().toISOString(),modelCalls:0,...result},null,2));
console.log(JSON.stringify(result));await browser.close();
