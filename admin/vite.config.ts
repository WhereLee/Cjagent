import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// dev 走 proxy 转发 /api 到本地后端：后端 CORS 白名单不含前端端口，
// 跨域问题在 dev server 层解决，不改后端任何配置。
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // 显式监听所有本地地址：Windows 上 localhost 常只解析到 ::1，
    // 不设 host 时 127.0.0.1 会直接连接被拒。
    host: true,
    port: 8082,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        manualChunks: {
          'vendor-vue': ['vue', 'vue-router', 'pinia'],
          'vendor-element': ['element-plus', '@element-plus/icons-vue'],
          'vendor-http': ['axios'],
        },
      },
    },
  },
})
