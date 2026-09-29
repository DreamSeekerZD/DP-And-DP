<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  createDraft,
  fetchOwnedPerformance,
  publishPerformance,
  updatePerformance,
  withdrawPerformance,
} from '@/api/performance'
import { ApiError } from '@/types/api'
import {
  PERFORMANCE_STATUS_LABEL,
  type Performance,
  type SavePerformancePayload,
} from '@/types/performance'
import { centToYuan, describeParseFailure, parseStock, yuanToCent } from '@/utils/money'
import { fromShanghaiInput, toShanghaiInput } from '@/utils/time'

const route = useRoute()
const router = useRouter()

/**
 * 演出编号。新建时为空，保存成功后才拿到。
 * 路径参数本来就是字符串，直接沿用，不转 Number。
 */
const performanceId = ref<string | null>(null)

const entity = ref<Performance | null>(null)
const loading = ref(false)
const submitting = ref(false)
const notVisible = ref(false)
const loadError = ref('')
const formError = ref('')

const form = reactive({
  title: '',
  description: '',
  coverUrl: '',
  venue: '',
  /** 北京时间的墙上时钟，由 el-date-picker 的 value-format 直接给出字符串 */
  startsAtLocal: '',
  /** 「元」的十进制字符串，提交前用 yuanToCent 转成整数分 */
  priceYuan: '',
  /** 库存的整数字符串 */
  totalStock: '',
})

/** 已发布/已下架：关键字段锁定，只有简介与封面可改 */
const isLocked = computed(
  () => entity.value !== null && entity.value.status !== 'DRAFT',
)

const isPublished = computed(() => entity.value?.status === 'PUBLISHED')
const isWithdrawn = computed(() => entity.value?.status === 'WITHDRAWN')
const isDraft = computed(() => entity.value === null || entity.value.status === 'DRAFT')

const statusLabel = computed(() =>
  entity.value ? PERFORMANCE_STATUS_LABEL[entity.value.status] : '新建',
)

function fillForm(source: Performance): void {
  form.title = source.title ?? ''
  form.description = source.description ?? ''
  form.coverUrl = source.coverUrl ?? ''
  form.venue = source.venue ?? ''
  // 后端给的是 UTC，转成北京时间墙上时钟再放进输入框
  form.startsAtLocal = toShanghaiInput(source.startsAt)
  form.priceYuan = centToYuan(source.priceCent)
  form.totalStock = source.totalStock === null ? '' : String(source.totalStock)
}

async function load(): Promise<void> {
  const id = performanceId.value
  if (id === null) return

  loading.value = true
  loadError.value = ''
  notVisible.value = false
  try {
    const result = await fetchOwnedPerformance(id)
    entity.value = result
    fillForm(result)
  } catch (error) {
    if (error instanceof ApiError && error.isNotFound) {
      // 他人的内容与不存在的编号都按不可见处理，不显示任何内容
      notVisible.value = true
    } else {
      loadError.value = error instanceof Error ? error.message : '加载失败'
    }
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  const raw = route.params.id
  performanceId.value = typeof raw === 'string' && raw !== '' ? raw : null
  void load()
})

/** 收集发布前还缺哪些必填项，用于给出明确提示（后端仍会独立校验） */
function missingFields(): string[] {
  const missing: string[] = []
  if (!form.title.trim()) missing.push('标题')
  if (!form.description.trim()) missing.push('介绍')
  if (!form.venue.trim()) missing.push('地点')
  if (!form.startsAtLocal.trim()) missing.push('开始时间')
  if (!form.priceYuan.trim()) missing.push('票价')
  if (!form.totalStock.trim()) missing.push('总库存')
  return missing
}

/** 把表单整理成请求体；返回 null 表示表单里有非法输入，已写入 formError */
function buildPayload(): SavePerformancePayload | null {
  formError.value = ''

  let priceCent: number | null = null
  if (form.priceYuan.trim() !== '') {
    const price = yuanToCent(form.priceYuan)
    if (!price.ok) {
      formError.value = `票价${describeParseFailure(price.reason, '')}：请输入最多两位小数的金额`
      return null
    }
    priceCent = price.value
  }

  let totalStock: number | null = null
  if (form.totalStock.trim() !== '') {
    const stock = parseStock(form.totalStock)
    if (!stock.ok) {
      formError.value = `总库存${describeParseFailure(stock.reason, '')}：请输入 1 到 1000000 之间的整数`
      return null
    }
    totalStock = stock.value
  }

  const payload: SavePerformancePayload = {
    title: form.title.trim() || null,
    description: form.description.trim() || null,
    coverUrl: form.coverUrl.trim() || null,
    venue: form.venue.trim() || null,
    startsAt: fromShanghaiInput(form.startsAtLocal),
    priceCent,
    totalStock,
  }

  // 已发布/已下架：关键字段一律提交后端当前值，保证「原样带回」。
  // 不依赖输入框里的回显值，避免时间被格式化后再往返产生秒级漂移。
  const locked = entity.value
  if (locked && locked.status !== 'DRAFT') {
    payload.title = locked.title
    payload.venue = locked.venue
    payload.startsAt = locked.startsAt
    payload.priceCent = locked.priceCent
    payload.totalStock = locked.totalStock
  }

  return payload
}

