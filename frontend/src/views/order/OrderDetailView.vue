<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { cancelOrder, fetchOrder } from '@/api/order'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/types/api'
import {
  CLOSE_REASON_LABEL,
  ORDER_STATUS_LABEL,
  type Order,
} from '@/types/order'
import { formatDuration, remainingSeconds } from '@/utils/order-time'
import { centToYuan } from '@/utils/money'
import { formatShanghaiWithSeconds } from '@/utils/time'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

/** 订单号始终是字符串，不转 Number */
const orderId = computed(() => {
  const raw = route.params.id
  return typeof raw === 'string' ? raw : (raw[0] ?? '')
})

const order = ref<Order | null>(null)
const loading = ref(false)
const loadError = ref('')
const notFound = ref(false)

const canceling = ref(false)
/** 取消结果 / 查询失败等需要停留在页面上的说明，不用一闪而过的 toast 承担 */
const cancelMessage = ref('')

// ---- 倒计时 ----
/** 当前倒计时的展示秒数 */
const remainingSecondsNow = ref(0)
/** 已到支付截止时间，等待服务端关闭 */
const zeroWaiting = ref(false)
/** 收到本次响应时 performance.now() 的基准，之后只取差值，不累减、不读本地墙上时钟 */
let elapsedBase = 0
let ticker: number | undefined
/** 归零只允许触发一次单次查询，避免「仍待支付就反复自触发」 */
let zeroFired = false

/**
 * 数据落盘守卫：load 与归零补查、取消共用。只有「最新一次发起」才有资格写 order/提示，
 * 防止旧 id / 旧身份 / 旧请求覆盖新数据。
 */
let dataSeq = 0
/**
 * 加载标志守卫：只有 load 使用。归零补查、取消等并发动作不会碰它，
 * 因此它们永远不可能把「加载中」卡住（本修复的生命周期拆分）。
 */
let loadSeq = 0

function stopTicker(): void {
  if (ticker !== undefined) {
    window.clearInterval(ticker)
    ticker = undefined
  }
}

/** 用一份新响应整体替换订单状态，并重设倒计时基准 */
function applyOrder(o: Order): void {
  order.value = o
  elapsedBase = performance.now()
  remainingSecondsNow.value = remainingSeconds(o.expireAt, o.serverTime, 0)
  zeroFired = false

  if (o.status !== 'PENDING_PAYMENT') {
    // 已支付 / 已关闭：停表，不显示倒计时
    zeroWaiting.value = false
    stopTicker()
    remainingSecondsNow.value = 0
  } else if (remainingSecondsNow.value <= 0) {
    // 刚取到就已到期：直接显示「等待关闭」，不再为同一份最新响应补查一次 GET；
    // 补查只在计时中从正数减到 0 时触发一次（见 startTicker）。
    // 这里不伪造 CLOSED，真实状态仍由后端返回。
    zeroWaiting.value = true
    stopTicker()
  } else {
    zeroWaiting.value = false
    startTicker()
  }
}

function startTicker(): void {
  stopTicker()
  ticker = window.setInterval(() => {
    const o = order.value
    if (!o || o.status !== 'PENDING_PAYMENT') {
      stopTicker()
      return
    }
    const rem = remainingSeconds(o.expireAt, o.serverTime, performance.now() - elapsedBase)
    remainingSecondsNow.value = rem
    if (rem <= 0) {
      stopTicker()
      void triggerZeroFetch()
    }
  }, 1000)
}

/**
 * 归零：只提示一次 + 单次 GET 真实状态，不本地改 CLOSED、不循环查询。
 * 成功与失败都必须先过 data 守卫：切换订单后，旧的归零结果（含失败提示）不得污染新订单。
 */
async function triggerZeroFetch(): Promise<void> {
  if (zeroFired) return
  zeroFired = true
  zeroWaiting.value = true

  const myData = ++dataSeq
  try {
    const o = await fetchOrder(orderId.value)
    if (myData !== dataSeq) return
    order.value = o
    // 保留真实状态：仍在待支付就继续显示「等待关闭」，由后端补扫决定何时 CLOSED
    zeroWaiting.value = o.status === 'PENDING_PAYMENT'
    remainingSecondsNow.value = 0
  } catch {
    if (myData !== dataSeq) return
    // 查询失败：保留提示并给出手动刷新入口
    zeroWaiting.value = true
    cancelMessage.value = '状态查询失败，请点「刷新」重新核实'
  }
}

const isPending = computed(() => order.value?.status === 'PENDING_PAYMENT')
const isClosed = computed(() => order.value?.status === 'CLOSED')
const isPaid = computed(() => order.value?.status === 'PAID')

