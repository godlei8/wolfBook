<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import assistantApi from '../../services/assistant'
import { buildChatViewportState } from './chat-state.mjs'
import { requireAuth } from '../../utils/auth'

const inputValue = ref('')
const scrollIntoView = ref('')
const chatScrollTop = ref(0)
const loading = ref(false)
const sending = ref(false)
const sessionId = ref('')
const bootstrap = ref({
  enabled: true,
  knowledgeBaseEnabled: true,
  webSearchEnabled: false,
  postgresReady: false,
  chatReady: false,
  currentMode: 'RAG',
  unavailableReason: '',
  quickQuestions: ['12人进阶有什么板子？', '女巫能不能自救？', '守卫和女巫会不会冲突？'],
})

const messages = ref([])

const canSend = computed(() => inputValue.value.trim().length > 0 && !sending.value)
const quickQuestions = computed(() => bootstrap.value.quickQuestions || [])

function toBadge(answerType) {
  switch (answerType) {
    case 'RAG_ANSWER':
      return '知识库回答'
    case 'WEB_AUGMENTED_ANSWER':
      return '联网增强'
    case 'WEB_ONLY_ANSWER':
      return '联网回答'
    case 'CLARIFICATION':
      return '需要补充'
    case 'NO_EVIDENCE':
      return '证据不足'
    case 'OUT_OF_SCOPE':
      return '范围限制'
    case 'ERROR':
      return '边界提示'
    default:
      return 'AI回答'
  }
}

