<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { placeOrder } from '@/api/order'
import { fetchPublicPerformance } from '@/api/performance'
import { ApiError } from '@/types/api'
import {
  PERFORMANCE_STATUS_LABEL,
  UNAVAILABLE_REASON_LABEL,
  type Performance,
} from '@/types/performance'
import { useAuthStore } from '@/stores/auth'
import { centToYuan } from '@/utils/money'
import { formatShanghaiWithSeconds } from '@/utils/time'

const route = useRoute()
const auth = useAuthStore()
const router = useRouter()

/** 路由参数可能是数组；统一取字符串，**始终不转 Number** */
const performanceId = computed(() => {
  const raw = route.params.id
  return typeof raw === 'string' ? raw : (Array.isArray(raw) ? (raw[0] ?? '') : '')
})

const performance = ref<Performance | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const notFound = ref(false)
/** 封面缺失或加载失败时退化成文字占位块，不重试、也不请求任何外部图片 */
const coverFailed = ref(false)

/** 可售状态：canPurchase 只说明这场演出在售，**不代表当前用户能买** */
function statusLabel(item: Performance): string {
  if (item.canPurchase) return '可售'
  if (item.unavailableReason) return UNAVAILABLE_REASON_LABEL[item.unavailableReason]
  return '不可售'
}

/** 库存、价格等可空字段统一用「未填写」，绝不显示 null */
function stockText(value: number | null): string {
  return value === null ? '未填写' : String(value)
}

function handleCoverError(): void {
  coverFailed.value = true
}

async function load(): Promise<void> {
  const id = performanceId.value
  if (id === '') {
    performance.value = null
    notFound.value = true
    return
  }

  loading.value = true
  errorMessage.value = ''
  notFound.value = false
  coverFailed.value = false
  try {
    performance.value = await fetchPublicPerformance(id)
  } catch (error) {
    performance.value = null
    // 404 是「不存在或尚未发布」的确定结论，不是可重试的失败，更不能用示例数据顶替
    if (error instanceof ApiError && error.isNotFound) {
      notFound.value = true
    } else {
      errorMessage.value = error instanceof Error ? error.message : '演出详情加载失败'
    }
  } finally {
    loading.value = false
  }
}

// ---- 下单 ----
/** 下单进行中；用于禁用按钮，防止连点产生多个请求 */
const placing = ref(false)
/** 下单结果提示（留在页面上，不用一闪而过的 toast 承担） */
const placeMessage = ref('')
/**
 * 下单后的「本页购买是否被禁用」状态（B05 Redis 异步下单的反馈合同）：
 * - 'processing'：ORDER_PROCESSING，已占用但暂无订单 → 提示稍后到我的订单确认；
 * - 'unknown'：ORDER_RESULT_UNKNOWN / 网络超时 / 其它 503 → 结果不确定，先查订单；
 * - 'none'：不阻塞（成功、售罄、暂不可购买、业务拒绝等）。
 * 阻塞只存在本组件的这一次生命周期里：不落任何全局/持久存储，
 * 手动刷新或离开重进（重新挂载）即恢复初始状态，可再次主动尝试。
 */
type PlacementBlockState = 'processing' | 'unknown' | 'none'
const placementBlock = ref<PlacementBlockState>('none')
const placementBlocked = computed(() => placementBlock.value !== 'none')

/**
 * 按 B05 HTTP 合同把下单错误映射成本页反馈。
 * 只在这里决定「提示文案 + 是否阻塞本页购买」；请求层不自动重放 POST。
 */
function applyPlaceError(error: unknown): void {
  if (error instanceof ApiError) {
    if (error.status === 0) {
      // 网络超时/中断：结果未知，先查订单，不能承诺失败，更不能自动重发
      placementBlock.value = 'unknown'
      placeMessage.value = '下单结果待确认：网络或服务可能出现异常。请先到「我的订单」核对是否已生成订单。'
      return
    }
    if (error.status === 503 && error.code === 'ORDER_RESULT_UNKNOWN') {
      placementBlock.value = 'unknown'
      placeMessage.value = '下单结果未知：服务处理结果不确定。请先到「我的订单」核对是否已生成订单。'
      return
    }
    if (error.status === 503 && error.code !== 'ORDER_ADMISSION_UNAVAILABLE') {
      // 其它 503（如 SERVICE_BUSY）：同样结果不确定，先查订单
      placementBlock.value = 'unknown'
      placeMessage.value = '服务暂时不可用，下单结果待确认。请先到「我的订单」核对是否已生成订单。'
      return
    }
    if (error.status === 503) {
      // ORDER_ADMISSION_UNAVAILABLE：明确暂不可准入，结束 loading，不阻塞再次尝试
      placementBlock.value = 'none'
      placeMessage.value = error.message || '当前演出暂不可购买，请稍后再试。'
      return
    }
    if (error.status === 409 && error.code === 'ORDER_PROCESSING') {
      placementBlock.value = 'processing'
      placeMessage.value = '下单正在处理，请稍后到「我的订单」确认。'
      return
    }
    if (error.status === 409 && error.code === 'STOCK_INSUFFICIENT') {
      placementBlock.value = 'none'
      placeMessage.value = error.message || '演出已售罄。'
      return
    }
    if (error.status === 401) {
      placementBlock.value = 'none'
      placeMessage.value = '登录已失效，请重新登录后再购买'
      return
    }
    // 其它（如 409 PERFORMANCE_NOT_SALEABLE、参数、404 等）：明确原因，不阻塞
    placementBlock.value = 'none'
    placeMessage.value = error.message
    return
  }
  placementBlock.value = 'none'
  placeMessage.value = '下单失败，请稍后重试'
}

