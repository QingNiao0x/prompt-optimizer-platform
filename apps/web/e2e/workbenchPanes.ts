import type { Page } from '@playwright/test';

const PANE_LABELS = {
  context: '项目上下文',
  intent: '写想法',
  result: '增强结果',
} as const;

export async function openWorkbenchPane(
  page: Page,
  pane: keyof typeof PANE_LABELS,
): Promise<void> {
  const isNarrowWorkbench = await page.evaluate(() =>
    window.matchMedia('(max-width: 900px)').matches);
  if (!isNarrowWorkbench) {
    return;
  }

  const tab = page.getByRole('tab', { name: PANE_LABELS[pane], exact: true });
  await tab.waitFor({ state: 'visible' });
  await tab.click();
}
