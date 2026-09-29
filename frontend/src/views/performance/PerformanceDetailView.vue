<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { fetchPublicPerformance } from '@/api/performance'
import { ApiError } from '@/types/api'
import {
  PERFORMANCE_STATUS_LABEL,
  UNAVAILABLE_REASON_LABEL,
  type Performance,
} from '@/types/performance'
import { centToYuan } from '@/utils/money'
import { formatShanghaiWithSeconds } from '@/utils/time'

const route = useRoute()

/** 路由参数可能是数组；统一取字符串，**始终不转 Number** */
const performanceId = computed(() => {
  const raw = route.params.id
  return typeof raw === 'string' ? raw : (raw[0] ?? '')
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
        title="购票功能后续阶段开放"
        description="当前阶段只展示演出信息，没有下单入口，也不会生成任何订单。上面显示「可售」仅表示这场演出在售，不代表当前账号已获得购买资格。"
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
