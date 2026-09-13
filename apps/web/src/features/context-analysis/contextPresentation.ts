import type { ContextSnapshot, FileSnippet } from '@/types/api';

export type ContextDisplayMode = 'CODE_PROJECT' | 'DOCUMENT';

export interface ProjectModuleSummary {
  id: string;
  name: string;
  path: string;
  description: string;
  sourceFileCount: number;
}

export interface ContextPresentation {
  mode: ContextDisplayMode;
  modeLabel: string;
  overview: string;
  modules: ProjectModuleSummary[];
}

interface ModuleRole {
  name: string;
  description: string;
  priority: number;
}

interface MutableModuleSummary extends Omit<ProjectModuleSummary, 'id' | 'sourceFileCount'> {
  priority: number;
  sourceFiles: Set<string>;
}

const CODE_LANGUAGES = new Set([
  'c', 'cpp', 'csharp', 'go', 'html', 'java', 'javascript', 'kotlin', 'less',
  'php', 'python', 'razor', 'ruby', 'rust', 'sass', 'scss', 'sql', 'swift',
  'typescript', 'vue',
]);

const PROJECT_FILE_NAMES = new Set([
  'build.gradle', 'build.gradle.kts', 'cargo.toml', 'composer.json', 'go.mod',
  'package.json', 'pipfile', 'pom.xml', 'pyproject.toml', 'requirements.txt',
  'settings.gradle', 'settings.gradle.kts',
]);

const CODE_FILE_PATTERN = /\.(?:c|cc|cpp|cs|cshtml|css|go|h|hpp|html|java|js|jsx|kt|kts|less|php|py|pyi|pyx|razor|rb|rs|sass|scss|sql|swift|ts|tsx|vue)$/i;
const INDEX_CHUNK_SUFFIX = /#chunk-\d+$/i;

const MODULE_ROLES: Readonly<Record<string, ModuleRole>> = {
  controller: {
    name: '接口控制层',
    description: '接收外部请求、校验输入并组织接口响应。',
    priority: 20,
  },
  api: {
    name: '接口模块',
    description: '定义或实现对外 API、请求模型和响应契约。',
    priority: 21,
  },
  service: {
    name: '业务服务层',
    description: '编排业务流程并承载核心应用逻辑。',
    priority: 22,
  },
  application: {
    name: '应用层',
    description: '协调领域能力、用例流程和外部适配器。',
    priority: 23,
  },
  repository: {
    name: '数据访问层',
    description: '封装数据查询、持久化和存储访问。',
    priority: 24,
  },
  dao: {
    name: '数据访问层',
    description: '封装数据查询、持久化和存储访问。',
    priority: 24,
  },
  domain: {
    name: '领域模型',
    description: '描述核心业务概念、规则和值对象。',
    priority: 25,
  },
  entity: {
    name: '实体模型',
    description: '定义业务实体或持久化数据结构。',
    priority: 26,
  },
  model: {
    name: '模型层',
    description: '集中维护页面、接口或业务使用的数据模型。',
    priority: 27,
  },
  views: {
    name: '页面模块',
    description: '组织面向用户的页面内容和页面级交互。',
    priority: 30,
  },
  pages: {
    name: '页面模块',
    description: '组织面向用户的页面内容和页面级交互。',
    priority: 30,
  },
  components: {
    name: '界面组件',
    description: '提供复用的界面结构、展示和交互组件。',
    priority: 31,
  },
  stores: {
    name: '状态管理',
    description: '维护跨组件共享状态和业务操作。',
    priority: 32,
  },
  store: {
    name: '状态管理',
    description: '维护跨组件共享状态和业务操作。',
    priority: 32,
  },
  router: {
    name: '路由模块',
    description: '维护页面路由、导航和访问入口。',
    priority: 33,
  },
  routes: {
    name: '路由模块',
    description: '维护页面路由、导航和访问入口。',
    priority: 33,
  },
  config: {
    name: '配置模块',
    description: '维护应用运行参数、组件装配和环境配置。',
    priority: 34,
  },
  resources: {
    name: '资源与配置',
    description: '保存应用配置、模板和运行时资源。',
    priority: 35,
  },
  test: {
    name: '测试模块',
    description: '验证核心功能、接口行为和回归场景。',
    priority: 40,
  },
  tests: {
    name: '测试模块',
    description: '验证核心功能、接口行为和回归场景。',
    priority: 40,
  },
};

