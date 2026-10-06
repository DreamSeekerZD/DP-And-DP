<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import {
  fetchOwnedPerformances,
  publishPerformance,
  withdrawPerformance,
} from '@/api/performance'
import {
  PERFORMANCE_STATUS_LABEL,
  type Performance,
  type PerformanceStatus,
} from '@/types/performance'
import { centToYuan } from '@/utils/money'
import { formatShanghai } from '@/utils/time'

const items = ref<Performance[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 10
const statusFilter = ref<PerformanceStatus | ''>('')

const loading = ref(false)
const loadError = ref('')

/** 正在提交的演出 id：用于禁用该行的按钮，避免重复提交 */
const busyId = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    const result = await fetchOwnedPerformances({
      page: page.value,
      pageSize,
      // 空字符串表示「全部」，不能把空串当作状态传给后端（会被判为未知状态 400）
      status: statusFilter.value === '' ? undefined : statusFilter.value,
    })
    items.value = result.items
    total.value = result.total
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '加载失败'
    items.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function changeFilter(): void {
  page.value = 1
  void load()
}

function changePage(next: number): void {
  page.value = next
  void load()
}

/** 发布：先确认，再请求；请求期间按钮禁用，不做自动重试 */
async function handlePublish(item: Performance): Promise<void> {
  if (busyId.value) return
  try {
    await ElMessageBox.confirm(
      `发布后「${item.title ?? '未命名演出'}」的标题、地点、开始时间、价格与总库存将被锁定，不能再修改。确认发布？`,
      '确认发布',
      { confirmButtonText: '发布', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }

  busyId.value = item.id
  try {
    await publishPerformance(item.id)
    ElMessage.success('已发布')
    // 成功后从后端重新读取，不用本地拼装的状态冒充结果
    await load()
  } catch (error) {
    // 发布不完整会返回 409 PERFORMANCE_NOT_READY，原样展示后端说明
    ElMessage.error(error instanceof Error ? error.message : '发布失败')
  } finally {
    busyId.value = null
  }
}

/** 下架：先确认，再请求；下架后不能重新上架 */
async function handleWithdraw(item: Performance): Promise<void> {
  if (busyId.value) return
  try {
    await ElMessageBox.confirm(
      `下架后「${item.title ?? '未命名演出'}」不再出现在公开列表，且不能重新上架。已有订单不受影响。确认下架？`,
      '确认下架',
      { confirmButtonText: '下架', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }

  busyId.value = item.id
  try {
    await withdrawPerformance(item.id)
    ElMessage.success('已下架')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '下架失败')
  } finally {
    busyId.value = null
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page__head">
      <div>
        <p class="eyebrow">运营</p>
        <h1 class="page__title">我的发布</h1>
      </div>
      <RouterLink :to="{ name: 'performance-create' }">
        <el-button type="primary">新建演出</el-button>
      </RouterLink>
    </div>

    <div class="toolbar">
      <el-radio-group v-model="statusFilter" :disabled="loading" @change="changeFilter">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="DRAFT">草稿</el-radio-button>
        <el-radio-button value="PUBLISHED">已发布</el-radio-button>
        <el-radio-button value="WITHDRAWN">已下架</el-radio-button>
      </el-radio-group>
    </div>

    <el-alert
      v-if="loadError"
      class="notice"
      type="error"
      :title="loadError"
      :closable="false"
      show-icon
    >
      <template #default>
        <el-button size="small" :loading="loading" @click="load">重新加载</el-button>
      </template>
    </el-alert>

    <el-skeleton v-if="loading && items.length === 0" :rows="4" animated />

    <el-empty
      v-else-if="!loading && items.length === 0 && !loadError"
      description="还没有演出"
    >
      <RouterLink :to="{ name: 'performance-create' }">
        <el-button type="primary">新建第一场演出</el-button>
      </RouterLink>
    </el-empty>

    <ul v-else class="list">
      <li v-for="item in items" :key="item.id" class="list__row perforation">
        <div class="list__main">
          <div class="list__title">
            <span>{{ item.title ?? '未命名演出' }}</span>
            <el-tag size="small" :type="item.status === 'PUBLISHED' ? 'success' : 'info'">
              {{ PERFORMANCE_STATUS_LABEL[item.status] }}
            </el-tag>
          </div>

          <dl class="facts">
            <div class="facts__item">
              <dt>开始时间</dt>
              <dd class="num">
                {{ formatShanghai(item.startsAt) || '未填写' }}
                <span class="faint">（北京时间）</span>
              </dd>
            </div>
            <div class="facts__item">
              <dt>地点</dt>
              <dd>{{ item.venue ?? '未填写' }}</dd>
            </div>
            <div class="facts__item">
              <dt>票价</dt>
              <dd>
                <span v-if="item.priceCent === null" class="faint">未填写</span>
                <span v-else class="price">
                  <span class="price__symbol">¥</span>
                  <span class="num">{{ centToYuan(item.priceCent) }}</span>
                </span>
              </dd>
            </div>
            <div class="facts__item">
              <dt>库存</dt>
              <dd class="num">
                <template v-if="item.totalStock === null">
                  <span class="faint">未填写</span>
                </template>
                <template v-else>
                  {{ item.availableStock ?? 0 }} / {{ item.totalStock }}
                </template>
              </dd>
            </div>
          </dl>
        </div>

        <div class="list__actions">
          <RouterLink
            :to="{ name: 'performance-edit', params: { id: item.id } }"
          >
            <el-button size="small">编辑</el-button>
          </RouterLink>
          <RouterLink
            :to="{ name: 'performance-orders', params: { id: item.id } }"
          >
            <el-button size="small">查看订单</el-button>
          </RouterLink>
          <el-button
            v-if="item.status === 'DRAFT'"
            size="small"
            type="primary"
            :loading="busyId === item.id"
            :disabled="busyId !== null"
            @click="handlePublish(item)"
          >
            发布
          </el-button>
          <el-button
            v-if="item.status === 'PUBLISHED'"
            size="small"
            :loading="busyId === item.id"
            :disabled="busyId !== null"
            @click="handleWithdraw(item)"
          >
            下架
          </el-button>
          <span v-if="item.status === 'WITHDRAWN'" class="faint list__note">
            已下架，不可重新上架
          </span>
        </div>
      </li>
    </ul>

    <el-pagination
      v-if="total > pageSize"
      class="pager"
      layout="prev, pager, next"
      :total="total"
      :current-page="page"
      :page-size="pageSize"
      @current-change="changePage"
    />
  </div>
</template>

<style scoped>
.toolbar {
  margin-bottom: 16px;
}

.notice {
  margin-bottom: 16px;
}

.list {
  margin: 0;
  padding: 0;
  list-style: none;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
}

.list__row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding: 16px 18px;
  border-bottom: 1px solid var(--line);
}

.list__row:last-child {
  border-bottom: none;
}

.list__main {
  min-width: 0;
}

.list__title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 16px;
  font-weight: 600;
}

.facts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 24px;
  margin: 0;
}

.facts__item {
  display: flex;
  gap: 8px;
}

.facts__item dt {
  color: var(--ink-faint);
  font-size: 13px;
}

.facts__item dd {
  margin: 0;
  font-size: 13px;
}

.list__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.list__note {
  font-size: 12px;
}

.pager {
  margin-top: 20px;
  justify-content: center;
}
</style>
