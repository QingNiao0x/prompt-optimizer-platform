import { describe, expect, it } from 'vitest';

import type { ContextSnapshot } from '@/types/api';

import { buildContextPresentation } from './contextPresentation';

const snapshot = (overrides: Partial<ContextSnapshot>): ContextSnapshot => ({
  customDescription: '',
  technologyStack: [],
  dependencies: [],
  directoryTree: [],
  fileSnippets: [],
  warnings: [],
  redactions: [],
  analysisVersion: 'v2',
  ...overrides,
});

describe('context presentation', () => {
  it('should build a project overview and module summaries for a code project', () => {
    const presentation = buildContextPresentation(snapshot({
      technologyStack: [
        { name: 'Java 21', source: 'backend/pom.xml', confidence: 0.98 },
        { name: 'Spring Boot 3', source: 'backend/pom.xml', confidence: 0.98 },
        { name: 'Vue 3', source: 'frontend/package.json', confidence: 0.98 },
        { name: 'TypeScript', source: 'frontend/package.json', confidence: 0.95 },
      ],
      dependencies: [
        { ecosystem: 'maven', name: 'spring-boot-starter-web', version: '3.3.13', source: 'backend/pom.xml' },
      ],
      directoryTree: ['backend/', 'backend/src/', 'frontend/', 'frontend/src/'],
      fileSnippets: [
        {
          path: 'README.md',
          language: 'markdown',
          content: '# Sample',
          summary: '这是一个用于上下文分析测试的前后端分离示例项目。',
          truncated: false,
        },
        {
          path: 'backend/src/main/java/com/example/controller/HelloController.java',
          language: 'java',
          content: 'class HelloController {}',
          summary: '提供示例问候接口。',
          truncated: false,
        },
        {
          path: 'frontend/src/views/App.vue',
          language: 'vue',
          content: '<template><main /></template>',
          summary: '展示示例项目首页。',
          truncated: false,
        },
      ],
    }));

    expect(presentation.mode).toBe('CODE_PROJECT');
    expect(presentation.overview).toContain('前后端分离示例项目');
    expect(presentation.overview).toContain('Java 21');
    expect(presentation.overview).toContain('Vue 3');
    expect(presentation.modules).toEqual(expect.arrayContaining([
      expect.objectContaining({ name: '后端服务', path: 'backend' }),
      expect.objectContaining({ name: '接口控制层' }),
      expect.objectContaining({ name: '前端应用', path: 'frontend' }),
      expect.objectContaining({ name: '页面模块' }),
    ]));
  });

  it('should keep ordinary documents in summary-only mode', () => {
    const presentation = buildContextPresentation(snapshot({
      directoryTree: ['docs/report.docx'],
      fileSnippets: [{
        path: 'docs/report.docx',
        language: 'docx',
        content: '季度经营报告正文',
        summary: '文档介绍本季度经营情况和下一阶段计划。',
        truncated: false,
      }],
    }));

    expect(presentation).toEqual({
      mode: 'DOCUMENT',
      modeLabel: '普通文档',
      overview: '',
      modules: [],
    });
  });

  it('should recognize source files as a code project even without stack tags', () => {
    const presentation = buildContextPresentation(snapshot({
      fileSnippets: [{
        path: 'src/main/java/com/example/OrderService.java#chunk-1',
        language: 'java',
        content: 'class OrderService {}',
        summary: '订单业务服务。',
        truncated: false,
      }],
    }));

    expect(presentation.mode).toBe('CODE_PROJECT');
    expect(presentation.modules).toEqual(expect.arrayContaining([
      expect.objectContaining({ name: '业务服务层' }),
    ]));
  });
});
