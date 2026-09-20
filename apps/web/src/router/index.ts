import { createRouter, createWebHistory } from 'vue-router';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('@/pages/HomePage.vue'),
      meta: { title: '首页' },
    },
    {
      path: '/workbench',
      name: 'workbench',
      component: () => import('@/pages/PromptWorkbenchPage.vue'),
      meta: { title: '提示词工作台' },
    },
    {
      path: '/history',
      name: 'history',
      component: () => import('@/pages/HistoryPage.vue'),
      meta: { title: '历史记录' },
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('@/pages/SettingsPage.vue'),
      meta: { title: '设置' },
    },
  ],
});

router.afterEach((to) => {
  const pageTitle = typeof to.meta.title === 'string' ? to.meta.title : '工作台';
  document.title = `${pageTitle} · Prompt Optimizer`;
});

export default router;
