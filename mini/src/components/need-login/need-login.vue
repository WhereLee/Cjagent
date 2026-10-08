<script setup lang="ts">
/**
 * 未登录 / 拿不到身份时的兜底块。
 *
 * <p>为什么要有这个组件：页面鉴权靠 `reLaunch` 回登录页，但 H5 上存在
 * "上一跳过渡未完成时 reLaunch 被丢弃"的竞态（隐藏标签页 rAF 冻结、300ms 内连发导航都能触发，
 * 浏览器实测到）。只渲染 `v-if="ready"` 的空 false 分支会得到"地址栏是受限页 + 整页空白"，
 * 用户只能猜。给一块能主动点走的说明，就不把正确性完全押在导航成功上。
 *
 * <p>目录结构 `components/need-login/need-login.vue` 是 uni-app 的 easycom 约定，
 * 模板里直接写 `<need-login />` 即可，不需要 import。
 */
function goLogin(): void {
  uni.reLaunch({ url: '/pages/login/index', animationDuration: 0 })
}
</script>

<template>
  <view class="card">
    <view class="title">请先登录</view>
    <view class="hint">登录状态已失效，或暂时无法确认你的身份。点下面的按钮重新登录。</view>
    <button class="btn btn-primary" @click="goLogin">去登录</button>
  </view>
</template>
