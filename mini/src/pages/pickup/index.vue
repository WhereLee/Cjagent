<script setup lang="ts">
import { reactive, ref } from 'vue'
import { pickupByVoucher } from '@/api'
import type { StorageOrderView } from '@/api/schema-mini'
import { orderStatusText } from '@/utils/dict'

const form = reactive({ voucherCode: '', phoneTail: '' })
const loading = ref(false)
const error = ref('')
const result = ref<StorageOrderView | null>(null)

function validate(): boolean {
  if (!form.voucherCode.trim()) {
    error.value = '请填写取件码'
    return false
  }
  if (!/^\d{4}$/.test(form.phoneTail.trim())) {
    error.value = '手机号后四位应为 4 位数字'
    return false
  }
  return true
}

async function onSubmit(): Promise<void> {
  error.value = ''
  result.value = null
  if (!validate()) return
  loading.value = true
  try {
    result.value = await pickupByVoucher({
      voucherCode: form.voucherCode.trim(),
      phoneTail: form.phoneTail.trim(),
    })
    uni.showToast({ title: '柜门已打开，请取物', icon: 'success' })
  } catch (e) {
    error.value = e instanceof Error && e.message ? e.message : '取件失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function goLogin(): void {
  uni.reLaunch({ url: '/pages/login/index' })
}
</script>

<template>
  <view class="page">
    <view class="card">
      <view class="title">凭取件码取件</view>
      <view class="muted intro">
        手机不在手上也能取件：输入取件码 + 手机号后四位。连续错 5 次会锁定开柜；锁定后可登录后在订单详情里重置取件码。
      </view>

      <view class="field first-field">
        <text class="field-label">取件码</text>
        <input
          v-model="form.voucherCode"
          class="input code-input"
          placeholder="存件后获得的取件码"
          placeholder-class="input-placeholder"
        />
      </view>

      <view class="field">
        <text class="field-label">手机号后四位</text>
        <input
          v-model="form.phoneTail"
          class="input"
          type="number"
          maxlength="4"
          placeholder="四位数字"
          placeholder-class="input-placeholder"
        />
      </view>

      <view v-if="error" class="error-box">{{ error }}</view>

      <button class="btn btn-primary btn-block submit-btn" :loading="loading" @click="onSubmit">
        开柜取件
      </button>
    </view>

    <view v-if="result" class="card result-card">
      <view class="row">
        <text class="card-title">柜门已打开</text>
        <text class="chip chip-success">{{ orderStatusText(result.status) }}</text>
      </view>
      <view class="slot-row">
        <text class="slot">{{ result.slotNo ?? '—' }}</text>
        <text class="muted">号格</text>
      </view>
      <view class="muted">柜机 {{ result.cabinetNo ?? '—' }}</view>
      <view class="muted tip">请取走物品并关好柜门。订单 {{ result.orderNo ?? '—' }} 已完成结算。</view>
      <view v-if="result.message" class="message-box">{{ result.message }}</view>
    </view>

    <view class="login-row">
      <text class="muted">手机在手上？</text>
      <text class="link" @click="goLogin">登录后直接取件 ›</text>
    </view>
  </view>
</template>

<style scoped>
.intro {
  margin-top: 12rpx;
  line-height: 1.8;
}

.first-field {
  margin-top: 28rpx;
}

.code-input {
  letter-spacing: 2rpx;
}

.error-box {
  margin: 8rpx 0 16rpx;
  padding: 16rpx 18rpx;
  background: #fdeeea;
  border-radius: 10rpx;
  color: #b4471f;
  font-size: 25rpx;
  line-height: 1.6;
}

.submit-btn {
  margin-top: 12rpx;
}

.result-card {
  border: 2rpx solid #cfe8c5;
  background: #fbfef9;
}

.slot-row {
  display: flex;
  align-items: baseline;
  gap: 10rpx;
  margin: 20rpx 0 6rpx;
}

.slot {
  font-size: 76rpx;
  font-weight: 700;
  color: #3d8b2f;
  line-height: 1.1;
}

.tip {
  margin-top: 14rpx;
  line-height: 1.7;
}

.message-box {
  margin-top: 14rpx;
  padding: 14rpx 18rpx;
  background: #ffffff;
  border-radius: 10rpx;
  color: #4a5562;
  font-size: 24rpx;
  line-height: 1.7;
}

.login-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8rpx;
  margin-top: 24rpx;
}

.link {
  color: #2b6de5;
  font-size: 25rpx;
}
</style>
