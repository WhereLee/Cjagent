import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { defineConfig, loadEnv } from 'vite'

/**
 * 本地端口用 8082，**不是 8081也不是 Vite 默认 5173**：
 * 8081 已被本机 RocketMQ 5 的 proxy 组件占用（监听 ::8081，现象是 EACCES 而非 EADDRINUSE）。
 * 同时后端 dev 的 CORS 白名单必须放同一个源（见 application-dev.yml），
 * 两边只改一侧的症状是“登录接口报 CORS”，归因很不直观。
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const port = Number(env.VITE_DEV_PORT ?? 8082)

  return {
    plugins: [
      vue(),
      /**
       * 组件按需引入（Element Plus 官方推荐）。
       *
       * 之前整包 app.use(ElementPlus) 使一个 vendor 超过 1MB：除了首屏下载，
       * 业务改一行也会弄脏整个包。自动导出的 components.d.ts / auto-imports.d.ts
       * 要**提交进仓**：typecheck 在首次构建前就要看到它们。
       */
      AutoImport({ resolvers: [ElementPlusResolver()], dts: 'src/auto-imports.d.ts' }),
      Components({ resolvers: [ElementPlusResolver()], dts: 'src/components.d.ts' }),
    ],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port,
      strictPort: true, // 端口被占就报错，不要静默换端口——那会让 CORS 白名单突然失配
      host: '127.0.0.1',
    },
    build: {
      sourcemap: mode !== 'production',
      /**
       * 阈值按实测数据定，不是调大就完事。
       *
       * 按需引入后的实际分布：vendor-element 795KB（≈0.81MB，已是该尺寸下限——
       * 用了 Table/Pagination/Form/Select 这类重组件，拆包拆不开它），
       * vendor-vue 109KB，业务 chunk 均 2~9KB。
       * 之前整包引入时：element 1.1MB + CSS 361KB；现在 CSS 降到 120KB。
       * 真正能再降的手段是换轻量组件库或去掉表格，那是产品决策不是构建技巧。
       */
      chunkSizeWarningLimit: 900,
      rollupOptions: {
        output: {
          /**
           * 把体积大且很少变的依赖单独分包。
           *
           * 不分包时业务代码改一行也会弄脏整个 vendor（用户重下 1.1MB），
           * 拆成 element-plus / vue 后业务 chunk 很小，缓存命中率明显提升。
           */
          manualChunks(id: string) {
            if (id.includes('node_modules')) {
              if (id.includes('element-plus') || id.includes('@element-plus')) return 'vendor-element'
              if (id.includes('/vue/') || id.includes('@vue/') || id.includes('vue-router') || id.includes('pinia')) {
                return 'vendor-vue'
              }
              if (id.includes('axios')) return 'vendor-http'
              return 'vendor-misc'
            }
            return undefined
          },
        },
      },
    },
  }
})