const normalizePath = (path: string): string =>
  path.replace(/\\/g, '/').replace(INDEX_CHUNK_SUFFIX, '').replace(/^\.\//, '');

const fileNameOf = (path: string): string => {
  const segments = normalizePath(path).split('/');
  return segments[segments.length - 1]?.toLowerCase() ?? '';
};

const isCodeSnippet = (snippet: FileSnippet): boolean => {
  const path = normalizePath(snippet.path);
  return CODE_LANGUAGES.has(snippet.language.toLowerCase())
    || CODE_FILE_PATTERN.test(path)
    || PROJECT_FILE_NAMES.has(fileNameOf(path));
};

const resolveDisplayMode = (snapshot: ContextSnapshot): ContextDisplayMode =>
  snapshot.technologyStack.length > 0 || snapshot.fileSnippets.some(isCodeSnippet)
    ? 'CODE_PROJECT'
    : 'DOCUMENT';

const rootModuleRole = (segments: string[]): ModuleRole | undefined => {
  const first = segments[0]?.toLowerCase();
  const second = segments[1]?.toLowerCase();
  if (first === 'backend' || first === 'server') {
    return {
      name: '后端服务',
      description: '承载服务启动、接口、业务处理、配置和数据访问相关代码。',
      priority: 1,
    };
  }
  if (first === 'frontend' || first === 'web' || first === 'client') {
    return {
      name: '前端应用',
      description: '承载页面、组件、状态管理和用户交互逻辑。',
      priority: 2,
    };
  }
  if (first === 'services' && second) {
    return {
      name: `${segments[1]} 服务`,
      description: '独立运行的后端服务或服务端能力模块。',
      priority: 3,
    };
  }
  if (first === 'apps' && second) {
    return {
      name: `${segments[1]} 应用`,
      description: '可独立运行的产品应用或客户端模块。',
      priority: 4,
    };
  }
  if (first === 'packages' && second) {
    return {
      name: `${segments[1]} 共享包`,
      description: '供多个应用复用的公共类型、组件或基础能力。',
      priority: 5,
    };
  }
  return undefined;
};

const rootModulePath = (segments: string[]): string => {
  const first = segments[0]?.toLowerCase();
  return first === 'apps' || first === 'services' || first === 'packages'
    ? segments.slice(0, 2).join('/')
    : segments[0] ?? '项目根目录';
};

const roleFromFileName = (path: string): ModuleRole | undefined => {
  const fileName = fileNameOf(path);
  if (/controller\.[^.]+$/i.test(fileName)) {
    return MODULE_ROLES.controller;
  }
  if (/service(?:impl)?\.[^.]+$/i.test(fileName)) {
    return MODULE_ROLES.service;
  }
  if (/(?:repository|dao)\.[^.]+$/i.test(fileName)) {
    return MODULE_ROLES.repository;
  }
  if (/(?:store|stores)\.[^.]+$/i.test(fileName)) {
    return MODULE_ROLES.store;
  }
  return undefined;
};

const addModule = (
  modules: Map<string, MutableModuleSummary>,
  path: string,
  role: ModuleRole,
  sourcePath: string,
): void => {
  const key = `${path.toLowerCase()}::${role.name}`;
  const current = modules.get(key);
  if (current) {
    current.sourceFiles.add(sourcePath);
    return;
  }
  modules.set(key, {
    name: role.name,
    path,
    description: role.description,
    priority: role.priority,
    sourceFiles: new Set([sourcePath]),
  });
};

const addFallbackRootModules = (
  modules: Map<string, MutableModuleSummary>,
  snapshot: ContextSnapshot,
): void => {
  const stackNames = snapshot.technologyStack.map((item) => item.name.toLowerCase());
  const hasBackend = stackNames.some((name) =>
    ['java', 'spring', 'python', 'go', 'rust', '.net', 'php', 'ruby'].some((term) => name.includes(term)));
  const hasFrontend = stackNames.some((name) =>
    ['vue', 'react', 'angular', 'svelte', 'vite'].some((term) => name.includes(term)));
  if (hasBackend) {
    addModule(modules, '项目根目录', {
      name: '后端服务',
      description: '根据构建文件和源码识别出的服务端实现。',
      priority: 10,
    }, '技术栈识别结果');
  }
  if (hasFrontend) {
    addModule(modules, '项目根目录', {
      name: '前端应用',
      description: '根据依赖清单和源码识别出的前端界面实现。',
      priority: 11,
    }, '技术栈识别结果');
  }
};

const buildProjectModules = (snapshot: ContextSnapshot): ProjectModuleSummary[] => {
  const modules = new Map<string, MutableModuleSummary>();
  for (const snippet of snapshot.fileSnippets.filter(isCodeSnippet)) {
    const sourcePath = normalizePath(snippet.path);
    const segments = sourcePath.split('/').filter(Boolean).slice(0, -1);
    const rootRole = rootModuleRole(segments);
    if (rootRole) {
      addModule(modules, rootModulePath(segments), rootRole, sourcePath);
    }
    const fileRole = roleFromFileName(sourcePath);
    if (fileRole) {
      addModule(modules, segments.join('/') || '项目根目录', fileRole, sourcePath);
    }
    segments.forEach((segment, index) => {
      const role = MODULE_ROLES[segment.toLowerCase()];
      if (role) {
        addModule(modules, segments.slice(0, index + 1).join('/'), role, sourcePath);
      }
    });
  }
  if (modules.size === 0) {
    addFallbackRootModules(modules, snapshot);
  }
  return Array.from(modules.entries())
    .map(([id, module]) => ({
      id,
      name: module.name,
      path: module.path,
      description: module.description,
      sourceFileCount: module.sourceFiles.size,
      priority: module.priority,
    }))
    .sort((left, right) => left.priority - right.priority || left.path.localeCompare(right.path))
    .slice(0, 12)
    .map(({ priority: _priority, ...module }) => module);
};

const hasPathSegment = (snapshot: ContextSnapshot, candidates: readonly string[]): boolean =>
  snapshot.fileSnippets.some((snippet) => {
    const segments = normalizePath(snippet.path).toLowerCase().split('/');
    return candidates.some((candidate) => segments.includes(candidate));
  });

const buildProjectOverview = (
  snapshot: ContextSnapshot,
  modules: ProjectModuleSummary[],
): string => {
  const readmeSummary = snapshot.fileSnippets.find((snippet) =>
    /^readme(?:\.[a-z0-9]+)?$/i.test(fileNameOf(snippet.path)))?.summary.trim();
  const hasFrontend = hasPathSegment(snapshot, ['frontend', 'web', 'client'])
    || snapshot.technologyStack.some((item) => /vue|react|angular|svelte/i.test(item.name));
  const hasBackend = hasPathSegment(snapshot, ['backend', 'server', 'services'])
    || snapshot.technologyStack.some((item) => /java|spring|python|go|rust|\.net|php|ruby/i.test(item.name));
  const architecture = hasFrontend && hasBackend ? '前后端分离' : '软件';
  const stackNames = snapshot.technologyStack.slice(0, 10).map((item) => item.name);
  const sentences: string[] = [];
  if (readmeSummary) {
    sentences.push(readmeSummary);
  }
  sentences.push(
    stackNames.length > 0
      ? `根据文件路径、构建清单和源码证据，当前识别为${architecture}项目，主要技术栈包括${stackNames.join('、')}。`
      : `根据已分析的源码文件，当前识别为${architecture}项目。`,
  );
  if (modules.length > 0) {
    const moduleNames = Array.from(new Set(modules.map((module) => module.name))).slice(0, 6);
    sentences.push(`可见模块主要包括${moduleNames.join('、')}。`);
  }
  sentences.push(
    `本次上下文包含 ${snapshot.fileSnippets.length} 个文件摘要、`
      + `${snapshot.dependencies.length} 项依赖和 ${snapshot.directoryTree.length} 个目录节点。`,
  );
  return sentences.join(' ');
};

/**
 * 将后端的通用上下文快照转换成适合当前文件类型的展示模型。
 * 这里只使用已经脱敏的分析结果，不会重新读取本地文件或扩大上传范围。
 */
export const buildContextPresentation = (snapshot: ContextSnapshot): ContextPresentation => {
  const mode = resolveDisplayMode(snapshot);
  if (mode === 'DOCUMENT') {
    return {
      mode,
      modeLabel: '普通文档',
      overview: '',
      modules: [],
    };
  }
  const modules = buildProjectModules(snapshot);
  return {
    mode,
    modeLabel: '代码项目',
    overview: buildProjectOverview(snapshot, modules),
    modules,
  };
};
