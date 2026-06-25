<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import assistant from '../../services/assistant'
import { markdownToRichText, markdownToRichTextSegments } from '../../services/markdown'
import { formatDateTime, fromNow } from '../../utils/format'
import { normalizeChatMessage } from './message-model'
import { createAssistantStreamBuffer, shouldAutoScrollOnMessageAppend } from './stream-buffer'
import storage from '../../services/storage'

const bootstrap = ref(null)
const sessions = ref([])
const messages = ref([])
const loading = ref(false)
const loadingHistory = ref(false)
const hydratingHistory = ref(false)
const pendingHistoryCount = ref(0)
const sending = ref(false)
const activeSessionId = ref('')
const scene = ref('general')
const pageContext = ref({})
const draft = ref('')
const chatScrollIntoView = ref('')
const loadError = ref('')

const streamRenderDelayMs = 120
const historyHydrationDelayMs = 28
const completedMessageRenderDelayMs = 16
const historyMessageSegmentCharLimit = 380
const completedMessageSegmentCharLimit = 520
let hydrationToken = 0
const pendingRenderTimers = new Map()

const draftCount = computed(() => (draft.value || '').length)
const assistantEnabled = computed(() => bootstrap.value?.enabled !== false)
const canSend = computed(() => assistantEnabled.value && !!(draft.value || '').trim() && !sending.value)
const quickQuestions = computed(() => bootstrap.value?.quickQuestions || [])
const latestSessionId = computed(() => bootstrap.value?.latestSessionId || '')
const hasMessages = computed(() => messages.value.length > 0)
const showEmptyState = computed(() => !loading.value && !loadingHistory.value && !hasMessages.value)

function scrollChatToBottom() {
  chatScrollIntoView.value = ''
  nextTick(() => {
    chatScrollIntoView.value = 'chat-scroll-anchor'
  })
}

watch(
  () => messages.value.length,
  (nextCount, previousCount) => {
    if (shouldAutoScrollOnMessageAppend(previousCount, nextCount)) {
      scrollChatToBottom()
    }
  },
)

function decorateSession(session) {
  return {
    ...session,
    title: session?.title || '未命名会话',
    relativeUpdate: fromNow(session?.updateTime),
    updateLabel: formatDateTime(session?.updateTime),
  }
}

function normalizeMessage(message, options = {}) {
  return normalizeChatMessage(
    message,
    {
      markdownRenderer: (content) => markdownToRichText(content, { streaming: !!message?.isStreaming }),
    },
    options,
  )
}

function clearPendingRenderTimer(messageId) {
  const timerId = pendingRenderTimers.get(String(messageId))
  if (timerId) {
    clearTimeout(timerId)
    pendingRenderTimers.delete(String(messageId))
  }
}

function clearAllPendingRenderTimers() {
  pendingRenderTimers.forEach((timerId) => clearTimeout(timerId))
  pendingRenderTimers.clear()
}

function cancelPendingHydration() {
  hydrationToken += 1
  hydratingHistory.value = false
  pendingHistoryCount.value = 0
  clearAllPendingRenderTimers()
}

function patchMessage(messageId, patcher, normalizeOptions = {}) {
  clearPendingRenderTimer(messageId)
  messages.value = messages.value.map((item) => {
    if (String(item.id) !== String(messageId)) {
      return item
    }
    return normalizeMessage(
      {
        ...patcher(item),
      },
      normalizeOptions,
    )
  })
}

