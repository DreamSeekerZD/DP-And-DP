/**
 * 后端统一响应信封。成功 code 固定为 OK，失败 data 固定为 null。
 * 失败使用真实 HTTP 状态码，不把错误包成 HTTP 200。
 */
export interface Result<T> {
  code: string
  message: string
  data: T | null
}

/**
 * 分页结果。
 *
 * total 在后端是原始 long（不是包装 Long），所以 JSON 里是**数字**而不是字符串；
 * 只有 id / publisherId 这类 BIGINT 编号才是字符串。
 */
export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  pageSize: number
}

/** 当前登录用户；id 是十进制字符串，不得转 Number */
export interface CurrentUser {
  id: string
  username: string
  role: UserRole
}

export type UserRole = 'USER' | 'OPERATOR'

/**
 * 归一化后的接口错误，保留 HTTP 状态与稳定业务码，
 * 让页面能区分「未登录 / 无权限 / CSRF 失效 / 校验失败 / 资源不可见」。
 */
export class ApiError extends Error {
  /** HTTP 状态码；网络错误或超时为 0 */
  readonly status: number

  /** 后端稳定英文业务码；网络错误时为本地码 */
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }

  /** 资源不存在或对当前身份不可见 */
  get isNotFound(): boolean {
    return this.status === 404
  }

  /** 未登录或会话已失效 */
  get isUnauthenticated(): boolean {
    return this.status === 401
  }
}
