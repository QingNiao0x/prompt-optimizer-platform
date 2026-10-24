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
    // 用户协议与隐私政策必须在注册前可读，因此保持公开访问，不受登录守卫限制。
    {
      path: '/terms',
      name: 'terms',
      component: () => import('@/pages/TermsPage.vue'),
      meta: { title: '用户协议' },
    },
    {
      path: '/privacy',
      name: 'privacy',
      component: () => import('@/pages/PrivacyPage.vue'),
      meta: { title: '隐私政策' },
    },
    {
      path: '/workbench',
      name: 'workbench',
      component: () => import('@/pages/PromptWorkbenchPage.vue'),
      meta: { title: '提示词工作台' },
    },
    {
      path: '/image-to-prompt',
      name: 'image-to-prompt',
      component: () => import('@/pages/ImageToPromptPage.vue'),
      meta: { title: '图像转提示词' },
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
    {
      path: '/admin/analytics',
      name: 'admin-analytics',
      component: () => import('@/pages/AdminAnalyticsPage.vue'),
      meta: { title: '平台统计与日志', platformAdmin: true },
    },
  ],
});

router.beforeEach(async (to) => {
  // terms 与 privacy 属于公开页面：注册前的用户需要先读到条款才能勾选同意。
  if (to.name === 'home' || to.name === 'login' || to.name === 'terms' || to.name === 'privacy') {
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
