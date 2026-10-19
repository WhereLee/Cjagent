import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

// dev 走 proxy 转发 /api 到本地后端：不依赖后端 CORS 配置（H5 与小程序 dev 均生效）
export default defineConfig({
  plugins: [uni()],
  server: {
    // 显式监听所有本地地址：Windows 上 localhost 常只解析到 ::1，
    // 不设 host 时 127.0.0.1 会直接连接被拒。
    host: true,
    port: 8083,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
})
