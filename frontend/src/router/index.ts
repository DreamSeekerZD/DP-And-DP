import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/**
 * 路由与入口检查。
 *
 * 守卫只负责**体验**：决定显示哪个页面。真实权限始终由后端裁决，
 * 页面隐藏入口不等于授权，绕过守卫直接调接口一样会被 401/403/404 拒绝。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: { name: 'performance-list' } },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { anonymousOnly: true },
    },
    {
      path: '/performance',
      name: 'performance-list',
      component: () => import('@/views/performance/PerformanceListView.vue'),
    },
    {
      path: '/performance/:id',
      name: 'performance-detail',
      component: () => import('@/views/performance/PerformanceDetailView.vue'),
    },
    {
      path: '/orders',
      name: 'my-orders',
      component: () => import('@/views/order/MyOrdersView.vue'),
      meta: { requiresUser: true },
    },
    {
      path: '/orders/:id',
      name: 'order-detail',
      component: () => import('@/views/order/OrderDetailView.vue'),
      meta: { requiresUser: true },
    },
    {
      path: '/operator/performances',
      name: 'my-performances',
      component: () => import('@/views/operator/MyPerformancesView.vue'),
      meta: { requiresOperator: true },
    },
    {
      path: '/operator/performances/new',
      name: 'performance-create',
      component: () => import('@/views/operator/PerformanceEditView.vue'),
      meta: { requiresOperator: true },
    },
    {
      path: '/operator/performances/:id/edit',
      name: 'performance-edit',
      component: () => import('@/views/operator/PerformanceEditView.vue'),
      meta: { requiresOperator: true },
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue'),
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  // 首次导航先恢复身份，避免刷新页面后误判为匿名。
  // /auth/me 的 401 表示匿名，是正常分支，不弹窗也不循环跳转。
  await auth.ensureInitialized()

  if (to.meta.requiresOperator && !auth.isOperator) {
    if (!auth.isLoggedIn) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
    // 已登录但不是运营：不展示运营页面，也不暗示这类页面存在
    return { name: 'not-found' }
  }

  if (to.meta.requiresUser && !auth.isUser) {
    if (!auth.isLoggedIn) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
    // 已登录但不是普通用户（即运营账号）：不展示订单页面
    return { name: 'not-found' }
  }

  if (to.meta.anonymousOnly && auth.isLoggedIn) {
    return { name: 'performance-list' }
  }

  return true
})

export default router