function scheduleMessageHydration(messageId, options = {}) {
  clearPendingRenderTimer(messageId)

  const targetMessage = messages.value.find((item) => String(item.id) === String(messageId))
  if (!targetMessage?.content) {
    options.onComplete?.()
    return
  }

  const delayMs = options.delayMs ?? completedMessageRenderDelayMs
  const segments = markdownToRichTextSegments(targetMessage.content, {
    segmentCharLimit: options.segmentCharLimit ?? completedMessageSegmentCharLimit,
  })

  if (!segments.length) {
    patchMessage(messageId, (current) => ({
      ...current,
      isRenderPending: false,
      isStreaming: false,
    }))
    if (options.scrollOnProgress) {
      scrollChatToBottom()
    }
    options.onComplete?.()
    return
  }

  let index = 0
  const pump = () => {
    pendingRenderTimers.delete(String(messageId))
    index += 1

    patchMessage(
      messageId,
      (current) => ({
        ...current,
        renderMode: 'markdown',
        renderedContent: segments.slice(0, index).join(''),
        isRenderPending: index < segments.length,
        isStreaming: false,
      }),
      { preserveRenderedContent: true },
    )

    if (options.scrollOnProgress) {
      scrollChatToBottom()
    }

    if (index >= segments.length) {
      options.onComplete?.()
      return
    }

    const nextTimerId = setTimeout(pump, delayMs)
    pendingRenderTimers.set(String(messageId), nextTimerId)
  }

  const timerId = setTimeout(pump, delayMs)
  pendingRenderTimers.set(String(messageId), timerId)
}

function hydratePendingMessages() {
  const pendingIds = messages.value
    .filter((item) => item.role === 'ASSISTANT' && item.isRenderPending)
    .map((item) => item.id)

  pendingHistoryCount.value = pendingIds.length
  hydratingHistory.value = pendingIds.length > 0

  if (!pendingIds.length) {
    return
  }

  const currentToken = ++hydrationToken

  const pump = (index = 0) => {
    if (currentToken !== hydrationToken) {
      return
    }
    if (index >= pendingIds.length) {
      hydratingHistory.value = false
      pendingHistoryCount.value = 0
      return
    }

    const nextId = pendingIds[index]
    scheduleMessageHydration(nextId, {
      delayMs: historyHydrationDelayMs,
      segmentCharLimit: historyMessageSegmentCharLimit,
      onComplete() {
        if (currentToken !== hydrationToken) {
          return
        }
        pendingHistoryCount.value = Math.max(0, pendingIds.length - index - 1)
        setTimeout(() => pump(index + 1), historyHydrationDelayMs)
      },
    })
  }

  setTimeout(() => pump(0), 0)
}

function appendOptimisticUserMessage(message) {
  const tempMessage = normalizeMessage({
    id: `local-user-${Date.now()}`,
    role: 'USER',
    content: message,
    contentFormat: 'PLAIN_TEXT',
    isStreaming: false,
  })
  messages.value = [...messages.value, tempMessage]
}

function addStreamingAssistantMessage() {
  const messageId = `local-assistant-${Date.now()}`
  const streamingMessage = normalizeMessage({
    id: messageId,
    role: 'ASSISTANT',
    content: '',
    contentFormat: 'MARKDOWN',
    answerType: 'STREAMING',
    isStreaming: true,
  })
  messages.value = [...messages.value, streamingMessage]
  return messageId
}

function replaceStreamingMessage(messageId, response) {
  const nextMessage = normalizeMessage({
    id: response.messageId,
    role: 'ASSISTANT',
    content: response.answer,
    contentFormat: response.contentFormat || 'MARKDOWN',
    answerType: response.answerType,
    traceId: response.traceId,
    isStreaming: false,
  })

  clearPendingRenderTimer(messageId)
  messages.value = messages.value.map((item) => {
    if (String(item.id) !== String(messageId)) {
      return item
    }
    return nextMessage
  })
  scrollChatToBottom()

  if (nextMessage.isRenderPending) {
    scheduleMessageHydration(nextMessage.id, {
      delayMs: completedMessageRenderDelayMs,
      segmentCharLimit: completedMessageSegmentCharLimit,
      scrollOnProgress: true,
    })
  }
}

function markStreamingFailed(messageId, errorMessage) {
  patchMessage(messageId, () => ({
    role: 'ASSISTANT',
    content: `## 发送失败\n\n${errorMessage || '当前消息发送失败，请稍后再试。'}`,
    contentFormat: 'MARKDOWN',
    answerType: 'REFUSAL',
    isStreaming: false,
  }))
}

async function loadBootstrap() {
  bootstrap.value = await assistant.bootstrap()
}

