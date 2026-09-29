<script setup lang="ts">
/**
 * 兜底页面。
 *
 * 同时承担两种「看不到」的情形：路径不存在，以及已登录的非运营人员访问运营路由。
 * 两种情况都不透露目标页面是否存在，措辞统一。
 */
</script>

<template>
  <div class="page not-found">
    <p class="eyebrow">404</p>
    <h1 class="page__title">页面不存在或不可见</h1>
    <p class="muted">
      这个地址可能已经失效，或者当前账号看不到它。
    </p>
    <RouterLink :to="{ name: 'performance-list' }">
      <el-button type="primary">返回演出列表</el-button>
    </RouterLink>
  </div>
</template>

<style scoped>
.not-found {
  max-width: 560px;
}

.not-found p {
  margin: 0 0 20px;
}
</style>
