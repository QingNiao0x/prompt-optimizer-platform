import { createRequire } from 'node:module';
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';

// Read the two new histories through normal authenticated APIs; never create an identity or alter a work.
const root = resolve('tmp/me00a-20261012/candidate12-real');
const account = JSON.parse(await readFile(resolve('tmp/local-acceptance/account.json'), 'utf8'));
const require = createRequire(resolve('apps/web/package.json'));
const browser = await require('@playwright/test').chromium.connectOverCDP('http://127.0.0.1:9325');
try {
  const page = browser.contexts()[0].pages().find(item => item.url().startsWith('http://127.0.0.1:9001/'));
  if (!page) throw new Error('Canonical fixture browser unavailable');
  const get = path => page.evaluate(async path => {
    const response = await fetch(path);
    return { status: response.status, body: await response.json() };
  }, path);
  const me = await get('/api/v1/auth/me');
  if (me.status !== 200 || me.body.data.userId !== account.userId || me.body.data.tenantId !== account.tenantId)
    throw new Error('Canonical account scope mismatch');
  const list = await get('/api/v1/optimization-history?current=1&size=20');
  if (list.status !== 200) throw new Error('Normal scoped history list unavailable');
  const checks = {};
  for (const arm of ['plan', 'direct']) {
    const saved = JSON.parse(await readFile(resolve(root, arm, 'history.json'), 'utf8'));
    const input = JSON.parse(await readFile(resolve(root, arm, 'input.json'), 'utf8'));
    const copy = await readFile(resolve(root, arm, 'copied.md'), 'utf8');
    const detail = await get(`/api/v1/optimization-history/${saved.body.data.id}`);
    if (detail.status !== 200 || detail.body.data.rawPrompt !== input.rawPrompt || detail.body.data.optimizedPrompt !== copy)
      throw new Error(`Saved history or copied body changed: ${arm}`);
    checks[arm] = { copiedEqualsPersistedHistory: true, rawPromptUnchanged: true,
      copySha256: createHash('sha256').update(copy).digest('hex') };
  }
  const report = { verifiedAt: new Date().toISOString(), canonicalFixture: true, normalAuthenticatedRead: true,
    accountHash: createHash('sha256').update(account.userId).digest('hex'), newAccountsCreated: 0,
    newOptimizationHistories: 2, currentVisibleHistoryTotal: list.body.data.total, checks,
    scope: 'Normal local API and database history; not the complete Vite copy/edit/undo user flow' };
  await writeFile(resolve(root, 'live-history-verification.json'), JSON.stringify(report, null, 2), { flag: 'wx' });
  console.log(JSON.stringify({ sameAccount: true, historyTotal: report.currentVisibleHistoryTotal, twoCopiesMatch: true }));
} finally { await browser.close(); }
