const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');

// 在读取生成结果前固定作品、对照条件及允许调用数。
const [root, metadataFile] = process.argv.slice(2);
const first = path.join(root, 'fresh-initial');
const read = file => JSON.parse(fs.readFileSync(file, 'utf8'));
const sha = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const save = (file, value) => fs.writeFileSync(path.join(first, file), JSON.stringify(value, null, 2) + '\n', {flag: 'wx'});
const samplesPath = path.join(root, 'revision-fresh-samples.json');
const outcomes = path.join(root, 'revision-pilot', 'ledger.json');
const scope = path.join(root, 'fresh-protocol.md');
const samples = read(samplesPath);
const metadata = read(metadataFile);
const modelId = 'deepseek:deepseek-flash';
assert.equal(samples.length, 8);
assert.equal(new Set(samples.map(item => item.id)).size, 8);
assert.equal(read(outcomes).actualCalls, 3);
assert.equal(metadata.defaultModel.modelId, modelId);
assert.equal(metadata.temperature, 0.2);
assert.equal(metadata.maxTokens, 8000);
assert.equal(metadata.jsonResponseFormat, true);
fs.mkdirSync(first);
const versions = {baseline: 'text-polish-v3', candidate: 'text-polish-paragraph-patch-v1'};
const jobs = samples.flatMap((item, i) => (i % 2 ? ['candidate', 'baseline'] : ['baseline', 'candidate'])
    .map(arm => ({id: item.id + '-' + arm, sample: item.id, phase: 'pilot', language: 'ZH',
        strategy: versions[arm], thinking: 'DISABLED', input: {
            sourceText: item.source, style: item.style, depth: 'LIGHT', audience: '',
            requirements: '保留标题和原段数。' + item.objective, lockedTexts: item.locked ?? [], modelId
        }
    })));
save('metadata.json', metadata);
save('authorization.json', {status: 'APPROVED', batchCeiling: 200, priorCalls: 45, maxInitialAttempts: 32, maxRevisionCalls: 8});
save('ledger.json', {actualCalls: 0, reserved: 0, closed: false, entries: []});
const files = [samplesPath, outcomes, scope, path.join(first, 'metadata.json'),
    'scripts/evaluation/text-polish-thinking/ThinkingComparison.java',
    'scripts/evaluation/text-polish-semantic/DirectedRevisionEvaluation.java',
    ...['OpenAiTextPolishProvider', 'TextPolishInstructions', 'TextPolishSkills', 'TextPolishParagraphPatch', 'TextPolishFidelity']
        .map(name => 'services/api/src/main/java/com/promptoptimizer/textpolish/service/' + name + '.java'),
    ...Object.values(versions).map(version => 'services/api/src/main/resources/textpolish/skills/' + version + '/SKILL.md')];
save('protocol.json', {maxActualCalls: 32, phaseCaps: {pilot: 32}, jobs,
    sourceSha256: Object.fromEntries(files.map(file => [file.split(path.sep).join('/'), sha(file)]))});
fs.writeFileSync(path.join(first, 'protocol.sha256'), sha(path.join(first, 'protocol.json')) + '\n', {flag: 'wx'});
