import { createPinia } from 'pinia'
import { createSSRApp } from 'vue'

import App from './App.vue'

/**
 * uni-app 的入口是工厂函数而不是直接 mount：框架要在里面注入平台差异
 * （生命周期、页面栈、条件编译），所以 createApp 必须导出、必须返回 { app }。
 *
 * <p>这里不装 router、不做路由守卫：小程序没有 vue-router，鉴权拦在页面 onLoad
 * 与后端 401 两处，具体见 utils/authz.ts。
 */
export function createApp() {
  const app = createSSRApp(App)
  app.use(createPinia())
  return { app }
}