function createMessage(role, content, extra = {}) {
  return {
    id: `${role.toLowerCase()}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    role,
    content,
    createTime: new Date().toISOString(),
    answerType: extra.answerType || null,
    sources: extra.sources || [],
  }
}

function bootstrapNotice() {
  if (!bootstrap.value.enabled) {
    return 'AI 助手当前已关闭，请稍后再试。'
  }
  if (bootstrap.value.unavailableReason) {
    return bootstrap.value.unavailableReason
  }
  if (!bootstrap.value.knowledgeBaseEnabled) {
    return bootstrap.value.webSearchEnabled
      ? '当前模式：联网回答（本地知识库已关闭）'
      : '当前模式不可用：知识库已关闭且联网搜索不可用'
  }
  return '当前模式：知识库问答'
}

function seedWelcome() {
  messages.value = [
    createMessage('ASSISTANT', bootstrapNotice(), {
      answerType: bootstrap.value.enabled ? 'CLARIFICATION' : 'ERROR',
    }),
  ]
}

function scrollToBottom() {
  nextTick(() => {
    const latest = messages.value[messages.value.length - 1]
    const nextViewport = buildChatViewportState('append', latest?.id, chatScrollTop.value)
    chatScrollTop.value = nextViewport.scrollTop
    scrollIntoView.value = nextViewport.scrollIntoView
  })
}

function resetConversationViewport() {
  const nextViewport = buildChatViewportState('reset', null, chatScrollTop.value)
  chatScrollTop.value = nextViewport.scrollTop
  scrollIntoView.value = nextViewport.scrollIntoView
}

function handleChatScroll(event) {
  chatScrollTop.value = event?.detail?.scrollTop ?? chatScrollTop.value
}

async function loadHistory() {
  try {
    const sessions = await assistantApi.listSessions()
    if (!sessions || !sessions.length) {
      seedWelcome()
      resetConversationViewport()
      return
    }
    sessionId.value = sessions[0].sessionId
    const history = await assistantApi.listMessages(sessionId.value)
    if (!history || !history.length) {
      seedWelcome()
      resetConversationViewport()
      return
    }
    messages.value = history.map((item) =>
      createMessage(item.role === 'USER' ? 'USER' : 'ASSISTANT', item.content, {
        answerType: item.answerType || null,
        sources: item.sources || [],
      }),
    )
  } catch (error) {
    seedWelcome()
    resetConversationViewport()
  }
}

async function initialize() {
  if (!requireAuth({ title: '请先登录后使用 AI 助手', redirect: true, delay: 300 })) {
    return
  }
  loading.value = true
  try {
    bootstrap.value = await assistantApi.bootstrap()
  } catch (error) {
    bootstrap.value.unavailableReason = '初始化失败，请稍后重试。'
  } finally {
    loading.value = false
  }
  await loadHistory()
  scrollToBottom()
}

async function sendMessage() {
  const content = inputValue.value.trim()
  if (!content || sending.value) {
    return
  }

  messages.value.push(createMessage('USER', content))
  inputValue.value = ''
  scrollToBottom()

  sending.value = true
  const assistantMessage = createMessage('ASSISTANT', '', {
    answerType: 'AI回答',
    sources: [],
  })
  messages.value.push(assistantMessage)
  scrollToBottom()

  try {
    const response = await assistantApi.askStream(
      {
        sessionId: sessionId.value || undefined,
        question: content,
      },
      {
        onStart(event) {
          sessionId.value = event?.sessionId || sessionId.value
          assistantMessage.id = event?.messageId || assistantMessage.id
          assistantMessage.answerType = event?.answerType || assistantMessage.answerType
          assistantMessage.sources = event?.sources || []
          scrollToBottom()
        },
        onDelta(delta) {
          assistantMessage.content += delta || ''
          scrollToBottom()
        },
        onDone(event) {
          sessionId.value = event?.sessionId || sessionId.value
          assistantMessage.id = event?.messageId || assistantMessage.id
          assistantMessage.content = event?.answer || assistantMessage.content || '暂时没有拿到回答。'
          assistantMessage.answerType = event?.answerType || assistantMessage.answerType
          assistantMessage.sources = event?.sources || assistantMessage.sources || []
          scrollToBottom()
        },
        onError(event) {
          assistantMessage.content = event?.message || '请求失败，请稍后再试。'
          assistantMessage.answerType = 'ERROR'
          assistantMessage.sources = []
          scrollToBottom()
        },
      },
    )
    sessionId.value = response?.sessionId || sessionId.value
  } catch (error) {
    assistantMessage.content = error?.message || '请求失败，请稍后再试。'
    assistantMessage.answerType = 'ERROR'
    assistantMessage.sources = []
  } finally {
    sending.value = false
    scrollToBottom()
  }
}

function askQuickQuestion(question) {
  inputValue.value = question
  sendMessage()
}

function clearMessages() {
  sessionId.value = ''
  seedWelcome()
  resetConversationViewport()
}

onMounted(() => {
  void initialize()
})
</script>

<template>
  <view class="assistant-page">
    <view class="assistant-shell">
      <view class="glass-card assistant-header">
        <view class="assistant-header-kicker">WOLFBOOK AI</view>
        <view class="assistant-header-main">
          <view class="assistant-avatar">
            <view class="assistant-avatar-eyes">
              <view />
              <view />
            </view>
            <text>AI</text>
          </view>
          <view class="assistant-header-copy">
            <text class="assistant-header-title">狼人杀 AI 助手</text>
            <text class="assistant-header-desc">知识库 + 联网检索闭环问答</text>
          </view>
        </view>
        <view class="assistant-header-actions">
          <view class="assistant-status-pill">{{ bootstrap.currentMode || 'RAG' }}</view>
          <button class="reset-button" @tap="clearMessages">新会话</button>
        </view>
      </view>

      <view class="maintenance-banner">
        <text class="maintenance-title">模式提示</text>
        <text class="maintenance-desc">{{ bootstrapNotice() }}</text>
      </view>

      <scroll-view
        class="assistant-chat-scroll"
        scroll-y
        :scroll-top="chatScrollTop"
        :scroll-into-view="scrollIntoView"
        :scroll-with-animation="true"
        @scroll="handleChatScroll"
      >
        <view class="assistant-chat-content">
          <view
            v-for="item in messages"
            :id="`msg-${item.id}`"
            :key="item.id"
            class="message-row"
            :class="{ 'message-row--user': item.role === 'USER' }"
          >
            <view class="message-avatar" :class="{ 'message-avatar--assistant': item.role === 'ASSISTANT' }">
              {{ item.role === 'USER' ? '我' : 'AI' }}
            </view>
            <view class="glass-card message-bubble" :class="{ 'message-bubble--assistant': item.role === 'ASSISTANT' }">
              <view v-if="item.role === 'ASSISTANT'" class="answer-badge">{{ toBadge(item.answerType) }}</view>
              <text class="message-text">{{ item.content }}</text>
              <view v-if="item.role === 'ASSISTANT' && item.sources && item.sources.length" class="source-list">
                <view v-for="source in item.sources.slice(0, 3)" :key="source.chunkUid || source.title" class="source-item">
                  <text class="source-title">{{ source.title }}</text>
                  <text class="source-content">{{ source.content }}</text>
                </view>
              </view>
            </view>
          </view>

          <view class="quick-panel">
            <text class="quick-title">你可以这样问我</text>
            <button
              v-for="question in quickQuestions"
              :key="question"
              class="quick-question"
              @tap="askQuickQuestion(question)"
            >
              {{ question }}
            </button>
          </view>
        </view>
      </scroll-view>

      <view class="assistant-composer">
        <textarea
          v-model="inputValue"
          class="assistant-input"
          auto-height
          maxlength="300"
          :placeholder="loading ? '正在初始化 AI 助手...' : '输入狼人杀相关问题，例如：舞者技能是什么？'"
          confirm-type="send"
          @confirm="sendMessage"
        />
        <button class="send-button" :disabled="!canSend || loading" @tap="sendMessage">
          {{ sending ? '发送中' : '发送' }}
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.assistant-page {
  min-height: 100vh;
  padding: 28rpx 24rpx 36rpx;
  background:
    radial-gradient(circle at 18% 12%, rgba(255, 192, 0, 0.16), transparent 32%),
    linear-gradient(180deg, #121212 0%, #060606 100%);
  color: #f7f1df;
  box-sizing: border-box;
}

.assistant-shell {
  min-height: calc(100vh - 64rpx);
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  gap: 22rpx;
}

.glass-card {
  border: 1rpx solid rgba(255, 255, 255, 0.08);
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.08), rgba(255, 255, 255, 0.03)),
    rgba(18, 18, 18, 0.88);
  box-shadow: 0 24rpx 52rpx rgba(0, 0, 0, 0.26);
  backdrop-filter: blur(18rpx);
}

.assistant-header {
  padding: 28rpx;
  border-radius: 34rpx;
  display: grid;
  gap: 22rpx;
}

.assistant-header-kicker {
  color: #ffc000;
  font-size: 22rpx;
  font-weight: 900;
  letter-spacing: 0.22em;
}

.assistant-header-main {
  display: flex;
  gap: 22rpx;
  align-items: center;
}

.assistant-avatar {
  width: 98rpx;
  height: 98rpx;
  border-radius: 30rpx;
  display: grid;
  place-items: center;
  gap: 8rpx;
  color: #fff7d6;
  font-size: 22rpx;
  font-weight: 900;
  background: linear-gradient(180deg, rgba(255, 192, 0, 0.38), rgba(62, 44, 0, 0.96));
  border: 2rpx solid rgba(255, 213, 95, 0.46);
}

.assistant-avatar-eyes {
  width: 42rpx;
  display: flex;
  justify-content: space-between;

  view {
    width: 9rpx;
    height: 14rpx;
    border-radius: 999rpx;
    background: #111;
  }
}

.assistant-header-copy {
  min-width: 0;
  display: grid;
  gap: 8rpx;
}

.assistant-header-title {
  font-size: 38rpx;
  font-weight: 900;
}

.assistant-header-desc {
  color: #b9b1a0;
  font-size: 24rpx;
  line-height: 1.5;
}

.assistant-header-actions {
  display: flex;
  gap: 16rpx;
  align-items: center;
  justify-content: space-between;
}

.assistant-status-pill,
.reset-button {
  height: 58rpx;
  padding: 0 24rpx;
  border-radius: 999rpx;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 24rpx;
  font-weight: 800;
}

.assistant-status-pill {
  color: #ffc000;
  background: rgba(255, 192, 0, 0.12);
  border: 1rpx solid rgba(255, 192, 0, 0.26);
}

.reset-button {
  margin: 0;
  color: #f7f1df;
  background: rgba(255, 255, 255, 0.08);
}

.maintenance-banner {
  padding: 24rpx 26rpx;
  border-radius: 28rpx;
  display: grid;
  gap: 8rpx;
  background: rgba(255, 192, 0, 0.1);
  border: 1rpx solid rgba(255, 192, 0, 0.18);
}

.maintenance-title {
  color: #ffe08a;
  font-size: 28rpx;
  font-weight: 900;
}

.maintenance-desc {
  color: #d4c7a8;
  font-size: 24rpx;
  line-height: 1.6;
}

.assistant-chat-scroll {
  min-height: 0;
  height: 100%;
  border-radius: 28rpx;
  background: rgba(6, 6, 6, 0.46);
}

.assistant-chat-content {
  min-height: 100%;
  padding: 4rpx 0 20rpx;
  display: grid;
  align-content: start;
  gap: 22rpx;
}

.message-row {
  display: flex;
  gap: 14rpx;
  align-items: flex-start;
}

.message-row--user {
  flex-direction: row-reverse;
}

.message-avatar {
  flex: 0 0 64rpx;
  width: 64rpx;
  height: 64rpx;
  border-radius: 22rpx;
  display: grid;
  place-items: center;
  color: #111;
  background: #ffc000;
  font-size: 22rpx;
  font-weight: 900;
}

.message-avatar--assistant {
  color: #ffc000;
  background: rgba(255, 192, 0, 0.12);
  border: 1rpx solid rgba(255, 192, 0, 0.28);
}

.message-bubble {
  max-width: 560rpx;
  padding: 22rpx 24rpx;
  border-radius: 28rpx;
}

.message-bubble--assistant {
  border-color: rgba(255, 192, 0, 0.18);
}

.answer-badge {
  width: fit-content;
  margin-bottom: 12rpx;
  padding: 6rpx 14rpx;
  border-radius: 999rpx;
  color: #ffc000;
  background: rgba(255, 192, 0, 0.12);
  font-size: 21rpx;
  font-weight: 800;
}

.message-text {
  color: #f1ead8;
  font-size: 28rpx;
  line-height: 1.72;
  white-space: pre-wrap;
}

.source-list {
  margin-top: 12rpx;
  display: grid;
  gap: 10rpx;
}

.source-item {
  padding: 12rpx;
  border-radius: 16rpx;
  background: rgba(255, 255, 255, 0.06);
}

.source-title {
  color: #f0cd76;
  font-size: 22rpx;
  display: block;
}

.source-content {
  margin-top: 4rpx;
  color: #cbbfa3;
  font-size: 22rpx;
  line-height: 1.4;
  display: block;
}

.quick-panel {
  margin-left: 78rpx;
  display: grid;
  gap: 12rpx;
}

.quick-title {
  color: #ffc000;
  font-size: 24rpx;
  font-weight: 900;
}

.quick-question {
  width: fit-content;
  max-width: 560rpx;
  margin: 0;
  padding: 12rpx 20rpx;
  border-radius: 999rpx;
  color: #f4df9b;
  background: rgba(255, 192, 0, 0.12);
  font-size: 24rpx;
  line-height: 1.4;
}

.assistant-composer {
  padding: 24rpx;
  border-radius: 36rpx;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 132rpx;
  gap: 16rpx;
  align-items: stretch;
  background: rgba(0, 0, 0, 0.42);
  border: 1rpx solid rgba(255, 255, 255, 0.08);
}

.assistant-input {
  width: 100%;
  min-height: 112rpx;
  max-height: 240rpx;
  padding: 24rpx 24rpx;
  border-radius: 28rpx;
  color: #f7f1df;
  background: rgba(255, 255, 255, 0.08);
  font-size: 27rpx;
  line-height: 1.65;
  box-sizing: border-box;
}

.send-button {
  height: 112rpx;
  margin: 0;
  border-radius: 28rpx;
  color: #17120a;
  background: linear-gradient(135deg, #ffe08a, #ffc000);
  font-size: 26rpx;
  font-weight: 900;
}

.send-button[disabled] {
  color: rgba(255, 255, 255, 0.5);
  background: rgba(255, 255, 255, 0.1);
}
</style>
