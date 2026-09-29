import type { PageResult } from '@/types/api'
import type { Performance, PerformanceStatus, SavePerformancePayload } from '@/types/performance'
import { get, post, put } from '@/utils/request'

/** 分页查询参数；后端 page 从 1 开始，pageSize 上限 100 */
export interface PageQuery {
  page?: number
  pageSize?: number
}

/** 公开演出列表；只含已发布内容，已开始/售罄仍会展示但不可售 */
export function fetchPublicPerformances(query: PageQuery = {}): Promise<PageResult<Performance>> {
  return get<PageResult<Performance>>('/performances', { ...query })
}

/** 公开演出详情；草稿与已下架对公开接口一律 404 */
export function fetchPublicPerformance(id: string): Promise<Performance> {
  return get<Performance>(`/performances/${id}`)
}

/** 本人演出列表，可按状态筛选 */
export function fetchOwnedPerformances(
  query: PageQuery & { status?: PerformanceStatus } = {},
): Promise<PageResult<Performance>> {
  return get<PageResult<Performance>>('/operator/performances', { ...query })
}

/** 本人演出详情；他人的演出返回 404 */
export function fetchOwnedPerformance(id: string): Promise<Performance> {
  return get<Performance>(`/operator/performances/${id}`)
}

/** 新建草稿；允许空对象，但不完整的草稿无法发布 */
export function createDraft(payload: SavePerformancePayload): Promise<Performance> {
  return post<Performance>('/operator/performances', payload)
}

/** 编辑演出：完整对象替换。已发布/已下架时关键字段必须原样带回 */
export function updatePerformance(
  id: string,
  payload: SavePerformancePayload,
): Promise<Performance> {
  return put<Performance>(`/operator/performances/${id}`, payload)
}

/** 发布草稿；不完整返回 409 PERFORMANCE_NOT_READY */
export function publishPerformance(id: string): Promise<Performance> {
  return post<Performance>(`/operator/performances/${id}/publish`)
}

/** 下架已发布演出；下架后不能重新上架 */
export function withdrawPerformance(id: string): Promise<Performance> {
  return post<Performance>(`/operator/performances/${id}/withdraw`)
}
