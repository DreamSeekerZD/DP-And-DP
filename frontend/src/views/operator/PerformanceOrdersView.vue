<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchOperatorOrders } from '@/api/order'
import { fetchOwnedPerformance } from '@/api/performance'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/types/api'
import {
  CLOSE_REASON_LABEL,
  ORDER_STATUS_LABEL,
  type OperatorOrder,
  type OrderStatus,
} from '@/types/order'
import type { Performance } from '@/types/performance'
import { centToYuan } from '@/utils/money'
import { formatShanghaiWithSeconds } from '@/utils/time'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

/** 演出编号始终是字符串，不转 Number */
const performanceId = computed(() => {
  const raw = route.params.id
  return typeof raw === 'string' ? raw : (raw[0] ?? '')
})

/** 演出信息走运营详情接口：草稿/发布/下架都能看，不依赖公开详情（下架即 404） */
const performance = ref<Performance | null>(null)
const items = ref<OperatorOrder[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 10
const statusFilter = ref<OrderStatus | ''>('')

const loading = ref(false)
const loadError = ref('')
const notVisible = ref(false)

/** 请求序号：只认最新一次响应，切换演出/退出时旧响应不得覆盖新数据 */
let seq = 0

async function load(): Promise<void> {
  const id = performanceId.value
  if (id === '') {
    notVisible.value = true
    return
  }

  const mySeq = ++seq
  loading.value = true
  loadError.value = ''
  notVisible.value = false
  try {
    const [perf, result] = await Promise.all([
      fetchOwnedPerformance(id),
      fetchOperatorOrders(id, {
        page: page.value,
        pageSize,
        status: statusFilter.value === '' ? undefined : statusFilter.value,
      }),
    ])
    if (mySeq !== seq) return
    performance.value = perf
    items.value = result.items
    total.value = result.total
  } catch (error) {
    if (mySeq !== seq) return
    performance.value = null
    items.value = []
    total.value = 0
    if (error instanceof ApiError && error.isNotFound) {
      // 演出不存在或不属于当前运营：统一不可见，不残留上一场演出的订单
      notVisible.value = true
    } else {
      loadError.value = error instanceof Error ? error.message : '营业订单加载失败'
    }
  } finally {
    if (mySeq === seq) loading.value = false
  }
}

/** 状态筛选变化即回第 1 页重查（用 watcher，不依赖组件 change 事件） */
watch(statusFilter, () => {
  page.value = 1
  void load()
})

function changePage(next: number): void {
  page.value = next
  void load()
}

/** 退出 / 会话失效 / 换账号：清空私有数据并离开受保护页 */
watch(
  () => auth.user,
  (next) => {
    if (next !== null) return
    seq++
    performance.value = null
    items.value = []
    total.value = 0
    loadError.value = ''
    void router.replace({ name: 'login' })
  },
)

watch(performanceId, () => {
  page.value = 1
  void load()
})

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page__head">
      <div>
        <p class="eyebrow">运营 / 演出订单</p>
        <h1 class="page__title">{{ performance?.title ?? '未命名演出' }}</h1>
        <p v-if="performance" class="muted orders__meta">
          <span class="num">{{ formatShanghaiWithSeconds(performance.startsAt) || '未填写' }}</span>
          <span>· {{ performance.venue ?? '未填写' }}</span>
          <el-tag size="small" :type="performance.status === 'PUBLISHED' ? 'success' : 'info'" class="orders__tag">
            {{ performance.status === 'DRAFT' ? '草稿' : performance.status === 'PUBLISHED' ? '已发布' : '已下架' }}
          </el-tag>
        </p>
      </div>
      <RouterLink :to="{ name: 'my-performances' }">
        <el-button>返回我的发布</el-button>
      </RouterLink>
    </div>

    <el-result
      v-if="notVisible"
      icon="warning"
      title="演出不存在或不可见"
      sub-title="这场演出可能不存在，或者属于其他运营人员。"
    >
      <template #extra>
        <RouterLink :to="{ name: 'my-performances' }">
          <el-button type="primary">返回我的发布</el-button>
        </RouterLink>
      </template>
    </el-result>

    <template v-else>
      <div class="toolbar">
        <el-radio-group v-model="statusFilter" :disabled="loading">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="PENDING_PAYMENT">待支付</el-radio-button>
          <el-radio-button value="PAID">已支付</el-radio-button>
          <el-radio-button value="CLOSED">已关闭</el-radio-button>
        </el-radio-group>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
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
        description="这场演出还没有订单"
      />

      <div v-else-if="items.length > 0" class="table">
        <table>
          <thead>
            <tr>
              <th>订单号</th>
              <th>金额</th>
              <th>状态</th>
              <th>下单时间</th>
              <th>支付截止</th>
              <th>支付时间</th>
              <th>关闭</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in items" :key="item.id">
              <td class="num">{{ item.id }}</td>
              <td><span class="price"><span class="price__symbol">¥</span><span class="num">{{ centToYuan(item.amountCent) }}</span></span></td>
              <td>
                <el-tag size="small" :type="item.status === 'PAID' ? 'success' : item.status === 'CLOSED' ? 'info' : 'warning'">
                  {{ ORDER_STATUS_LABEL[item.status] }}
                </el-tag>
              </td>
              <td class="num">{{ formatShanghaiWithSeconds(item.createdAt) }}</td>
              <td class="num">{{ formatShanghaiWithSeconds(item.expireAt) }}</td>
              <td class="num">{{ item.paidAt ? formatShanghaiWithSeconds(item.paidAt) : '—' }}</td>
              <td>
                <span class="num">{{ item.closedAt ? formatShanghaiWithSeconds(item.closedAt) : '—' }}</span>
                <span v-if="item.closeReason" class="faint">（{{ CLOSE_REASON_LABEL[item.closeReason] }}）</span>
              </td>
            </tr>
          </tbody>
        </table>
        <p class="faint table__note">
          只读查看，不展示用户资料与票号，也没有代付/代取消入口。
        </p>
      </div>

      <el-pagination
        v-if="total > pageSize"
        class="pager"
        layout="prev, pager, next"
        :total="total"
        :current-page="page"
        :page-size="pageSize"
        @current-change="changePage"
      />
    </template>
  </div>
</template>

<style scoped>
.orders__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 6px 0 0;
  font-size: 13px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 16px;
}

.notice {
  margin-bottom: 16px;
}

.table {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  overflow-x: auto;
}

.table table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.table th,
.table td {
  padding: 10px 14px;
  text-align: left;
  border-bottom: 1px solid var(--line);
  white-space: nowrap;
}

.table th {
  color: var(--ink-faint);
  font-weight: 600;
}

.table tbody tr:last-child td {
  border-bottom: none;
}

.table__note {
  margin: 10px 14px 12px;
  font-size: 12px;
}

.pager {
  margin-top: 20px;
  justify-content: center;
}
</style>