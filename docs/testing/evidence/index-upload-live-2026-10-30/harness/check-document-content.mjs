import fs from 'node:fs/promises';import path from 'node:path';import os from 'node:os';import {createHash} from 'node:crypto';
const out=path.resolve('tmp/index-upload-20261030',process.env.DOCUMENT_ACCEPTANCE_RUN||'document-live');
const report=JSON.parse(await fs.readFile(path.join(out,'report.json'),'utf8'));
const roots=(await fs.readdir(os.tmpdir(),{withFileTypes:true})).filter(e=>e.isDirectory()&&e.name.startsWith('prompt-optimizer-documents-')).map(e=>path.join(os.tmpdir(),e.name));
const result=[];
for(const f of report.files){
 let bytes;for(const root of roots){try{bytes=await fs.readFile(path.join(root,f.documentId,'chunks.data'));break}catch{}}
 if(!bytes){result.push({name:f.name,found:false});continue;}
 const text=bytes.toString('utf8');let expected=[];
 if(f.name.endsWith('.docx'))expected=Array.from({length:700},(_,i)=>`材料第${i}段`);
 if(f.name.endsWith('.xlsx'))expected=Array.from({length:2000},(_,i)=>`记录${i+1}`);
 if(f.name.endsWith('.pdf'))expected=Array.from({length:5},(_,i)=>Array.from({length:35},(_,j)=>`Synthetic procurement record ${i}-${j}; amount 12345.67`)).flat();
 if(f.name.endsWith('.txt')){const source=await fs.readFile(path.resolve('tmp/index-upload-20261030/fixtures',f.name),'utf8');expected=[source.slice(0,40),source.slice(-60)];}
 const missing=expected.filter(e=>!text.includes(e));result.push({name:f.name,found:true,indexBytes:bytes.length,indexSha256:createHash('sha256').update(bytes).digest('hex'),expectedUnits:expected.length,missingUnits:missing.length,missingSamples:missing.slice(0,10),headAndTail:f.returnedMarkers.map(x=>({marker:x.marker,present:text.includes(x.marker)}))});
}
await fs.writeFile(path.join(out,'independent-content-check.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
