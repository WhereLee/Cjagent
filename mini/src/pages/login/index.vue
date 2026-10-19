<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { miniLogin } from '@/api'
import {
  getDeviceCode,
  getSavedProfile,
  saveProfile,
  setToken,
  switchAccount,
} from '@/stores/auth'
import { toastError } from '@/utils/format'

const saved = getSavedProfile()
const form = reactive({
  tenantCode: saved.tenantCode,
  phone: saved.phone,
})
const loading = ref(false)
const expiredTip = ref(false)
const errorText = ref('')
/** 有上次资料就默认走一键登录；首次使用才展示注册表单 */
const returning = ref(Boolean(saved.phone))
const showDemo = ref(false)

const maskedPhone = computed(() => {
  const phone = form.phone
  return phone.length >= 7 ? `${phone.slice(0, 3)}****${phone.slice(-4)}` : phone
})

onLoad((query) => {
  expiredTip.value = String(query?.expired ?? '') === '1'
})

async function login(): Promise<void> {
  if (!form.phone.trim()) {
    uni.showToast({ title: '请填写手机号', icon: 'none' })
    return
  }
  if (!form.tenantCode.trim()) {
    uni.showToast({ title: '首次使用需要填写服务商编码', icon: 'none' })
    return
  }
  loading.value = true
  errorText.value = ''
  try {
    const view = await miniLogin({
      code: getDeviceCode(),
      tenantCode: form.tenantCode.trim(),
      phone: form.phone.trim(),
    })
    setToken(view.accessToken ?? '', view.refreshToken ?? '')
    saveProfile({ tenantCode: form.tenantCode.trim(), phone: form.phone.trim() })
    uni.showToast({ title: '登录成功', icon: 'success' })
    setTimeout(() => uni.switchTab({ url: '/pages/home/index' }), 400)
  } catch (e) {
    // 新设备但手机号已注册在别的租户/换过服务商时，后端会明确拒绝；此时展开表单让用户改
    returning.value = false
    errorText.value = e instanceof Error && e.message ? e.message : '登录失败，请稍后重试'
    toastError(e)
  } finally {
    loading.value = false
  }
}

async function onQuickLogin(): Promise<void> {
  await login()
}

function onSwitch(): void {
  switchAccount()
  returning.value = false
  form.tenantCode = ''
  form.phone = ''
}

function goPickup(): void {
  uni.navigateTo({ url: '/pages/pickup/index' })
}
</script>

<template>
  <view class="page login-page">
    <view class="brand">
      <view class="logo">柜</view>
      <text class="title-lg">智慧寄存柜</text>
      <text class="muted">存件、取件、查账单，一个入口</text>
    </view>

    <view v-if="expiredTip" class="card notice">登录已过期，请重新登录</view>

    <view v-if="returning" class="card">
      <view class="card-title">欢迎回来</view>
      <view class="muted phone">{{ maskedPhone }}</view>
      <button class="btn btn-primary btn-block action" :loading="loading" @click="onQuickLogin">
        一键登录
      </button>
      <button class="btn btn-plain btn-block" @click="onSwitch">切换账号</button>
    </view>

    <view v-else class="card">
      <view class="card-title">首次使用</view>
      <view class="muted intro">
        绑定手机号后即可存件取件。手机号同时用于柜机屏取件的尾号校验，请填写本人号码。
      </view>

      <view v-if="errorText" class="error-box">{{ errorText }}</view>

      <view class="field">
        <text class="field-label">手机号</text>
        <input
          v-model="form.phone"
          class="input"
          type="tel"
          maxlength="11"
          placeholder="11 位手机号"
          placeholder-class="input-placeholder"
        />
      </view>

      <view class="field">
        <text class="field-label">服务商编码</text>
        <input
          v-model="form.tenantCode"
          class="input"
          placeholder="本地演示填 platform"
          placeholder-class="input-placeholder"
        />
      </view>

      <button class="btn btn-primary btn-block" :loading="loading" @click="login">
        登录 / 注册
      </button>

      <view class="demo-toggle" @click="showDemo = !showDemo">
        {{ showDemo ? '收起演示说明' : '演示登录说明 ›' }}
      </view>
      <view v-if="showDemo" class="demo-note">
        当前后端开启了 dev 模拟登录：本机保存的 code 就是账号身份，换一个 code 等于换一个人。
        点“切换账号”会重新生成本机身份。正式接入微信登录后，这一层由 wx.login 替换，页面交互不变。
      </view>
    </view>

    <button class="btn btn-plain pickup-link" @click="goPickup">
      手机没电？凭取件码取件 ›
    </button>
  </view>
</template>

<style scoped>
.login-page {
  padding: 72rpx 40rpx 48rpx;
}

.brand {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  margin-bottom: 56rpx;
}

.logo {
  width: 96rpx;
  height: 96rpx;
  border-radius: 28rpx;
  background: linear-gradient(135deg, #2b6de5, #5b8ff0);
  color: #ffffff;
  font-size: 44rpx;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 24rpx;
}

.brand .muted {
  margin-top: 10rpx;
}

.notice {
  background: #fdf3e7;
  color: #a86413;
  font-size: 26rpx;
}

.phone {
  margin: 12rpx 0 28rpx;
}

.action {
  margin-bottom: 12rpx;
}

.intro {
  margin: 12rpx 0 28rpx;
  line-height: 1.7;
}

.error-box {
  margin: 0 0 20rpx;
  padding: 16rpx 18rpx;
  background: #fdeeea;
  border-radius: 10rpx;
  color: #b4471f;
  font-size: 25rpx;
  line-height: 1.6;
}

.demo-toggle {
  margin-top: 24rpx;
  text-align: center;
  color: #98a2b3;
  font-size: 23rpx;
}

.demo-note {
  margin-top: 14rpx;
  padding: 16rpx 18rpx;
  background: #f7f8fa;
  border-radius: 10rpx;
  color: #6b7684;
  font-size: 23rpx;
  line-height: 1.7;
}

.pickup-link {
  margin-top: 40rpx;
}
</style>
