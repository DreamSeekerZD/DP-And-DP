import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as authApi from '@/api/auth'
import type { CurrentUser } from '@/types/api'
import { clearCsrf, onUnauthenticated, setCsrf, setCsrfProvider } from '@/utils/request'

/**
 * 身份状态。
 *
 * 只保存当前用户这类**非凭据**状态：Session 在 HttpOnly Cookie 里，
 * CSRF token 只在 utils/request 的内存变量中，两者都不落 localStorage。
 * 刷新页面靠 /auth/me 恢复身份，后端始终是权限的唯一裁决者。
 */
export const useAuthStore = defineStore('auth', () => {
  const user = ref<CurrentUser | null>(null)
  const initialized = ref(false)

  const isLoggedIn = computed(() => user.value !== null)
  const isOperator = computed(() => user.value?.role === 'OPERATOR')

  /** 取一份新的 CSRF token；写请求发出前若本地没有会自动调用到这里 */
  async function refreshCsrf(): Promise<void> {
    const payload = await authApi.fetchCsrf()
    setCsrf(payload.headerName, payload.token)
  }

  /**
   * 清空本地身份。
   * 只清状态，不跳转、不弹窗 —— 跳转交给路由守卫，提示交给页面，
   * 避免 401 时反复弹错或循环跳转。
   */
  function forgetUser(): void {
    user.value = null
    clearCsrf()
  }

  // 注入到 request 模块，避免 request ←→ store 循环引用
  setCsrfProvider(refreshCsrf)
  onUnauthenticated(forgetUser)

  /** 首次进入应用时恢复身份。401 只表示「当前是匿名」，属正常分支 */
  async function ensureInitialized(): Promise<void> {
    if (initialized.value) return
    try {
      user.value = await authApi.me()
    } catch {
      // 未登录、会话过期、后端不可用都按匿名处理；真正的错误由各页面自己提示
      user.value = null
    } finally {
      initialized.value = true
    }
  }

  /**
   * 登录。
   * 登录本身是写请求，缺少 CSRF 时请求拦截器会自动先取一份。
   */
  async function login(username: string, password: string): Promise<void> {
    user.value = await authApi.login(username, password)
    // 登录成功时框架会销毁旧 CSRF token，必须重新获取，
    // 否则登录后的第一个写请求会得到 403 CSRF_INVALID。
    await refreshCsrf()
  }

  /** 退出。无论后端是否报错，前端都先清身份，不残留任何凭据 */
  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } finally {
      forgetUser()
    }
  }

  return {
    user,
    initialized,
    isLoggedIn,
    isOperator,
    ensureInitialized,
    login,
    logout,
    refreshCsrf,
    forgetUser,
  }
})
