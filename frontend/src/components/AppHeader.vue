<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const loggingOut = ref(false)

async function handleLogout(): Promise<void> {
  if (loggingOut.value) return
  loggingOut.value = true
  try {
    await auth.logout()
    ElMessage.success('已退出登录')
    await router.push({ name: 'performance-list' })
  } catch (error) {
    // 退出接口失败也已经在 store 里清掉了本地身份，如实说明即可
    ElMessage.error(error instanceof Error ? error.message : '退出失败')
  } finally {
    loggingOut.value = false
  }
}
</script>

<template>
  <header class="masthead">
    <div class="masthead__inner">
      <RouterLink class="masthead__brand" :to="{ name: 'performance-list' }">
        <span class="masthead__mark" aria-hidden="true">票</span>
        <span class="masthead__name">演出票务</span>
      </RouterLink>

      <nav class="masthead__nav">
        <RouterLink class="masthead__link" :to="{ name: 'performance-list' }">演出</RouterLink>
        <!-- 运营入口只对运营角色显示；这不是授权，后端仍会独立校验 -->
        <RouterLink
          v-if="auth.isOperator"
          class="masthead__link"
          :to="{ name: 'my-performances' }"
        >
          我的发布
        </RouterLink>
        <!-- 普通用户入口：我的订单 -->
        <RouterLink v-if="auth.isUser" class="masthead__link" :to="{ name: 'my-orders' }">
          我的订单
        </RouterLink>
      </nav>

      <div class="masthead__account">
        <template v-if="auth.isLoggedIn">
          <span class="masthead__who">
            <span class="num">{{ auth.user?.username }}</span>
            <span class="masthead__role">{{ auth.isOperator ? '运营' : '用户' }}</span>
          </span>
          <el-button
            text
            :loading="loggingOut"
            :disabled="loggingOut"
            @click="handleLogout"
          >
            退出
          </el-button>
        </template>
        <RouterLink v-else class="masthead__link" :to="{ name: 'login' }">登录</RouterLink>
      </div>
    </div>
  </header>
</template>

<style scoped>
.masthead {
  background: var(--ink);
  color: #fff;
}

.masthead__inner {
  display: flex;
  align-items: center;
  gap: 24px;
  max-width: var(--page-max);
  margin: 0 auto;
  padding: 0 20px;
  height: 56px;
}

.masthead__brand {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #fff;
  text-decoration: none;
  font-weight: 700;
}

.masthead__mark {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: 4px;
  background: var(--seal);
  font-size: 13px;
}

.masthead__name {
  letter-spacing: 0.04em;
}

.masthead__nav {
  display: flex;
  gap: 4px;
  flex: 1;
}

.masthead__link {
  padding: 6px 10px;
  border-radius: var(--radius);
  color: rgb(255 255 255 / 78%);
  text-decoration: none;
  font-size: 14px;
}

.masthead__link:hover {
  background: rgb(255 255 255 / 10%);
  color: #fff;
}

.masthead__link.router-link-exact-active {
  background: rgb(255 255 255 / 14%);
  color: #fff;
}

.masthead__account {
  display: flex;
  align-items: center;
  gap: 10px;
}

.masthead__who {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
}

.masthead__role {
  padding: 1px 6px;
  border-radius: 3px;
  background: rgb(255 255 255 / 16%);
  font-size: 12px;
}

.masthead__account :deep(.el-button) {
  color: rgb(255 255 255 / 82%);
}

.masthead__account :deep(.el-button:hover) {
  color: #fff;
  background: rgb(255 255 255 / 10%);
}
</style>