async function load(preserveMessage = false): Promise<void> {
  const id = orderId.value
  if (id === '') {
    order.value = null
    notFound.value = true
    return
  }

  const myLoad = ++loadSeq
  const myData = ++dataSeq
  loading.value = true
  loadError.value = ''
  notFound.value = false
  if (!preserveMessage) cancelMessage.value = ''
  zeroWaiting.value = false

  try {
    const o = await fetchOrder(id)
    if (myData !== dataSeq) return
    applyOrder(o)
  } catch (error) {
    if (myData !== dataSeq) return
    order.value = null
    stopTicker()
    if (error instanceof ApiError && error.isNotFound) {
      notFound.value = true
    } else {
      loadError.value = error instanceof Error ? error.message : '订单详情加载失败'
    }
  } finally {
    // 只由「本次发起加载的最新一次调用」负责关闭 loading；
    // 归零补查等并发动作只递增 dataSeq，绝不会把加载中标志卡住。
    if (myLoad === loadSeq) loading.value = false
  }
}

async function handleCancel(): Promise<void> {
  const o = order.value
  if (!o || canceling.value) return
  if (!(o.status === 'PENDING_PAYMENT' && o.canCancel)) return

  // 确认框点「再想想」直接 return，不发任何请求
  try {
    await ElMessageBox.confirm(
      '取消后会立即释放这一张名额，订单关闭且不可恢复。确认取消吗？',
      '确认取消',
      { confirmButtonText: '取消订单', cancelButtonText: '再想想', type: 'warning' },
    )
  } catch {
    return
  }

  canceling.value = true
  cancelMessage.value = ''
  const myData = ++dataSeq

  try {
    await cancelOrder(o.id)
    if (myData !== dataSeq) return
    ElMessage.success('订单已取消')
    // 回读后端真实结果，不本地设 CLOSED、不手工改库存
    await load()
  } catch (error) {
    if (myData !== dataSeq) return
    if (error instanceof ApiError && error.status === 0) {
      // 结果不确定：不承诺已取消，留在页面上给刷新入口
      cancelMessage.value = '取消结果待确认：可能网络或服务端超时，请点「刷新」核实订单状态'
    } else {
      cancelMessage.value = error instanceof Error ? error.message : '取消失败，请重试'
    }
    // 失败保留订单信息，重新读取一次当前状态；若确已关闭则清除待确认提示
    void load(true).then(() => {
      if (order.value?.status === 'CLOSED') cancelMessage.value = ''
    })
  } finally {
    canceling.value = false
  }
}

// 页面恢复可见时重新核对（可能已被后台补扫关闭）
function onVisibility(): void {
  if (document.visibilityState === 'visible' && order.value !== null) {
    void load()
  }
}

// 退出 / 会话失效 / 换账号：清空私有数据并离开受保护页，不再显示上一个用户的订单
watch(
  () => auth.user,
  (next) => {
    if (next !== null) return
    dataSeq++
    loadSeq++
    stopTicker()
    order.value = null
    loadError.value = ''
    void router.replace({ name: 'login' })
  },
)

watch(orderId, () => void load())

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibility)
  void load()
})

onBeforeUnmount(() => {
  // 使在途响应失效，避免卸载后仍把数据写进已销毁的组件
  dataSeq++
  loadSeq++
  stopTicker()
  document.removeEventListener('visibilitychange', onVisibility)
})
</script>