/**
 * 下单入口。
 * 匿名 → 引导登录后回详情，**不自动下单**；运营 → 页面不展示购买按钮。
 * 处理中 / 结果未知 / 超时之后，本页购买保持禁用并提供「我的订单」入口，
 * **查询为空不自动解锁**；手动刷新或离开重进（重新挂载）才允许再次主动尝试。
 * 返回/离开期间响应不应把用户强行带回旧详情：发起前记录原地址，回来后核对。
 */
async function handleBuy(): Promise<void> {
  if (placing.value || placementBlocked.value) return

  if (!auth.isLoggedIn) {
    await router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  if (!auth.isUser) return // 运营账号由模板展示说明，不出现可提交的购买按钮

  const perf = performance.value
  if (!perf) return

  const fromPath = route.fullPath
  placeMessage.value = ''
  placementBlock.value = 'none'
  placing.value = true

  try {
    const result = await placeOrder(perf.id)
    // 请求期间用户已经离开：不强行带回
    if (route.fullPath !== fromPath) return

    if (result.created) {
      ElMessage.success('下单成功')
    } else {
      // created=false 是成功，不是错误：已有有效订单，直接打开原单
      ElMessage.info('已有一张有效订单，正在为你打开')
    }
    await router.push({ name: 'order-detail', params: { id: result.order.id } })
  } catch (error) {
    if (route.fullPath !== fromPath) return

    applyPlaceError(error)
  } finally {
    placing.value = false
  }
}

// 直接在详情之间跳转时 id 会变，重新拉取即可
watch(performanceId, () => void load(), { immediate: true })
</script>

<template>
  <div class="page">
    <RouterLink class="detail__back" :to="{ name: 'performance-list' }">← 返回演出列表</RouterLink>

    <el-skeleton v-if="loading" :rows="6" animated />

    <!-- 404：对外与「未发布」是同一个答案，不透露草稿是否存在 -->
    <div v-else-if="notFound" class="detail__missing">
      <p class="eyebrow">404</p>
      <h1 class="page__title">演出不存在或尚未发布</h1>
      <p class="muted">这个演出可能已被下架，或者还没有对外公开。</p>
      <RouterLink :to="{ name: 'performance-list' }">
        <el-button type="primary">返回演出列表</el-button>
      </RouterLink>
    </div>

    <template v-else-if="errorMessage">
      <el-alert type="error" :title="errorMessage" :closable="false" show-icon />
      <div class="detail__retry">
        <el-button @click="load">重新加载</el-button>
      </div>
    </template>

    <template v-else-if="performance">
      <div class="page__head">
        <div class="detail__heading">
          <p class="eyebrow">公开演出</p>
          <h1 class="page__title">{{ performance.title || '未填写' }}</h1>
        </div>
      </div>

      <div class="detail__body">
        <div class="detail__cover">
          <img
            v-if="performance.coverUrl && !coverFailed"
            class="detail__image"
            :src="performance.coverUrl"
            alt="演出封面"
            @error="handleCoverError"
          />
          <div v-else class="detail__cover-fallback">暂无封面</div>
        </div>

        <dl class="detail__facts">
          <div class="detail__fact">
            <dt>开始时间</dt>
            <dd class="num">
              {{ formatShanghaiWithSeconds(performance.startsAt) || '未填写' }}
              <span class="faint detail__zone">北京时间</span>
            </dd>
          </div>
          <div class="detail__fact">
            <dt>场馆</dt>
            <dd>{{ performance.venue || '未填写' }}</dd>
          </div>
          <div class="detail__fact">
            <dt>票价</dt>
            <dd>
              <span v-if="performance.priceCent !== null" class="price">
                <span class="price__symbol">¥</span>
                <span class="price__value num">{{ centToYuan(performance.priceCent) }}</span>
              </span>
              <span v-else class="faint">未填写</span>
            </dd>
          </div>
          <div class="detail__fact">
            <dt>总库存</dt>
            <dd class="num">{{ stockText(performance.totalStock) }}</dd>
          </div>
          <div class="detail__fact">
            <dt>剩余库存</dt>
            <dd class="num">{{ stockText(performance.availableStock) }}</dd>
          </div>
          <div class="detail__fact">
            <dt>演出状态</dt>
            <dd>
              <el-tag size="small" effect="plain">
                {{ PERFORMANCE_STATUS_LABEL[performance.status] }}
              </el-tag>
            </dd>
          </div>
          <div class="detail__fact">
            <dt>售票状态</dt>
            <dd>
              <el-tag
                size="small"
                effect="plain"
                :type="performance.canPurchase ? 'success' : 'info'"
              >
                {{ statusLabel(performance) }}
              </el-tag>
            </dd>
          </div>
        </dl>
      </div>

      <!-- 购买区：只对可售演出出现。这只是入口，真实权限由后端裁决 -->
      <div v-if="performance.canPurchase" class="buy">
        <div class="buy__line">
          <span class="price buy__price">
            <span class="price__symbol">¥</span>
            <span class="price__value num">{{ centToYuan(performance.priceCent) }}</span>
          </span>

          <el-button
            v-if="auth.isUser"
            type="primary"
            :loading="placing"
            :disabled="placing || placementBlocked"
            @click="handleBuy"
          >
            购买一张
          </el-button>
          <!-- 匿名：引导登录后回本页，不自动下单 -->
          <el-button
            v-else-if="!auth.isLoggedIn"
            :disabled="placing || placementBlocked"
            @click="handleBuy"
          >
            登录后购买
          </el-button>
        </div>

        <p v-if="!auth.isLoggedIn" class="buy__hint muted">
          需要普通用户账号才能下单；运营账号无法以自身身份购买。
        </p>

        <el-alert
          v-if="auth.isOperator"
          class="buy__notice"
          type="info"
          :closable="false"
          show-icon
          title="运营账号无法购买"
          description="请使用普通用户账号登录后购买。演示账号见 backend/sql/README.md。"
        />
      </div>

      <!-- 页面级下单反馈：处理中/结果未知/售罄/暂不可购买等，不随购买区隐藏 -->
      <el-alert
        v-if="placeMessage"
        class="notice"
        type="warning"
        :title="placeMessage"
        :closable="false"
        show-icon
      >
        <template v-if="placementBlocked" #default>
          <RouterLink :to="{ name: 'my-orders', query: { performanceId: performance.id } }">
            <el-button size="small">查看该演出的订单</el-button>
          </RouterLink>
        </template>
      </el-alert>

      <section class="detail__section">
        <h2 class="detail__label">演出介绍</h2>
        <!-- 纯文本，保留换行；描述来自运营，不当作 HTML 解析 -->
        <p v-if="performance.description" class="detail__description">
          {{ performance.description }}
        </p>
        <p v-else class="faint">未填写</p>
      </section>

      <el-alert
        class="detail__notice"
        type="info"
        :closable="false"
        show-icon
        title="模拟支付"
        description="下单后请在支付截止时间前到「我的订单」完成付款。模拟支付不产生真实资金，也不会生成电子核销码；超时未处理的名额会由服务端关闭并释放。"
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

.detail__missing {
  max-width: 560px;
}

.detail__missing p {
  margin: 0 0 20px;
}

.detail__heading {
  min-width: 0;
}

.detail__body {
  display: flex;
  align-items: flex-start;
  gap: 24px;
}

.detail__cover {
  flex-shrink: 0;
  width: 260px;
  aspect-ratio: 16 / 10;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  overflow: hidden;
  background: var(--surface);
}

.detail__image {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.detail__cover-fallback {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
  color: var(--ink-faint);
  font-size: 13px;
}

.detail__facts {
  flex: 1;
  min-width: 0;
  margin: 0;
}

.detail__fact {
  display: flex;
  align-items: baseline;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px solid var(--line);
}

.detail__fact dt {
  flex-shrink: 0;
  width: 76px;
  color: var(--ink-soft);
  font-size: 13px;
}

.detail__fact dd {
  margin: 0;
}

.detail__zone {
  margin-left: 6px;
  font-size: 12px;
}

.detail__section {
  margin-top: 28px;
}

.detail__label {
  margin: 0 0 8px;
  font-size: 15px;
  font-weight: 600;
}

/* 描述是运营填的纯文本，原样保留换行 */
.detail__description {
  margin: 0;
  white-space: pre-wrap;
}

.buy {
  margin-top: 20px;
  padding: 16px 18px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
}

.buy__line {
  display: flex;
  align-items: center;
  gap: 16px;
}

.buy__price {
  font-size: 22px;
}

.buy__hint {
  margin: 10px 0 0;
  font-size: 13px;
}

.buy__notice {
  margin-top: 12px;
}

/* 页面级下单反馈（处理中/未知/售罄等），在 `.buy` 区之外、不受购买区显示与否影响 */
.notice {
  margin-top: 14px;
}

.detail__notice {
  margin-top: 28px;
}

.detail__retry {
  margin-top: 16px;
}

@media (max-width: 720px) {
  .detail__body {
    flex-direction: column;
  }

  .detail__cover {
    width: 100%;
  }
}
</style>