async function loadSessionList() {
  const rawSessions = await assistant.getSessions()
  sessions.value = rawSessions.map(decorateSession)
}

async function loadMessages(sessionId = activeSessionId.value) {
  if (!sessionId) {
    cancelPendingHydration()
    messages.value = []
    activeSessionId.value = ''
    return
  }

  loadingHistory.value = true
  loadError.value = ''
  activeSessionId.value = sessionId

  try {
    const rawMessages = await assistant.getMessages(sessionId)
    cancelPendingHydration()
    messages.value = rawMessages.map((message) => normalizeMessage(message, { deferMarkdown: true }))
    hydratePendingMessages()
  } catch (error) {
    loadError.value = error instanceof Error && error.message ? error.message : '历史会话加载失败'
    throw error
  } finally {
    loadingHistory.value = false
  }
}

async function loadAll() {
  if (!storage.getAuthToken()) {
    loadError.value = '请先登录后再使用 AI 助手'
    uni.showToast({ title: '请先登录', icon: 'none' })
    return
  }

  loading.value = true
  loadError.value = ''

  try {
    await Promise.all([loadBootstrap(), loadSessionList()])
  } catch (error) {
    loadError.value = error instanceof Error && error.message ? error.message : 'AI 助手加载失败'
    uni.showToast({ title: 'AI 助手加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

async function sendQuestion(question = draft.value) {
  if (sending.value || !assistantEnabled.value) return

  const message = (question || '').trim()
  if (!message) {
    uni.showToast({ title: '请输入问题', icon: 'none' })
    return
  }

  loadError.value = ''
  sending.value = true
  appendOptimisticUserMessage(message)
  const streamingMessageId = addStreamingAssistantMessage()
  const streamBuffer = createAssistantStreamBuffer(
    (delta) => {
      patchMessage(streamingMessageId, (current) => ({
        ...current,
        content: `${current.content || ''}${delta}`,
        isStreaming: true,
      }))
    },
    { delayMs: streamRenderDelayMs },
  )

  try {
    const response = await assistant.askStream(
      {
        sessionId: activeSessionId.value || undefined,
        message,
        scene: scene.value,
        pageContext: pageContext.value,
        clientTimestamp: new Date().toISOString(),
      },
      {
        onStarted(event) {
          if (event?.sessionId) {
            activeSessionId.value = event.sessionId
          }
        },
        onDelta(event) {
          const delta = event?.delta || ''
          if (!delta) return
          streamBuffer.push(delta)
        },
        onDone(event) {
          streamBuffer.flush()
          replaceStreamingMessage(streamingMessageId, event)
        },
      },
    )

    activeSessionId.value = response.sessionId
    draft.value = ''
    loadSessionList().catch(() => {})
  } catch (error) {
    const messageText = error instanceof Error && error.message ? error.message : '发送失败，请稍后再试'
    markStreamingFailed(streamingMessageId, messageText)
    uni.showToast({ title: messageText.slice(0, 18), icon: 'none' })
  } finally {
    streamBuffer.dispose()
    sending.value = false
  }
}

function goLogin() {
  uni.switchTab({ url: '/pages/user/index' })
}

async function resumeLatestSession() {
  if (!latestSessionId.value || loadingHistory.value || sending.value) {
    return
  }
  await loadMessages(latestSessionId.value)
}

async function openSession(sessionId) {
  if (!sessionId || loadingHistory.value || sending.value) {
    return
  }
  await loadMessages(sessionId)
}

function startNewSession() {
  cancelPendingHydration()
  loadError.value = ''
  activeSessionId.value = ''
  messages.value = []
  draft.value = ''
}

async function resetCurrentSession() {
  if (!activeSessionId.value) {
    startNewSession()
    return
  }
  try {
    await assistant.resetSession(activeSessionId.value)
    startNewSession()
    await loadSessionList()
    uni.showToast({ title: '当前会话已清空', icon: 'none' })
  } catch (error) {
    uni.showToast({ title: '清空失败', icon: 'none' })
  }
}

async function retryCurrentView() {
  if (!storage.getAuthToken()) {
    goLogin()
    return
  }
  if (activeSessionId.value) {
    await loadMessages(activeSessionId.value)
    return
  }
  await loadAll()
}

function messageTimeLabel(message) {
  return formatDateTime(message?.createTime)
}

onLoad((options) => {
  scene.value = decodeURIComponent(options?.scene || 'general')
  try {
    pageContext.value = JSON.parse(decodeURIComponent(options?.context || '{}'))
  } catch (error) {
    pageContext.value = {}
  }
  loadAll()
})

onUnload(() => {
  cancelPendingHydration()
})
</script>

<template>
  <view class="page-shell assistant-page">
    <view class="assistant-shell">
      <view class="assistant-toolbar">
        <view class="toolbar-title">AI 助手</view>
        <view class="toolbar-actions">
          <view class="toolbar-action" @tap="startNewSession">新会话</view>
          <view
            v-if="latestSessionId && latestSessionId !== activeSessionId"
            class="toolbar-action toolbar-action--ghost"
            @tap="resumeLatestSession"
          >
            恢复最近
          </view>
          <view
            v-if="activeSessionId"
            class="toolbar-action toolbar-action--ghost"
            @tap="resetCurrentSession"
          >
            清空当前
          </view>
        </view>
      </view>

      <view v-if="sessions.length" class="glass-card session-panel">
        <view class="panel-head">
          <view class="panel-title">对话历史</view>
          <view class="panel-meta">{{ sessions.length }} 条</view>
        </view>

        <scroll-view scroll-x class="session-scroll" show-scrollbar="false">
          <view class="session-row">
            <view
              v-for="item in sessions.slice(0, 8)"
              :key="item.sessionId"
              class="session-pill"
              :class="{ 'session-pill--active': item.sessionId === activeSessionId }"
              @tap="openSession(item.sessionId)"
            >
              <view class="session-pill-title">{{ item.title }}</view>
              <view class="session-pill-meta">{{ item.relativeUpdate || item.updateLabel }}</view>
            </view>
          </view>
        </scroll-view>
      </view>

      <view v-if="loadError" class="glass-card state-panel state-panel--error">
        <view class="state-title">当前加载失败</view>
        <view class="state-desc">{{ loadError }}</view>
        <view class="state-actions">
          <view class="state-button" @tap="retryCurrentView">重新加载</view>
          <view v-if="!storage.getAuthToken()" class="state-button state-button--ghost" @tap="goLogin">去登录</view>
        </view>
      </view>

      <scroll-view
        class="assistant-chat-scroll"
        scroll-y
        show-scrollbar="false"
        :scroll-into-view="chatScrollIntoView"
      >
        <view class="assistant-chat-content">
          <view v-if="showEmptyState" class="glass-card empty-panel">
            <view class="panel-title">开始对话</view>
            <view class="panel-desc">从下方快捷提问开始，或者直接输入问题。</view>
          </view>

          <view v-if="loading && !hasMessages" class="glass-card state-panel">
            <view class="state-title">正在连接 AI 助手</view>
            <view class="state-desc">正在准备会话数据。</view>
          </view>

          <view v-if="loadingHistory && !hasMessages" class="glass-card state-panel">
            <view class="state-title">正在载入历史对话</view>
            <view class="state-desc">会先恢复文本，再逐步整理长回答。</view>
          </view>

          <view v-if="hydratingHistory && hasMessages" class="glass-card hydrate-panel">
            <view class="hydrate-dot" />
            <view class="hydrate-copy">正在整理 {{ pendingHistoryCount }} 条历史回答，不影响继续提问。</view>
          </view>

          <view
            v-for="item in messages"
            :key="item.id"
            class="message-row"
            :class="{ 'message-row--self': item.role === 'USER' }"
            :id="`message-${item.id}`"
          >
            <view
              class="glass-card message-card"
              :class="{
                'message-card--assistant': item.role === 'ASSISTANT',
                'message-card--self': item.role === 'USER',
              }"
            >
              <view class="message-head">
                <view class="message-role">{{ item.role === 'USER' ? '我' : 'AI' }}</view>
                <view class="message-time">{{ messageTimeLabel(item) }}</view>
              </view>

              <rich-text
                v-if="item.renderMode === 'markdown'"
                class="message-content message-content--markdown"
                :nodes="item.renderedContent"
              />
              <view v-else class="message-content message-content--plain">{{ item.content }}</view>
            </view>
          </view>

          <view id="chat-scroll-anchor" class="scroll-anchor" />
        </view>
      </scroll-view>

      <view class="glass-card composer-panel">
        <scroll-view
          v-if="quickQuestions.length"
          scroll-x
          class="quick-scroll"
          show-scrollbar="false"
        >
          <view class="quick-row">
            <view
              v-for="item in quickQuestions"
              :key="item"
              class="quick-chip"
              @tap="sendQuestion(item)"
            >
              {{ item }}
            </view>
          </view>
        </scroll-view>

        <view class="composer-editor">
          <textarea
            v-model="draft"
            class="assistant-input"
            maxlength="300"
            auto-height
            :show-confirm-bar="false"
            :cursor-spacing="24"
            placeholder="比如：守卫和女巫会不会冲突？"
          />
        </view>

        <view class="composer-footer">
          <view class="composer-count">{{ draftCount }}/300</view>
          <button class="composer-submit" :disabled="!canSend" :loading="sending" @tap="sendQuestion()">
            发送
          </button>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.assistant-page {
  height: 100vh;
  padding: 24rpx 24rpx calc(env(safe-area-inset-bottom) + 20rpx);
  overflow: hidden;
}

.assistant-shell {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 18rpx;
}

.assistant-toolbar,
.panel-head,
.message-head,
.composer-footer,
.state-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}

.assistant-toolbar {
  flex-shrink: 0;
}

.toolbar-title {
  color: #fff6df;
  font-size: 34rpx;
  font-weight: 700;
}

.toolbar-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 12rpx;
}

.toolbar-action,
.state-button {
  min-width: 112rpx;
  height: 60rpx;
  padding: 0 20rpx;
  border-radius: 999rpx;
  background: rgba(255, 192, 0, 0.16);
  color: #ffc000;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22rpx;
  font-weight: 700;
}

.toolbar-action--ghost,
.state-button--ghost {
  background: rgba(255, 255, 255, 0.06);
  color: #ece4cf;
}

.panel-title,
.state-title {
  color: #fff6df;
  font-size: 28rpx;
  font-weight: 700;
  line-height: 1.35;
}

.panel-desc,
.state-desc,
.hydrate-copy {
  color: #cfc6b4;
  line-height: 1.7;
  font-size: 24rpx;
}

.panel-meta,
.session-pill-meta,
.message-time,
.composer-count {
  color: #938a79;
  font-size: 22rpx;
}

.session-panel,
.empty-panel,
.state-panel,
.hydrate-panel,
.composer-panel,
.message-card {
  overflow: hidden;
}

.session-panel {
  flex-shrink: 0;
  padding: 20rpx 22rpx;
  background: linear-gradient(180deg, rgba(18, 18, 18, 0.98), rgba(10, 10, 10, 0.96));
}

.session-scroll {
  margin-top: 16rpx;
  white-space: nowrap;
}

.session-row {
  display: inline-flex;
  gap: 14rpx;
  padding-bottom: 4rpx;
}

.session-pill {
  min-width: 220rpx;
  max-width: 280rpx;
  padding: 18rpx 20rpx;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.05);
  border: 1rpx solid rgba(255, 255, 255, 0.04);
  box-sizing: border-box;
}

