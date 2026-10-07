import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { ElLoading } from 'element-plus'
// 指令不走组件解析器（它只能识别模板里的 <ElXxx>），v-loading 必须自己注册并自引样式，
// 否则表格加载态不报错也不生效——这类“静默失效”比报错更难查
import 'element-plus/es/components/loading/style/css'

import App from './App.vue'
import router from './router'
import './styles/index.css'

import { bindSession } from './api/http'
import { useAuthStore } from './stores/auth'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)

/**
 * http 层与登录态在这里接线。
 *
 * 必须放在 pinia 装好之后、app.use(router) 之前：路由守卫第一次导航就会调接口，
 * 若此时 bridge 还没绑上，请求不带 token，表现是"登录后第一次刷新又被踢回登录页"。
 */
const auth = useAuthStore(pinia)
bindSession({
  accessToken: () => auth.accessToken,
  refreshToken: () => auth.refreshToken,
  applyTokens: (access, refresh) => auth.setTokens(access, refresh),
  forceLogout: () => {
    auth.clear()
    void router.replace({ name: 'login' })
  },
})

app.use(router)
app.directive('loading', ElLoading.directive)
// 组件与语言包都是按需引入（见 vite.config.ts），不再 app.use(ElementPlus) 整包注册
// 语言环境由 App.vue 里的 <ElConfigProvider> 提供
app.mount('#app')
