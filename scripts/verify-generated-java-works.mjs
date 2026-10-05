/**
 * 编译并运行合成 Java 作品的独立验收断言，不修改模型原作品，不把编译通过当成功能通过。
 * 只允许本脚本内两种纯内存工具题；拒绝进程、文件、网络和反射入口，子进程不继承模型密钥。
 */
import { readFile, writeFile, mkdir, realpath } from 'node:fs/promises';
import { resolve, relative, join } from 'node:path';
import { createHash, randomUUID } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { safeMaterialPath } from './prompt-output-evaluation.mjs';

const execute = promisify(execFile);
const root = resolve(import.meta.dirname, '..');
const hash = value => createHash('sha256').update(value).digest('hex');
const args = process.argv.slice(2);
const option = name => args[args.indexOf(name) + 1];
if (args.includes('--help')) {
  console.log('node scripts/verify-generated-java-works.mjs --run <frozen real output run> --output <new tmp json>');
  process.exit(0);
}
if (!args.includes('--run') || !args.includes('--output')) throw new Error('RUN_AND_OUTPUT_REQUIRED');
const run = await realpath(resolve(option('--run')));
const output = resolve(option('--output'));
for (const path of [run, output]) {
  const rel = relative(root, path); safeMaterialPath(rel);
  if (!/^tmp[/\\]/.test(rel)) throw new Error('WORKSPACE_TMP_REQUIRED');
}
const parent = await realpath(resolve(output, '..'));
if (parent !== resolve(output, '..')) throw new Error('OUTPUT_LINK_ESCAPE');
const manifest = JSON.parse(await readFile(join(run, 'manifest.json'), 'utf8'));
const jobsText = await readFile(join(run, 'jobs.json'), 'utf8');
if (hash(jobsText) !== manifest.jobsSha256) throw new Error('FROZEN_JOBS_CHANGED');
const batch = JSON.parse(jobsText);
const sandbox = join(root, 'tmp', 'generated-java-checks', new Date().toISOString().replace(/[:.]/g, '-') + '-' + randomUUID().slice(0, 8));
await mkdir(sandbox, { recursive: true });
const taskEnvironment = {};
for (const name of ['PATH', 'SystemRoot', 'WINDIR', 'TEMP', 'TMP', 'JAVA_HOME']) {
  if (process.env[name]) taskEnvironment[name] = process.env[name];
}
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', 'java.exe') : 'java';
const javac = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', 'javac.exe') : 'javac';
const mergeHarness = `import java.util.*;
public final class Harness {
  private static int checked=0, failures=0; private static String first="";
  private static final Set<String> ALLOWED=Set.of("name","age","enabled","region");
  private static void check(Map<String,Object> current, Map<String,Object> candidate, boolean confirmed) {
    checked++;
    Map<String,Object> c=current==null?new HashMap<>():new HashMap<>(current);
    Map<String,Object> a=candidate==null?new HashMap<>():new HashMap<>(candidate);
    Map<String,Object> expected=new HashMap<>(c);
    if(confirmed) for(String key:ALLOWED) {
      Object old=c.get(key), next=a.get(key);
      if((old==null || "".equals(old)) && next!=null && !"".equals(next)) expected.put(key,next);
    }
    try {
      Map<String,Object> got=FormMerge.merge(current,candidate,confirmed);
      if(got==null || got==current || got==candidate || !got.equals(expected)) throw new AssertionError("wrong values or alias");
      if(current!=null && !current.equals(c) || candidate!=null && !candidate.equals(a)) throw new AssertionError("input mutated");
      for(String key:expected.keySet()) if(got.get(key)!=expected.get(key)) throw new AssertionError("not shallow copy");
      got.put("__harness", "modifiable"); got.remove("__harness");
    } catch(Throwable failure) { failures++; if(first.isEmpty()) first="case "+checked+": "+failure.getClass().getSimpleName(); }
  }
  public static void main(String[] ignored) {
    Object absent=new Object(); Object[] values={absent,null,"",0,false,"  ","x",1};
    for(String key:ALLOWED) for(Object current:values) for(Object candidate:values) for(boolean confirmed:new boolean[]{false,true}) {
      Map<String,Object> c=new HashMap<>(), a=new HashMap<>();
      c.put("keepUnknown", "original"); a.put("unexpected", "must not add");
      if(current!=absent)c.put(key,current); if(candidate!=absent)a.put(key,candidate); check(c,a,confirmed);
    }
    for(boolean confirmed:new boolean[]{false,true}) {
      check(null,null,confirmed); check(null,Map.of("age",0,"enabled",false,"region","  "),confirmed);
      check(Map.of("name","kept"),null,confirmed);
      Map<String,Object> nested=new HashMap<>(); nested.put("name",new ArrayList<>(List.of("nested"))); check(nested,Map.of(),confirmed);
      Map<String,Object> same=new HashMap<>(); same.put("name",null); check(same,same,confirmed);
    }
    System.out.println("{\\"checked\\":"+checked+",\\"failures\\":"+failures+",\\"firstFailure\\":\\""+first+"\\"}");
  }
}`;
const decisionHarness = `public final class Harness {
  public static void main(String[] ignored) {
    int checked=0, failures=0; String first="";
    String[] businesses={null,"","approval","refund"," APPROVAL","APPROVAL ","OTHER","APPROVAL","REFUND"};
    int[] amounts={-1,0,500,999,1000,1001,5001}; int[] days={-1,0,3,7,8};
    for(String business:businesses) for(int amount:amounts) for(int day:days) for(boolean paid:new boolean[]{false,true}) for(boolean restricted:new boolean[]{false,true}) {
      checked++; boolean invalid=amount<0 || day<0 || !("APPROVAL".equals(business)||"REFUND".equals(business));
      String expected=invalid?null:("APPROVAL".equals(business)?(restricted?"DENY":(amount<=1000?"AUTO":"MANUAL")):(!paid||day>7?"DENY":(amount<=1000?"AUTO":"MANUAL")));
      try {
        String got=DecisionRules.decide(business,amount,day,paid,restricted);
        if(invalid || !expected.equals(got)) {failures++; if(first.isEmpty()) first=String.valueOf(business)+",amount="+amount+",days="+day+",paid="+paid+",restricted="+restricted+",expected="+expected+",got="+got;}
      } catch(IllegalArgumentException correct) {if(!invalid){failures++; if(first.isEmpty())first="unexpected IllegalArgumentException";}}
        catch(Throwable wrong) {failures++; if(first.isEmpty())first="unexpected "+wrong.getClass().getSimpleName();}
    }
    System.out.println("{\\"checked\\":"+checked+",\\"failures\\":"+failures+",\\"firstFailure\\":\\""+first+"\\"}");
  }
}`;
const entries = [];
for (const job of batch.jobs.filter(item => ['DEV-MERGE-M', 'DEV-DECISION-L'].includes(item.caseId))) {
  const result = JSON.parse(await readFile(join(run, 'execution', job.id + '.json'), 'utf8'));
  if (result.status !== 'SUCCESS') { entries.push({ jobId: job.id, status: 'GENERATION_FAILED' }); continue; }
  if (result.executionUserSha256 !== job.userHash || hash(result.output) !== result.outputSha256) throw new Error('ACTUAL_WORK_CHANGED');
  const blocks = [...result.output.matchAll(/```java\s*\r?\n([\s\S]*?)\r?\n```/g)];
  const name = job.caseId === 'DEV-MERGE-M' ? 'FormMerge' : 'DecisionRules';
  const base = { jobId: job.id, caseId: job.caseId, arm: job.arm, modelId: job.modelId, outputSha256: result.outputSha256 };
  if (blocks.length !== 1 || result.output.replace(blocks[0]?.[0] ?? '', '').trim()) {
    entries.push({ ...base, status: 'OUTPUT_FORMAT_FAILED' }); continue;
  }
  const code = blocks[0][1];
  // 限定合成工具题的执行面，不是通用沙箱。含未准许入口的作品仅记录、不执行。
  const imports = [...code.matchAll(/\bimport\s+(?:static\s+)?([^;]+);/g)].map(item => item[1].trim());
  const unsafe = /\b(?:System|Runtime|ProcessBuilder|Thread|ClassLoader|Files|Path|File|Socket|ServerSocket|URL|Unsafe|native|while)\b|\bClass\s*[.<(]|\bstatic\s*\{|\\u[0-9a-fA-F]{4}|\bpackage\s/.test(code);
  if (code.length > 40_000 || unsafe || imports.some(item => !/^java\.util\.(?:\*|Map|Set|List|HashMap|LinkedHashMap|Objects|Arrays|Collections|ArrayList)$/.test(item))
      || !new RegExp('public\\s+final\\s+class\\s+' + name + '\\b').test(code)) {
    entries.push({ ...base, status: 'NOT_EXECUTED_UNSAFE_OR_UNSUPPORTED' }); continue;
  }
  const directory = join(sandbox, job.id); await mkdir(directory);
  await writeFile(join(directory, name + '.java'), code, { flag: 'wx' });
  await writeFile(join(directory, 'Harness.java'), name === 'FormMerge' ? mergeHarness : decisionHarness, { flag: 'wx' });
  try {
    await execute(javac, ['--release', '21', name + '.java', 'Harness.java'], { cwd: directory, env: taskEnvironment, timeout: 15_000, maxBuffer: 65_536, windowsHide: true });
  } catch (error) {
    entries.push({ ...base, status: 'COMPILE_FAILED', reason: String(error.stderr ?? error.message).slice(0, 2000) }); continue;
  }
  try {
    const { stdout } = await execute(java, ['-Xmx64m', '-XX:ActiveProcessorCount=1', '-cp', '.', 'Harness'], { cwd: directory, env: taskEnvironment, timeout: 5000, maxBuffer: 65_536, windowsHide: true });
    const check = JSON.parse(stdout.trim());
    if (!Number.isInteger(check.checked) || !Number.isInteger(check.failures)) throw new Error('INVALID_HARNESS_OUTPUT');
    entries.push({ ...base, status: check.failures === 0 ? 'PASS' : 'FUNCTIONAL_FAILED', ...check });
  } catch (error) { entries.push({ ...base, status: 'EXECUTION_FAILED', reason: String(error.stderr ?? error.message).slice(0, 1000) }); }
}
const summary = { checkedWorks: entries.length, passed: entries.filter(item => item.status === 'PASS').length,
  failed: entries.filter(item => item.status !== 'PASS').length, inputCombinations: entries.reduce((n, item) => n + (item.checked ?? 0), 0) };
await writeFile(output, JSON.stringify({ schemaVersion: 1, sourceRun: relative(root, run), checkedAt: new Date().toISOString(),
  mechanism: 'COMPILED_SYNTHETIC_JAVA_ORACLE', sandbox: relative(root, sandbox), summary, entries }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ output, ...summary, failures: entries.filter(item => item.status !== 'PASS').map(item => ({ jobId: item.jobId, status: item.status, firstFailure: item.firstFailure })) }));