.session-pill--active {
  background: rgba(255, 192, 0, 0.12);
  border-color: rgba(255, 192, 0, 0.2);
}

.session-pill-title {
  color: #f8f1de;
  font-size: 24rpx;
  font-weight: 700;
  line-height: 1.4;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.assistant-chat-scroll {
  flex: 1;
  min-height: 0;
}

.assistant-chat-content {
  display: grid;
  gap: 16rpx;
  padding-bottom: 8rpx;
}

.empty-panel,
.state-panel,
.hydrate-panel,
.composer-panel {
  padding: 22rpx;
  background: linear-gradient(180deg, rgba(18, 18, 18, 0.98), rgba(10, 10, 10, 0.96));
}

.state-panel--error {
  border: 1rpx solid rgba(255, 114, 94, 0.18);
  background:
    linear-gradient(180deg, rgba(255, 114, 94, 0.06), rgba(255, 255, 255, 0.02)),
    linear-gradient(180deg, rgba(18, 18, 18, 0.98), rgba(10, 10, 10, 0.96));
}

.state-actions {
  margin-top: 18rpx;
  justify-content: flex-start;
}

.hydrate-panel {
  display: flex;
  align-items: center;
  gap: 14rpx;
  padding: 16rpx 20rpx;
}

.hydrate-dot {
  width: 16rpx;
  height: 16rpx;
  border-radius: 999rpx;
  background: #ffc000;
  box-shadow: 0 0 14rpx rgba(255, 192, 0, 0.34);
  flex-shrink: 0;
}

.message-row {
  display: flex;
  justify-content: flex-start;
}

.message-row--self {
  justify-content: flex-end;
}

.message-card {
  width: 100%;
  padding: 20rpx 22rpx;
  background: linear-gradient(180deg, rgba(18, 18, 18, 0.98), rgba(10, 10, 10, 0.96));
}

.message-card--assistant {
  max-width: 92%;
  border: 1rpx solid rgba(255, 192, 0, 0.12);
  background:
    linear-gradient(180deg, rgba(255, 192, 0, 0.05), rgba(255, 255, 255, 0.02)),
    linear-gradient(180deg, rgba(18, 18, 18, 0.98), rgba(10, 10, 10, 0.96));
}

.message-card--self {
  max-width: 88%;
  background: linear-gradient(180deg, rgba(36, 36, 36, 0.98), rgba(18, 18, 18, 0.98));
}

.message-role {
  color: #ffc000;
  font-size: 22rpx;
  font-weight: 700;
}

.message-content {
  margin-top: 12rpx;
}

.message-content--plain {
  color: #f3eee1;
  font-size: 28rpx;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}

.message-content--markdown {
  display: block;
}

.composer-panel {
  flex-shrink: 0;
  padding: 20rpx;
  background: linear-gradient(180deg, rgba(17, 17, 17, 0.98), rgba(8, 8, 8, 0.98));
}

.quick-scroll {
  white-space: nowrap;
}

.quick-row {
  display: inline-flex;
  gap: 12rpx;
  padding-bottom: 6rpx;
}

.quick-chip {
  padding: 14rpx 18rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.06);
  color: #f4edd9;
  font-size: 22rpx;
  line-height: 1.4;
}

.composer-editor {
  margin-top: 14rpx;
  padding: 12rpx 14rpx;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.05);
  border: 1rpx solid rgba(255, 255, 255, 0.04);
}

.assistant-input {
  width: 100%;
  min-height: 88rpx;
  max-height: 180rpx;
  padding: 0;
  background: transparent;
  color: #ffffff;
  font-size: 28rpx;
  line-height: 1.7;
  box-sizing: border-box;
}

.composer-footer {
  margin-top: 14rpx;
}

.composer-submit {
  margin: 0;
  width: 168rpx;
  height: 84rpx;
  line-height: 84rpx;
  border-radius: 16rpx;
  background: linear-gradient(135deg, #ffd24f, #f3b91f);
  color: #171105;
  font-size: 30rpx;
  font-weight: 800;
  box-shadow: 0 14rpx 28rpx rgba(255, 192, 0, 0.2);
  flex-shrink: 0;
}

.composer-submit::after {
  border: none;
}

.composer-submit[disabled] {
  background: rgba(255, 255, 255, 0.1);
  color: #8f8f8f;
  box-shadow: none;
}

.scroll-anchor {
  height: 1rpx;
}
</style>
