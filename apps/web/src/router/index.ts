import { createRouter, createWebHistory } from 'vue-router';
import { useAuthStore } from '@/stores/auth';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/pages/LoginPage.vue'),
      meta: { title: '登录' },
    },
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
    {
      path: '/admin/models',
      name: 'admin-models',
      component: () => import('@/pages/AdminModelsPage.vue'),
      meta: { title: '平台模型管理', platformAdmin: true },
    },
  ],
});

router.beforeEach(async (to) => {
  if (to.name === 'home' || to.name === 'login') {
    return true;
  }
  const auth = useAuthStore();
  try {
    await auth.initialize();
  } catch {
    return { name: 'login', query: { unavailable: '1' } };
  }
  if (!auth.isAuthenticated) return { name: 'login' };
  if (to.meta.platformAdmin && !auth.user?.platformAdmin) return { name: 'workbench' };
  return true;
});

router.afterEach((to) => {
  const pageTitle = typeof to.meta.title === 'string' ? to.meta.title : '工作台';
  document.title = `${pageTitle} · Prompt Optimizer`;
});

export default router;
