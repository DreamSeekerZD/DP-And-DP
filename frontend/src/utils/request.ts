import axios, { AxiosError, type AxiosInstance, type InternalAxiosRequestConfig } from 'axios'
import { ApiError, type Result } from '@/types/api'

/**
 * 统一请求封装。
 *
 * 职责边界（刻意划清，避免重复提示与循环依赖）：
 * - 只解后端统一信封、只归一化错误，**不弹任何提示**。提示由页面统一弹一次，
 *   否则同一个失败会被拦截器和页面各弹一遍。
 * - **不 import store**：CSRF 取值与「会话失效」回调由 auth store 注入，
 *   否则 request ←→ store 会形成循环引用。
 * - **不自动重放任何写请求**：超时或失败后一律交给用户手动重试，
 *   自动重发可能造成重复写入。
 */

/** 请求超时（毫秒）。共同契约候选 15 秒，不使用 HeXi 的超长超时 */
const TIMEOUT_MS = 15_000

/** 需要携带 CSRF header 的方法；GET / HEAD 属于安全方法，不需要 */
const WRITE_METHODS = new Set(['post', 'put', 'patch', 'delete'])

let csrfHeaderName = 'X-CSRF-TOKEN'
let csrfToken: string | null = null
let csrfProvider: (() => Promise<void>) | null = null
let unauthenticatedHandler: (() => void) | null = null

/** 写入一份新的 CSRF token（登录成功后必须重新写入） */
export function setCsrf(headerName: string, token: string): void {
  csrfHeaderName = headerName
  csrfToken = token
}

/** 清除本地 CSRF token；下一次写请求会重新获取 */
export function clearCsrf(): void {
  csrfToken = null
}

/**
 * 注入 CSRF 获取方式。
 * 写请求发出前若本地没有 token，会先 await 这个函数取一份，
 * 这样既保证每个写请求都带 token，又不会在失败后重放。
 */
export function setCsrfProvider(provider: () => Promise<void>): void {
  csrfProvider = provider
}

/** 注入「会话已失效」回调，由 auth store 清空身份 */
export function onUnauthenticated(handler: () => void): void {
  unauthenticatedHandler = handler
}

export const http: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: TIMEOUT_MS,
  // 同源经 Vite 代理，Session Cookie 由浏览器自动携带
  withCredentials: true,
})

http.interceptors.request.use(async (config: InternalAxiosRequestConfig) => {
  const method = (config.method ?? 'get').toLowerCase()
  if (!WRITE_METHODS.has(method)) return config

  // 本地没有 token 时先取一份：这是「发出前准备」，不是失败后的重放
  if (!csrfToken && csrfProvider) {
    await csrfProvider()
  }
  if (csrfToken) {
    config.headers.set(csrfHeaderName, csrfToken)
  }
  return config
})

/**
 * 把 axios 错误归一化成 ApiError，保留 HTTP 状态与业务码。
 *
 * 403 必须区分 CSRF 失效与角色权限不足：前者可重试，后者不该跳登录。
 * 401 只表示未登录或会话过期，交给注入的回调清身份，不在这里跳转。
 */
function normalizeError(error: AxiosError<Result<unknown>>): ApiError {
  const status = error.response?.status ?? 0
  const body = error.response?.data

  if (status === 0) {
    const timedOut = error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT'
    return new ApiError(
      0,
      'NETWORK_ERROR',
      timedOut
        ? '请求超时，结果待确认，请手动重试'
        : '无法连接后端，请确认后端已在 8080 启动',
    )
  }

  if (status === 401) {
    unauthenticatedHandler?.()
    return new ApiError(401, body?.code ?? 'UNAUTHENTICATED', body?.message ?? '未登录或会话已失效')
  }

  if (status === 403 && body?.code === 'CSRF_INVALID') {
    // 丢掉失效的 token，下一次**用户手动提交**时会自动重新获取。
    // 这里绝不重放刚失败的写请求。
    csrfToken = null
    return new ApiError(403, 'CSRF_INVALID', '安全令牌已失效，请再提交一次')
  }

  return new ApiError(status, body?.code ?? 'UNKNOWN', body?.message ?? `请求失败（HTTP ${status}）`)
}

async function unwrap<T>(request: Promise<{ data: Result<T> }>): Promise<T> {
  try {
    const response = await request
    const body = response.data
    if (body && body.code === 'OK') {
      return body.data as T
    }
    // HTTP 200 却不是 OK：按失败处理，不让页面拿到半成品
    throw new ApiError(200, body?.code ?? 'UNKNOWN', body?.message ?? '响应格式不正确')
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw normalizeError(error as AxiosError<Result<unknown>>)
  }
}

export function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  return unwrap<T>(http.get(url, { params }))
}

export function post<T>(url: string, body?: unknown): Promise<T> {
  return unwrap<T>(http.post(url, body))
}

export function put<T>(url: string, body?: unknown): Promise<T> {
  return unwrap<T>(http.put(url, body))
}
