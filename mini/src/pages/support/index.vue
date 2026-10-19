<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { supportChat } from '@/api'
import { isLoggedIn } from '@/stores/auth'
import { toastError } from '@/utils/format'

interface ChatItem {
  role: 'user' | 'agent'
  text: string
  escalated?: boolean
  ticketNo?: string
}

const orderNo = ref('')
const message = ref('')
const sending = ref(false)
const chats = ref<ChatItem[]>([])
const scrollTarget = ref('')

const quickQuestions = [
  '取件码在哪里看？',
  '账单为什么有欠费？',
  '柜门打不开怎么办？',
  '怎么提前结束订单？',
]

onLoad((query) => {
  orderNo.value = String(query?.orderNo ?? '')
  chats.value.push({
    role: 'agent',
    text: orderNo.value
      ? `你好，我在看订单 ${orderNo.value}。可以问我账单、取件或柜门问题；需要人工处理时会自动建工单。`
      : '你好，我是智能客服。可以问我取件、账单、柜门问题；需要人工处理时会自动建工单。',
  })
})

function scrollToBottom(): void {
  nextTick(() => {
    scrollTarget.value = `msg-${chats.value.length - 1}`
  })
}

async function send(text: string): Promise<void> {
  if (!text || sending.value) return
  chats.value.push({ role: 'user', text })
  message.value = ''
  sending.value = true
  scrollToBottom()
  try {
    const reply = await supportChat({ orderNo: orderNo.value || undefined, message: text })
    chats.value.push({
      role: 'agent',
      text: reply.reply ?? '我暂时没有拿到回答，请稍后再试。',
      escalated: reply.escalated,
      ticketNo: reply.ticketNo,
    })
  } catch (e) {
    chats.value.push({
      role: 'agent',
      text: '这条消息没有发出去，请检查网络后再试。',
    })
    toastError(e)
  } finally {
    sending.value = false
    scrollToBottom()
  }
}

function onSend(): void {
  send(message.value.trim())
}

function onQuick(text: string): void {
  send(text)
}
</script>

<template>
  <view class="page chat-page">
    <view v-if="!isLoggedIn()" class="card login-tip">
      请先登录后使用 AI 客服。
    </view>

    <scroll-view class="chat-area" scroll-y :scroll-into-view="scrollTarget" scroll-with-animation>
      <view v-for="(item, index) in chats" :id="`msg-${index}`" :key="index" class="bubble-row" :class="item.role">
        <view class="bubble" :class="item.role">
          <text class="bubble-text">{{ item.text }}</text>
          <view v-if="item.escalated" class="ticket">
            已为你创建工单 {{ item.ticketNo ?? '' }}，工作人员会跟进处理
          </view>
        </view>
      </view>

      <view v-if="sending" class="bubble-row agent">
        <view class="bubble agent dim">正在思考…</view>
      </view>

      <view v-if="chats.length <= 1" class="quick-list">
        <text class="quick-title">你可以这样问</text>
        <view v-for="question in quickQuestions" :key="question" class="quick-item" @click="onQuick(question)">
          {{ question }}
        </view>
      </view>

      <view class="chat-bottom" />
    </scroll-view>

    <view class="input-bar">
      <input
        v-model="message"
        class="input message-input"
        maxlength="500"
        placeholder="描述你的问题（最多 500 字）"
        placeholder-class="input-placeholder"
        confirm-type="send"
        @confirm="onSend"
      />
      <button class="btn btn-primary send-btn" :loading="sending" @click="onSend">发送</button>
    </view>
  </view>
</template>

<style scoped>
.chat-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  padding: 20rpx 20rpx 0;
  box-sizing: border-box;
}

.login-tip {
  background: #fdf3e7;
  color: #a86413;
  font-size: 26rpx;
}

.chat-area {
  flex: 1;
  min-height: 0;
}

.bubble-row {
  display: flex;
  margin-bottom: 20rpx;
}

.bubble-row.user {
  justify-content: flex-end;
}

.bubble-row.agent {
  justify-content: flex-start;
}

.bubble {
  max-width: 80%;
  padding: 20rpx 24rpx;
  border-radius: 16rpx;
  font-size: 27rpx;
  line-height: 1.7;
}

.bubble-text {
  white-space: pre-wrap;
}

.bubble.user {
  background: #2b6de5;
  color: #ffffff;
  border-bottom-right-radius: 6rpx;
}

.bubble.agent {
  background: #ffffff;
  color: #1f2733;
  border-bottom-left-radius: 6rpx;
}

.bubble.dim {
  color: #98a2b3;
}

.ticket {
  margin-top: 12rpx;
  padding: 10rpx 14rpx;
  background: #ecf6e8;
  color: #3d8b2f;
  border-radius: 8rpx;
  font-size: 23rpx;
}

.quick-list {
  margin-top: 8rpx;
  padding: 0 4rpx;
}

.quick-title {
  display: block;
  color: #98a2b3;
  font-size: 23rpx;
  margin-bottom: 12rpx;
}

.quick-item {
  display: inline-block;
  margin: 0 12rpx 12rpx 0;
  padding: 12rpx 20rpx;
  background: #ffffff;
  border-radius: 10rpx;
  color: #2b6de5;
  font-size: 24rpx;
}

.chat-bottom {
  height: 24rpx;
}

.input-bar {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 16rpx 0 calc(16rpx + env(safe-area-inset-bottom));
  border-top: 1px solid #eef1f5;
  background: #f4f6f9;
}

.message-input {
  flex: 1;
  background: #ffffff;
}

.send-btn {
  width: 160rpx;
  font-size: 27rpx;
  line-height: 2.9;
}
</style>
