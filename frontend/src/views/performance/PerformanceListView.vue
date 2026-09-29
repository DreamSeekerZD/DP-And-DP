<script setup lang="ts">
import { ref } from 'vue'
import { fetchPublicPerformances } from '@/api/performance'
import { UNAVAILABLE_REASON_LABEL, type Performance } from '@/types/performance'
import { centToYuan } from '@/utils/money'
import { formatShanghai } from '@/utils/time'

/** 列表固定每页 10 条；后端 page 从 1 开始 */
const PAGE_SIZE = 10

const performances = ref<Performance[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const errorMessage = ref('')

/**
 * 可售状态：canPurchase 只说明这场演出在售，**不代表当前用户能买**。
 * 不可售时用后端给出的原因，不自己编造「售罄」之类的结论。
 */
function statusLabel(item: Performance): string {
  if (item.canPurchase) return '可售'
  if (item.unavailableReason) return UNAVAILABLE_REASON_LABEL[item.unavailableReason]
  return '不可售'
}

/** 加载当前页；失败只提示一次，重试一律由用户点按钮触发 */
async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await fetchPublicPerformances({ page: page.value, pageSize: PAGE_SIZE })
    performances.value = result.items
    total.value = result.total
  } catch (error) {
    performances.value = []
    total.value = 0
    errorMessage.value = error instanceof Error ? error.message : '演出列表加载失败'
  } finally {
    loading.value = false
  }
}

function handlePageChange(next: number): void {
  page.value = next
  void load()
}

void load()
</script>

<template>
  <div class="page">
    <div class="page__head">
      <div>
        <p class="eyebrow">公开演出</p>
        <h1 class="page__title">演出</h1>
        <p class="muted list__notice">购票功能后续阶段开放，当前只展示演出信息。</p>
      </div>
    </div>

    <!-- 三种状态互斥：加载中 / 加载失败（可手动重试）/ 空列表 / 有数据 -->
    <el-skeleton v-if="loading" :rows="6" animated />

    <template v-else-if="errorMessage">
      <el-alert type="error" :title="errorMessage" :closable="false" show-icon />
      <div class="list__retry">
        <el-button @click="load">重新加载</el-button>
      </div>
    </template>

    <el-empty v-else-if="performances.length === 0" description="暂时没有公开的演出">
      <el-button @click="load">刷新看看</el-button>
    </el-empty>

    <template v-else>
      <ul class="list">
        <li v-for="item in performances" :key="item.id" class="row perforation">
          <RouterLink
            class="row__link"
            :to="{ name: 'performance-detail', params: { id: item.id } }"
          >
            <div class="row__main">
              <h2 class="row__title">{{ item.title || '未填写' }}</h2>
              <p class="row__meta">
                <span class="num">{{ formatShanghai(item.startsAt) || '未填写' }}</span>
                <span aria-hidden="true">·</span>
                <span>{{ item.venue || '未填写' }}</span>
              </p>
            </div>

            <div class="row__side">
              <span v-if="item.priceCent !== null" class="price">
                <span class="price__symbol">¥</span>
                <span class="price__value num">{{ centToYuan(item.priceCent) }}</span>
              </span>
              <span v-else class="faint">未填写</span>
              <el-tag :type="item.canPurchase ? 'success' : 'info'" size="small" effect="plain">
                {{ statusLabel(item) }}
              </el-tag>
            </div>
          </RouterLink>
        </li>
      </ul>

      <div class="list__pager">
        <el-pagination
          layout="prev, pager, next"
          :total="total"
          :current-page="page"
          :page-size="PAGE_SIZE"
          @current-change="handlePageChange"
        />
      </div>
    </template>
  </div>
</template>

<style scoped>
.list__notice {
  margin: 6px 0 0;
  font-size: 13px;
}

.list {
  margin: 0;
  padding: 0;
  border-top: 1px solid var(--line);
  list-style: none;
}

.row {
  border-bottom: 1px solid var(--line);
  background: var(--surface);
}

/* 固定行高，翻页时列表不会上下跳动 */
.row__link {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 76px;
  padding: 14px 18px;
  color: inherit;
  text-decoration: none;
}

.row__link:hover {
  background: var(--paper);
}

.row__main {
  min-width: 0;
}

.row__title {
  margin: 0;
  overflow: hidden;
  font-size: 16px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 4px 0 0;
  color: var(--ink-soft);
  font-size: 13px;
}

.row__side {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 12px;
}

.list__retry {
  margin-top: 16px;
}

.list__pager {
  display: flex;
  justify-content: center;
  margin-top: 20px;
}

@media (max-width: 560px) {
  .row__link {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