<template>
  <div class="page">
    <RouterLink class="detail__back" :to="{ name: 'my-orders' }">← 返回我的订单</RouterLink>

    <el-skeleton v-if="loading" :rows="6" animated />

    <el-result
      v-else-if="notFound"
      icon="warning"
      title="订单不存在或不可见"
      sub-title="这可能是别人的订单，或者该订单号不存在。"
    >
      <template #extra>
        <RouterLink :to="{ name: 'my-orders' }">
          <el-button type="primary">返回我的订单</el-button>
        </RouterLink>
      </template>
    </el-result>

    <template v-else-if="loadError">
      <el-alert type="error" :title="loadError" :closable="false" show-icon />
      <div class="detail__retry">
        <el-button @click="load">刷新</el-button>
      </div>
    </template>

    <template v-else-if="order">
      <div class="page__head">
        <div class="detail__heading">
          <p class="eyebrow">我的订单</p>
          <h1 class="page__title">
            {{ order.performanceTitle ?? '未命名演出' }}
          </h1>
          <div class="detail__status">
            <el-tag
              size="small"
              :type="isPaid ? 'success' : isClosed ? 'info' : 'warning'"
            >
              {{ ORDER_STATUS_LABEL[order.status] }}
            </el-tag>
            <span v-if="isClosed && order.closeReason" class="faint">
              {{ CLOSE_REASON_LABEL[order.closeReason] }}
            </span>
          </div>
        </div>
      </div>

      <!-- 倒计时：真实剩余秒数，归零只提示并做一次查询 -->
      <div v-if="isPending" class="countdown" :class="{ 'countdown--waiting': zeroWaiting }">
        <template v-if="!zeroWaiting">
          <span class="countdown__label">支付剩余时间</span>
          <span class="countdown__num num">{{ formatDuration(remainingSecondsNow) }}</span>
        </template>
        <template v-else>
          <span class="countdown__waiting">已到支付截止时间，等待关闭</span>
        </template>
      </div>

      <el-alert
        v-if="cancelMessage"
        class="notice"
        type="warning"
        :title="cancelMessage"
        :closable="false"
        show-icon
      />

      <dl class="facts">
        <div class="fact">
          <dt>订单号</dt>
          <dd class="num">{{ order.id }}</dd>
        </div>
        <div class="fact">
          <dt>演出</dt>
          <dd>
            {{ order.performanceTitle ?? '未命名演出' }}
          </dd>
        </div>
        <div class="fact">
          <dt>演出时间</dt>
          <dd class="num">{{ formatShanghaiWithSeconds(order.performanceStartsAt) }}</dd>
        </div>
        <div class="fact">
          <dt>场馆</dt>
          <dd>{{ order.performanceVenue ?? '未填写' }}</dd>
        </div>
        <div class="fact">
          <dt>金额</dt>
          <dd>
            <span class="price">
              <span class="price__symbol">¥</span>
              <span class="price__value num">{{ centToYuan(order.amountCent) }}</span>
            </span>
          </dd>
        </div>
        <div class="fact">
          <dt>下单时间</dt>
          <dd class="num">{{ formatShanghaiWithSeconds(order.createdAt) }}</dd>
        </div>
        <div v-if="isPending" class="fact">
          <dt>支付截止</dt>
          <dd class="num">{{ formatShanghaiWithSeconds(order.expireAt) }}</dd>
        </div>
        <div v-if="isPaid && order.paidAt" class="fact">
          <dt>支付时间</dt>
          <dd class="num">{{ formatShanghaiWithSeconds(order.paidAt) }}</dd>
        </div>
        <div v-if="isPaid && order.ticketNo" class="fact">
          <dt>票号</dt>
          <dd class="num">{{ order.ticketNo }}</dd>
        </div>
        <div v-if="isClosed" class="fact">
          <dt>关闭时间</dt>
          <dd class="num">{{ formatShanghaiWithSeconds(order.closedAt) }}</dd>
        </div>
        <div v-if="isClosed" class="fact">
          <dt>关闭原因</dt>
          <dd>{{ order.closeReason ? CLOSE_REASON_LABEL[order.closeReason] : '未记录' }}</dd>
        </div>
      </dl>

      <div class="actions">
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
        <el-button
          v-if="isPending && order.canCancel"
          type="danger"
          :loading="canceling"
          :disabled="canceling || loading"
          @click="handleCancel"
        >
          取消订单
        </el-button>
        <!-- 已关闭：给一个回演出的入口，能否重购由后端决定，不直接恢复旧单 -->
        <RouterLink
          v-if="isClosed"
          :to="{ name: 'performance-detail', params: { id: order.performanceId } }"
        >
          <el-button size="small">返回演出查看是否可重新购买</el-button>
        </RouterLink>
      </div>

      <el-alert
        class="notice notice--pay"
        type="info"
        :closable="false"
        show-icon
        title="模拟支付将在下一阶段开放"
        description="当前阶段没有付款入口，也无法生成票号。待支付订单可以稍后关闭或等待超时释放；如果这台后端配置了短有效期（如 30 秒），到点后由服务端补扫关闭并释放名额。"
      />
    </template>
  </div>
</template>

<style scoped>
.detail__back {
  display: inline-block;
  margin-bottom: 16px;
  color: var(--ink-soft);
  font-size: 13px;
}

.detail__heading {
  display: flex;
  align-items: center;
  gap: 12px;
}

.detail__status {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.countdown {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
  padding: 8px 14px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  font-size: 14px;
}

.countdown--waiting {
  border-color: var(--el-color-warning-light-5);
}

.countdown__label {
  color: var(--ink-soft);
}

.countdown__num {
  font-size: 18px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.countdown__waiting {
  color: var(--el-color-warning);
  font-weight: 600;
}

.notice {
  margin: 0 0 16px;
}

.facts {
  margin: 0 0 20px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
}

.fact {
  display: flex;
  align-items: baseline;
  gap: 12px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--line);
}

.fact:last-child {
  border-bottom: none;
}

.fact dt {
  flex-shrink: 0;
  width: 92px;
  color: var(--ink-soft);
  font-size: 13px;
}

.fact dd {
  margin: 0;
}

.actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 20px;
}

.notice--pay {
  max-width: 640px;
}
</style>