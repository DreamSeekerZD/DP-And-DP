<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchMyOrders } from '@/api/order'
import { useAuthStore } from '@/stores/auth'
import { ORDER_STATUS_LABEL, type Order, type OrderStatus } from '@/types/order'
import { centToYuan } from '@/utils/money'
import { formatShanghai } from '@/utils/time'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const items = ref<Order[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 10
const statusFilter = ref<OrderStatus | ''>('')

const loading = ref(false)
const loadError = ref('')

/** 请求序号：只认最新一次响应，避免「快速切筛选 / 换账号」时旧响应覆盖新数据 */
let seq = 0

/**
 * 「查看该演出订单」入口的筛选参数。
 * 来源是下单结果不确定后从详情页带过来的 performanceId，
 * 说明这单还没确认建没建，交给列表按演出查询。
 */
const performanceId = computed(() => {
  const raw = route.query.performanceId
  return typeof raw === 'string' ? raw : ''
})

const orderFilterLabel = computed(() =>
  performanceId.value ? `演出 #${performanceId.value}` : '',
)

async function load(): Promise<void> {
  const mySeq = ++seq
  loading.value = true
  loadError.value = ''
  try {
    const result = await fetchMyOrders({
      page: page.value,
      pageSize,
      status: statusFilter.value === '' ? undefined : statusFilter.value,
      performanceId: performanceId.value === '' ? undefined : performanceId.value,
    })
    if (mySeq !== seq) return
    items.value = result.items
    total.value = result.total
  } catch (error) {
    if (mySeq !== seq) return
    items.value = []
    total.value = 0
    loadError.value = error instanceof Error ? error.message : '订单列表加载失败'
  } finally {
    if (mySeq === seq) loading.value = false
  }
}

/** 切换状态筛选回到第 1 页 */
function changeFilter(): void {
  page.value = 1
  void load()
}

function changePage(next: number): void {
  page.value = next
  void load()
}

/** 退出 / 会话失效 / 换账号：立即清空私有数据并离开受保护页 */
watch(
  () => auth.user,
  (next) => {
    if (next !== null) return
    seq++ // 使在途响应失效
    items.value = []
    total.value = 0
    loadError.value = ''
    void router.replace({ name: 'login' })
  },
)

// 从详情页带 performanceId 跳过来时，自动切到该演出的订单筛选
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
        <p class="eyebrow">我的</p>
        <h1 class="page__title">我的订单</h1>
      </div>
    </div>

    <div class="toolbar">
      <el-radio-group v-model="statusFilter" :disabled="loading" @change="changeFilter">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="PENDING_PAYMENT">待支付</el-radio-button>
        <el-radio-button value="PAID">已支付</el-radio-button>
        <el-radio-button value="CLOSED">已关闭</el-radio-button>
      </el-radio-group>
      <span v-if="orderFilterLabel" class="toolbar__filter faint">
        当前筛选：{{ orderFilterLabel }}
        <RouterLink :to="{ name: 'my-orders' }">清除</RouterLink>
      </span>
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
      :description="orderFilterLabel ? '这场演出下还没有你的订单' : '还没有订单'"
    >
      <RouterLink :to="{ name: 'performance-list' }">
        <el-button type="primary">去浏览演出</el-button>
      </RouterLink>
    </el-empty>

    <ul v-else class="list">
      <li v-for="item in items" :key="item.id" class="list__row perforation">
        <RouterLink
          class="list__main"
          :to="{ name: 'order-detail', params: { id: item.id } }"
        >
          <div class="list__title">
            <span>{{ item.performanceTitle ?? '未命名演出' }}</span>
            <el-tag
              size="small"
              :type="item.status === 'PAID' ? 'success' : item.status === 'CLOSED' ? 'info' : 'warning'"
            >
              {{ ORDER_STATUS_LABEL[item.status] }}
            </el-tag>
          </div>

          <dl class="facts">
            <div class="facts__item">
              <dt>订单号</dt>
              <dd class="num">{{ item.id }}</dd>
            </div>
            <div class="facts__item">
              <dt>演出时间</dt>
              <dd class="num">{{ formatShanghai(item.performanceStartsAt) }}</dd>
            </div>
            <div class="facts__item">
              <dt>场馆</dt>
              <dd>{{ item.performanceVenue }}</dd>
            </div>
            <div class="facts__item">
              <dt>实付</dt>
              <dd>
                <span class="price">
                  <span class="price__symbol">¥</span>
                  <span class="num">{{ centToYuan(item.amountCent) }}</span>
                </span>
              </dd>
            </div>
            <div class="facts__item">
              <dt>下单时间</dt>
              <dd class="num">{{ formatShanghai(item.createdAt) }}</dd>
            </div>
          </dl>
        </RouterLink>
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
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 16px;
}

.toolbar__filter {
  font-size: 13px;
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
  border-bottom: 1px solid var(--line);
}

.list__row:last-child {
  border-bottom: none;
}

.list__main {
  display: block;
  padding: 16px 18px;
  color: inherit;
  text-decoration: none;
}

.list__main:hover {
  background: var(--paper);
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

.pager {
  margin-top: 20px;
  justify-content: center;
}
</style>