import { createRequire } from 'node:module';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
const require=createRequire(resolve('apps/web/package.json'));
const browser=await require('@playwright/test').chromium.connectOverCDP('http://127.0.0.1:9325');
const page=await browser.contexts()[0].newPage();
try {
  await page.route('**/api/v1/optimizations**',route=>route.abort('blockedbyclient'));
  await page.goto('http://127.0.0.1:5173/workbench',{waitUntil:'domcontentloaded'});
  await page.locator('.workbench-grid').waitFor({state:'visible',timeout:30000});
  const result=JSON.parse(await readFile('docs/testing/evidence/me00a-candidate17-2026-10-16/trace254-assembled.json','utf8'));
  await page.evaluate(async result=>{const {useOptimizationStore}=await import('/src/stores/optimization.ts');useOptimizationStore().applyReoptimized({result});},result);
  console.log(JSON.stringify(await page.evaluate(()=>({buttons:[...document.querySelectorAll('button')].map(b=>({text:b.textContent.trim(),aria:b.getAttribute('aria-label'),title:b.getAttribute('title'),class:b.className})),textareas:[...document.querySelectorAll('textarea')].map(t=>({class:t.className,placeholder:t.placeholder}))}))));
} finally {await page.close();await browser.close();}
