<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'

// 启动不做业务判定：登录态由各页面进入时校验，避免首页与 tab 页互相抢跳。
onLaunch(() => {})
</script>

<style>
@import './styles/common.css';
</style>
