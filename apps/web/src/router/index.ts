import { createRouter, createWebHistory } from 'vue-router';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'workbench',
      component: () => import('@/pages/PromptWorkbenchPage.vue'),
      meta: { title: '提示词工作台' },
    },
    {
      path: '/history',
      name: 'history',
      component: () => import('@/pages/ComingSoonPage.vue'),
      props: {
        eyebrow: 'History',
        title: '历史记录将在下一阶段开放',
        description: '届时可以查看、删除、重新载入并比较每一次提示词增强结果。',
      },
      meta: { title: '历史记录' },
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('@/pages/ComingSoonPage.vue'),
      props: {
        eyebrow: 'Settings',
        title: '模型设置将在下一阶段开放',
        description: '模型供应商和 API Key 当前由服务端环境变量管理，前端不会接触明文密钥。',
      },
      meta: { title: '设置' },
    },
  ],
});

router.afterEach((to) => {
  const pageTitle = typeof to.meta.title === 'string' ? to.meta.title : '工作台';
  document.title = `${pageTitle} · Prompt Optimizer`;
});

export default router;
