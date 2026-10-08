import { fileURLToPath, URL } from 'node:url'

import uni from '@dcloudio/vite-plugin-uni'
import { defineConfig } from 'vite'

/**
 * 端口用 8083：8080 是后端、8081 被本机 RocketMQ 5 的 proxy 占（抢它会得到 EACCES）、
 * 8082 是 admin。四个端口都写进 `docs/架构约定.md`，后端 dev 的 CORS 白名单要同步放行，
 * 只改一侧的症状是"请求被 CORS 拦掉"，很难归因。
 */
const DEV_PORT = 8083

export default defineConfig({
  plugins: [uni()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: '127.0.0.1',
    port: DEV_PORT,
    strictPort: true,
  },
})