/**
 * 写请求失败后的统一处理。
 * 网络错误或超时意味着结果未知，此时**先查询当前状态**再让用户决定，
 * 不盲目重发，避免重复写入。
 */
async function handleWriteError(error: unknown, action: string): Promise<void> {
  if (error instanceof ApiError && error.status === 0) {
    ElMessage.warning(`${action}结果待确认，正在重新读取当前状态`)
    await load()
    return
  }
  ElMessage.error(error instanceof Error ? error.message : `${action}失败`)
}

/** 保存草稿。新建走 POST，已有编号走 PUT */
async function handleSave(): Promise<void> {
  if (submitting.value) return
  const payload = buildPayload()
  if (!payload) return

  submitting.value = true
  try {
    if (performanceId.value === null) {
      const created = await createDraft(payload)
      performanceId.value = created.id
      entity.value = created
      ElMessage.success('草稿已保存')
      // 换成编辑地址，后续操作针对这条已保存的草稿
      await router.replace({ name: 'performance-edit', params: { id: created.id } })
      fillForm(created)
    } else {
      const updated = await updatePerformance(performanceId.value, payload)
      entity.value = updated
      fillForm(updated)
      ElMessage.success('已保存')
    }
  } catch (error) {
    // 失败保留用户输入，不做自动重试
    await handleWriteError(error, '保存')
  } finally {
    submitting.value = false
  }
}

/** 保存并发布。新建时先落草稿再发布，发布失败仍保留草稿 */
async function handleSaveAndPublish(): Promise<void> {
  if (submitting.value) return
  const payload = buildPayload()
  if (!payload) return

  const missing = missingFields()
  if (missing.length > 0) {
    formError.value = `发布前还需要填写：${missing.join('、')}`
    return
  }

  submitting.value = true
  try {
    let id = performanceId.value
    if (id === null) {
      const created = await createDraft(payload)
      id = created.id
      performanceId.value = created.id
      entity.value = created
    } else {
      const updated = await updatePerformance(id, payload)
      entity.value = updated
    }

    const published = await publishPerformance(id)
    entity.value = published
    ElMessage.success('已发布')
    await router.replace({ name: 'performance-edit', params: { id } })
    fillForm(published)
  } catch (error) {
    // 发布失败（例如后端判定开始时间已过）时保留草稿，让用户改完再发
    await handleWriteError(error, '发布')
    if (performanceId.value !== null) {
      await router.replace({ name: 'performance-edit', params: { id: performanceId.value } })
      await load()
    }
  } finally {
    submitting.value = false
  }
}

