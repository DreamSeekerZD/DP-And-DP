import type { CurrentUser } from '@/types/api'
import { get, post } from '@/utils/request'

/** GET /auth/csrf 的返回：写请求要带的 header 名与 token 值 */
export interface CsrfPayload {
  headerName: string
  token: string
}

/** 取 CSRF token，匿名可访问。登录后必须重新调用，否则下一个写请求 403 */
export function fetchCsrf(): Promise<CsrfPayload> {
  return get<CsrfPayload>('/auth/csrf')
}

/** 登录。成功返回当前用户；账号或密码错误统一 401 INVALID_CREDENTIALS */
export function login(username: string, password: string): Promise<CurrentUser> {
  return post<CurrentUser>('/auth/login', { username, password })
}

/** 查询当前登录用户；匿名时后端返回 401，这是正常分支而不是错误 */
export function me(): Promise<CurrentUser> {
  return get<CurrentUser>('/auth/me')
}

/**
 * 退出登录。
 *
 * 返回值刻意不声明具体形状：后端当前返回 `data: true`，
 * 而契约文档写的是 `{loggedOut:true}`（两者不一致，已在执行报告中记录）。
 * 前端只按「HTTP 200 即退出成功」处理，不依赖 data 形状。
 */
export function logout(): Promise<unknown> {
  return post<unknown>('/auth/logout')
}
