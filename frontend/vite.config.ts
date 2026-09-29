import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

// 前端固定 5173 且 strictPort：端口被占用时直接失败，不静默换端口，
// 否则共同契约里「localhost:5173 代理 /api 到 localhost:8080」的约定会被悄悄破坏。
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      // 浏览器始终访问前端同源路径，Session Cookie 才能按同源规则正常往返
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'node',
    include: ['tests/**/*.spec.ts'],
  },
})
