import { describe, expect, it } from 'vitest';

import { modelCallModule, modelCallOperationLabel } from './modelCallPresentation';

describe('model call business attribution', () => {
  it.each([
    ['prompt.optimize', '提示词优化', '提示词优化'],
    ['plan.generate', '提示词优化', 'Plan 问题生成'],
    ['image.analyze', '素材转提示词', '素材转提示词'],
    ['image.plan', '素材转提示词', '素材 Plan 问题生成'],
    ['image.generate', '素材转提示词', '素材提示词生成'],
    ['text.polish', '文章祛AI味', '文章祛AI味'],
  ])('identifies %s without mixing prompt enhancement and independent tools', (operation, module, label) => {
    expect(modelCallModule(operation)).toBe(module);
    expect(modelCallOperationLabel(operation)).toBe(label);
  });

  it.each(['document.summary.map', 'document.summary.reduce', 'context.embedding', 'context.vision'])(
    'keeps shared %s processing separate from tool attribution', (operation) => {
      expect(modelCallModule(operation)).toBe('上下文处理');
      expect(modelCallOperationLabel(operation)).not.toBe('未知操作');
    },
  );

  it.each(['unknown', '', 'image.unrecognized', 'text.other', 'constructor', '__proto__'])(
    'does not infer a module or operation from unrecognized %s', (operation) => {
      expect(modelCallModule(operation)).toBe('未分类');
      expect(modelCallOperationLabel(operation)).toBe('未知操作');
    },
  );
});
