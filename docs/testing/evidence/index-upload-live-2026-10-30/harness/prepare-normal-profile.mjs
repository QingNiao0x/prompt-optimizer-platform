import fs from 'node:fs/promises';
let source=await fs.readFile('tmp/index-upload-20261030/full-root-reuse.mjs','utf8');
source=source.replace("full-diagnostic-index","full-normal-profile");
source=source.replace("const browser=await chromium.connectOverCDP('http://127.0.0.1:9337');const context=browser.contexts()[0],page=context.pages()[0];",`const existing=await chromium.connectOverCDP('http://127.0.0.1:9337');
const context=await chromium.launchPersistentContext(path.join(root,'tmp/index-upload-20261030/normal-profile'),{channel:'chrome',headless:true,viewport:{width:1440,height:1000},locale:'zh-CN'});
await context.addCookies(await existing.contexts()[0].cookies());const page=context.pages()[0]||await context.newPage();`);
source=source.replace("await browser._connection?.close?.();","await context.close();process.exit(report.failure?1:0);");
source=source.replace("if(i%10===0){const metrics", "if(i%10===0){const usage=await page.evaluate(async()=>await navigator.storage.estimate());if(usage.usage>8*1024*1024*1024){await page.getByRole('button',{name:'暂停',exact:true}).click();throw new Error('Observed IndexedDB exceeds 8 GiB test storage cap despite configured 2 GiB estimated budget');}const metrics");
source=source.replace("heapBytes:metrics.metrics.find", "storage:usage,heapBytes:metrics.metrics.find");
await fs.writeFile('tmp/index-upload-20261030/full-normal-profile.mjs',source);
source=source.replace("'full-normal-profile'","'full-normal-external-profile'")
 .replace("path.join(root,'tmp/index-upload-20261030/normal-profile')","path.join(process.env.TEMP,'prompt-index-acceptance-'+Date.now())");
await fs.writeFile('tmp/index-upload-20261030/full-normal-external-profile.mjs',source);