/** 已保存草稿的发布入口 */
async function handlePublish(): Promise<void> {
  const id = performanceId.value
  if (id === null || submitting.value) return

  const missing = missingFields()
  if (missing.length > 0) {
    formError.value = `发布前还需要填写：${missing.join('、')}`
    return
  }

  try {
    await ElMessageBox.confirm(
      '发布后标题、地点、开始时间、价格与总库存将被锁定，不能再修改。确认发布？',
      '确认发布',
      { confirmButtonText: '发布', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }

  submitting.value = true
  try {
    // 先把当前编辑内容存下来，再发布，避免「改了没保存就发布」
    const payload = buildPayload()
    if (!payload) return
    await updatePerformance(id, payload)

    const published = await publishPerformance(id)
    entity.value = published
    fillForm(published)
    ElMessage.success('已发布')
  } catch (error) {
    await handleWriteError(error, '发布')
  } finally {
    submitting.value = false
  }
}

async function handleWithdraw(): Promise<void> {
  const id = performanceId.value
  if (id === null || submitting.value) return

  try {
    await ElMessageBox.confirm(
      '下架后不再出现在公开列表，且不能重新上架。已有订单不受影响。确认下架？',
      '确认下架',
      { confirmButtonText: '下架', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }

  submitting.value = true
  try {
    const withdrawn = await withdrawPerformance(id)
    entity.value = withdrawn
    fillForm(withdrawn)
    ElMessage.success('已下架')
  } catch (error) {
    await handleWriteError(error, '下架')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="page__head">
      <div>
        <p class="eyebrow">运营 / 演出</p>
        <h1 class="page__title">
          {{ performanceId === null ? '新建演出' : '编辑演出' }}
        </h1>
      </div>
      <RouterLink :to="{ name: 'my-performances' }">
        <el-button>返回我的发布</el-button>
      </RouterLink>
    </div>

    <!-- 他人的内容与不存在的编号统一按不可见处理，不显示任何演出信息 -->
    <el-result
      v-if="notVisible"
      icon="warning"
      title="演出不存在或不可见"
      sub-title="这条演出可能已被删除，或者属于其他运营人员。"
    >
      <template #extra>
        <RouterLink :to="{ name: 'my-performances' }">
          <el-button type="primary">返回我的发布</el-button>
        </RouterLink>
      </template>
    </el-result>

    <el-alert
      v-else-if="loadError"
      type="error"
      :title="loadError"
      :closable="false"
      show-icon
    >
      <template #default>
        <el-button size="small" :loading="loading" @click="load">重新加载</el-button>
      </template>
    </el-alert>

    <el-skeleton v-else-if="loading && entity === null" :rows="6" animated />

    <div v-else class="card">
      <div class="card__bar">
        <span class="faint">当前状态</span>
        <el-tag size="small" :type="isPublished ? 'success' : 'info'">{{ statusLabel }}</el-tag>
        <span v-if="isWithdrawn" class="faint">已下架，不能重新上架</span>
      </div>

      <el-alert
        v-if="isLocked"
        class="notice"
        type="info"
        :closable="false"
        show-icon
        title="已发布或已下架的演出只允许修改介绍与封面"
        description="标题、地点、开始时间、价格、总库存已锁定，提交时会按原值带回。"
      />

      <el-alert
        v-if="formError"
        class="notice"
        type="error"
        :title="formError"
        :closable="false"
        show-icon
      />

      <el-form label-position="top">
        <el-form-item label="标题">
          <el-input
            v-model="form.title"
            maxlength="100"
            show-word-limit
            placeholder="例如：本地演示音乐会"
            :disabled="isLocked || submitting"
          />
        </el-form-item>

        <el-form-item label="介绍">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="5"
            maxlength="10000"
            placeholder="纯文本介绍，换行会保留"
            :disabled="submitting"
          />
        </el-form-item>

        <el-form-item label="封面地址（可选，仅支持 http/https）">
          <el-input
            v-model="form.coverUrl"
            maxlength="1024"
            placeholder="https://..."
            :disabled="submitting"
          />
        </el-form-item>

        <el-form-item label="地点">
          <el-input
            v-model="form.venue"
            maxlength="200"
            placeholder="例如：上海市徐汇区演示音乐厅"
            :disabled="isLocked || submitting"
          />
        </el-form-item>

        <el-form-item>
          <template #label>
            开始时间
            <span class="faint">（北京时间，提交时带 +08:00）</span>
          </template>
          <el-date-picker
            v-model="form.startsAtLocal"
            type="datetime"
            format="YYYY-MM-DD HH:mm:ss"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="选择北京时间的开始时间"
            :disabled="isLocked || submitting"
          />
        </el-form-item>

        <div class="row">
          <el-form-item label="票价（元，最多两位小数）">
            <el-input
              v-model="form.priceYuan"
              placeholder="例如：99.00"
              :disabled="isLocked || submitting"
            />
          </el-form-item>

          <el-form-item label="总库存（整数）">
            <el-input
              v-model="form.totalStock"
              placeholder="例如：20"
              :disabled="isLocked || submitting"
            />
          </el-form-item>
        </div>
      </el-form>

      <div class="actions">
        <el-button :loading="submitting" :disabled="submitting" @click="handleSave">
          保存
        </el-button>

        <el-button
          v-if="isDraft"
          type="primary"
          :loading="submitting"
          :disabled="submitting"
          @click="performanceId === null ? handleSaveAndPublish() : handlePublish()"
        >
          发布
        </el-button>

        <el-button
          v-if="isPublished"
          :loading="submitting"
          :disabled="submitting"
          @click="handleWithdraw"
        >
          下架
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.card {
  padding: 20px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
}

.card__bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-bottom: 14px;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--line);
  font-size: 13px;
}

.notice {
  margin-bottom: 16px;
}

.row {
  display: flex;
  gap: 20px;
  flex-wrap: wrap;
}

.row > * {
  flex: 1 1 220px;
}

.actions {
  display: flex;
  gap: 10px;
  padding-top: 4px;
}
</style>
