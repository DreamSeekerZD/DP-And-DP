<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

/**
 * 表单只保留用户名与密码两个字段。
 * 登录失败时**只清密码、保留用户名**，并且密码不写入任何持久化存储。
 */
const form = reactive({ username: '', password: '' })
const submitting = ref(false)
const errorMessage = ref('')

function redirectTarget(): string {
  const redirect = route.query.redirect
  return typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/performance'
}

async function handleSubmit(): Promise<void> {
  if (submitting.value) return

  errorMessage.value = ''
  if (!form.username.trim() || !form.password) {
    errorMessage.value = '请填写用户名和密码'
    return
  }

  submitting.value = true
  try {
    await auth.login(form.username, form.password)
    // 登录成功后立刻清掉本地密码，避免停留在内存里
    form.password = ''
    ElMessage.success('登录成功')
    await router.replace(redirectTarget())
  } catch (error) {
    // 失败保留用户名，只清密码；错误只在这里显示一次
    form.password = ''
    errorMessage.value = error instanceof Error ? error.message : '登录失败，请重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="page login">
    <div class="login__card">
      <p class="eyebrow">演出票务</p>
      <h1 class="page__title">登录</h1>
      <p class="muted login__hint">
        演示账号见 <code>backend/sql/README.md</code>，密码由该文件提供，不在前端保存。
      </p>

      <el-alert
        v-if="errorMessage"
        class="login__alert"
        type="error"
        :title="errorMessage"
        :closable="false"
        show-icon
      />

      <el-form label-position="top" @submit.prevent="handleSubmit">
        <el-form-item label="用户名">
          <el-input
            v-model="form.username"
            name="username"
            autocomplete="username"
            placeholder="请输入用户名"
            :disabled="submitting"
            @keyup.enter="handleSubmit"
          />
        </el-form-item>

        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            name="password"
            type="password"
            autocomplete="current-password"
            placeholder="请输入密码"
            show-password
            :disabled="submitting"
            @keyup.enter="handleSubmit"
          />
        </el-form-item>

        <el-button
          class="login__submit"
          type="primary"
          native-type="submit"
          :loading="submitting"
          :disabled="submitting"
        >
          登录
        </el-button>
      </el-form>

      <RouterLink class="login__back" :to="{ name: 'performance-list' }">
        不登录，先浏览演出
      </RouterLink>
    </div>
  </div>
</template>

<style scoped>
.login {
  display: flex;
  justify-content: center;
}

.login__card {
  width: 100%;
  max-width: 380px;
  padding: 28px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
}

.login__hint {
  margin: 8px 0 20px;
  font-size: 13px;
}

.login__hint code {
  padding: 1px 4px;
  border-radius: 3px;
  background: var(--paper);
  font-size: 12px;
}

.login__alert {
  margin-bottom: 16px;
}

.login__submit {
  width: 100%;
}

.login__back {
  display: inline-block;
  margin-top: 16px;
  color: var(--ink-soft);
  font-size: 13px;
}
</style>
