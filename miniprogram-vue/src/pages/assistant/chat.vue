<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import assistant from '../../services/assistant'
import { markdownToRichText, plainTextToRichText } from '../../services/markdown'
import storage from '../../services/storage'

const bootstrap = ref(null)
const messages = ref([])
const loading = ref(false)
const sending = ref(false)
const activeSessionId = ref('')
const scene = ref('general')
const pageContext = ref({})
const draft = ref('')
const chatScrollIntoView = ref('')

const draftCount = computed(() => (draft.value || '').length)
const canSend = computed(() => !!(draft.value || '').trim() && !sending.value)
const composerHint = computed(() => '可以问规则、角色技能冲突，或者按人数推荐板子。')

function scrollChatToBottom() {
  chatScrollIntoView.value = ''
  nextTick(() => {
    chatScrollIntoView.value = 'chat-scroll-anchor'
  })
}

watch(
  messages,
  () => {
    scrollChatToBottom()
  },
  { deep: true }
)

function renderContent(message) {
  const content = message?.content || ''
  if ((message?.contentFormat || 'PLAIN_TEXT') === 'MARKDOWN') {
    if (message?.isStreaming) {
      return plainTextToRichText(content)
    }
    return markdownToRichText(content, { streaming: !!message?.isStreaming })
  }
  return plainTextToRichText(content)
}

function normalizeMessage(message) {
  const citations = Array.isArray(message?.citations) ? message.citations : []
  const boards = Array.isArray(message?.recommendedBoards) ? message.recommendedBoards : []
  const contentFormat = message?.contentFormat || (message?.role === 'ASSISTANT' ? 'MARKDOWN' : 'PLAIN_TEXT')

  let visibleCitations = citations
  if (citations.length && boards.length) {
    const boardIds = new Set(boards.map((board) => String(board.id)))
    visibleCitations = citations.filter((citation) => {
      if (citation?.sourceType !== 'STRUCTURED') {
        return true
      }
      return !boardIds.has(String(citation.sourceId))
    })
  }

  visibleCitations = visibleCitations.filter((citation) => citation?.sourceType === 'WEB')

  return {
    ...message,
    contentFormat,
    visibleCitations,
    renderedContent: renderContent({ ...message, contentFormat }),
  }
}

function appendOptimisticUserMessage(message) {
  const tempMessage = normalizeMessage({
    id: `local-user-${Date.now()}`,
    role: 'USER',
    content: message,
    contentFormat: 'PLAIN_TEXT',
    citations: [],
    recommendedBoards: [],
    suggestedQuestions: [],
    usedWebSearch: false,
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
    citations: [],
    recommendedBoards: [],
    suggestedQuestions: [],
    usedWebSearch: false,
    isStreaming: true,
  })
  messages.value = [...messages.value, streamingMessage]
  return messageId
}

function patchMessage(messageId, patcher) {
  messages.value = messages.value.map((item) => {
    if (String(item.id) !== String(messageId)) {
      return item
    }
    return normalizeMessage({
      ...patcher(item),
    })
  })
}

function replaceStreamingMessage(messageId, response) {
  patchMessage(messageId, () => ({
    id: response.messageId,
    role: 'ASSISTANT',
    content: response.answer,
    contentFormat: response.contentFormat || 'MARKDOWN',
    answerType: response.answerType,
    citations: response.citations,
    recommendedBoards: response.recommendedBoards,
    suggestedQuestions: response.suggestedQuestions,
    usedWebSearch: response.usedWebSearch,
    traceId: response.traceId,
    isStreaming: false,
  }))
}

function markStreamingFailed(messageId, errorMessage) {
  patchMessage(messageId, () => ({
    role: 'ASSISTANT',
    content: `## 发送失败\n\n- ${errorMessage || '当前消息发送失败，请稍后再试。'}`,
    contentFormat: 'MARKDOWN',
    answerType: 'REFUSAL',
    citations: [],
    recommendedBoards: [],
    suggestedQuestions: [],
    usedWebSearch: false,
    isStreaming: false,
  }))
}

async function loadBootstrap() {
  bootstrap.value = await assistant.bootstrap()
  if (!activeSessionId.value && bootstrap.value?.latestSessionId) {
    activeSessionId.value = bootstrap.value.latestSessionId
  }
}

async function loadMessages() {
  if (!activeSessionId.value) {
    messages.value = []
    return
  }
  const rawMessages = await assistant.getMessages(activeSessionId.value)
  messages.value = rawMessages.map(normalizeMessage)
}

async function loadAll() {
  if (!storage.getAuthToken()) {
    uni.showToast({ title: '请先登录', icon: 'none' })
    return
  }
  loading.value = true
  try {
    await loadBootstrap()
    await loadMessages()
  } catch (error) {
    uni.showToast({ title: 'AI 助手加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

async function sendQuestion(question = draft.value) {
  if (sending.value) return
  const message = (question || '').trim()
  if (!message) {
    uni.showToast({ title: '请输入问题', icon: 'none' })
    return
  }

  sending.value = true
  appendOptimisticUserMessage(message)
  const streamingMessageId = addStreamingAssistantMessage()

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
          patchMessage(streamingMessageId, (current) => ({
            ...current,
            content: `${current.content || ''}${delta}`,
            isStreaming: true,
          }))
        },
        onDone(event) {
          replaceStreamingMessage(streamingMessageId, event)
        },
      }
    )

    activeSessionId.value = response.sessionId
    draft.value = ''
  } catch (error) {
    const messageText = error instanceof Error && error.message ? error.message : '发送失败，请稍后再试'
    markStreamingFailed(streamingMessageId, messageText)
    uni.showToast({ title: messageText.slice(0, 18), icon: 'none' })
  } finally {
    sending.value = false
  }
}

function openBoard(boardId) {
  uni.navigateTo({ url: `/pages/boards/detail?id=${boardId}` })
}

function answerTypeLabel(answerType) {
  if (answerType === 'STRUCTURED_RECOMMENDATION') return '站内推荐'
  if (answerType === 'RAG_ANSWER') return '知识库回答'
  if (answerType === 'WEB_AUGMENTED_ANSWER') return '联网增强'
  if (answerType === 'REFUSAL') return '边界提示'
  if (answerType === 'STREAMING') return '生成中'
  return 'AI 回答'
}

function sourceTypeLabel(sourceType) {
  if (sourceType === 'STRUCTURED') return '站内板库'
  if (sourceType === 'DOCUMENT') return '知识文档'
  if (sourceType === 'WEB') return '联网来源'
  return sourceType || '来源'
}

function sourceTypeClass(sourceType) {
  if (sourceType === 'STRUCTURED') return 'citation-badge--structured'
  if (sourceType === 'DOCUMENT') return 'citation-badge--document'
  if (sourceType === 'WEB') return 'citation-badge--web'
  return 'citation-badge--default'
}

function handleCitationAction(citation) {
  if (!citation?.url) return
  uni.setClipboardData({
    data: citation.url,
    success: () => uni.showToast({ title: '链接已复制', icon: 'none' }),
  })
}

function startNewSession() {
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
    uni.showToast({ title: '会话已重置', icon: 'none' })
    startNewSession()
  } catch (error) {
    uni.showToast({ title: '重置失败', icon: 'none' })
  }
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
</script>

<template>
  <view class="page-shell assistant-page">
    <view class="assistant-shell">
      <view class="glass-card assistant-header">
        <view class="assistant-header-kicker">WOLFBOOK AI ASSISTANT</view>
        <view class="assistant-header-main">
          <view class="assistant-header-copy">
            <view class="assistant-header-title">AI 狼人顾问</view>
            <view class="assistant-header-desc">规则、角色、板子和站内知识，都可以在这里快速问。</view>
          </view>
          <view class="assistant-header-actions">
            <view class="header-action" @tap="startNewSession">新建</view>
            <view class="header-action header-action--ghost" @tap="resetCurrentSession">重置</view>
          </view>
        </view>
        <view class="assistant-header-meta">
          <view class="hero-chip">{{ bootstrap?.appearance?.dockLabel || 'AI狼人顾问' }}</view>
        </view>
      </view>

      <scroll-view
        class="assistant-chat-scroll"
        scroll-y
        enhanced
        show-scrollbar="false"
        :scroll-into-view="chatScrollIntoView"
      >
        <view class="assistant-chat-content">
          <view v-if="bootstrap && !messages.length" class="glass-card section-card welcome-panel">
            <view class="section-title">快捷提问</view>
            <view class="section-desc">{{ bootstrap.welcomeMessage }}</view>
            <view class="suggestion-grid">
              <view v-for="item in bootstrap.quickQuestions" :key="item" class="suggestion-card" @tap="sendQuestion(item)">
                {{ item }}
              </view>
            </view>
          </view>

          <view v-if="loading && !messages.length" class="glass-card section-card welcome-panel">
            <view class="section-desc">正在整理当前会话和知识来源...</view>
          </view>

          <view
            v-for="item in messages"
            :key="item.id"
            class="message-row"
            :class="{ 'message-row--assistant': item.role === 'ASSISTANT' }"
            :id="`message-${item.id}`"
          >
            <view class="message-avatar" :class="{ 'message-avatar--assistant': item.role === 'ASSISTANT' }">
              {{ item.role === 'ASSISTANT' ? 'AI' : '我' }}
            </view>
            <view class="glass-card message-bubble" :class="{ 'message-bubble--assistant': item.role === 'ASSISTANT' }">
              <view v-if="item.role === 'ASSISTANT'" class="message-bubble-head">
                <view class="answer-chip" :class="{ 'answer-chip--streaming': item.isStreaming }">
                  {{ item.isStreaming ? '生成中' : answerTypeLabel(item.answerType) }}
                </view>
                <view v-if="item.usedWebSearch" class="answer-chip answer-chip--web">联网补充</view>
              </view>

              <rich-text
                v-if="item.role === 'ASSISTANT'"
                class="message-content message-content--markdown"
                :nodes="item.renderedContent"
              />
              <view v-else class="message-content message-content--plain">{{ item.content }}</view>

              <view v-if="item.recommendedBoards && item.recommendedBoards.length" class="board-stack">
                <view
                  v-for="board in item.recommendedBoards"
                  :key="board.id"
                  class="board-card"
                  @tap="openBoard(board.id)"
                >
                  <image v-if="board.coverImage" class="board-cover" :src="board.coverImage" mode="aspectFill" />
                  <view class="board-copy">
                    <view class="board-name">{{ board.name }}</view>
                    <view class="section-meta">{{ board.playerCount }} 人 / {{ board.difficulty }}</view>
                    <view class="board-reason">{{ board.reason }}</view>
                  </view>
                </view>
              </view>

              <view v-if="item.visibleCitations && item.visibleCitations.length" class="citation-stack">
                <view
                  v-for="citation in item.visibleCitations"
                  :key="`${citation.sourceType}-${citation.title}-${citation.sourceId}`"
                  class="citation-card"
                  @tap="handleCitationAction(citation)"
                >
                  <view class="citation-head">
                    <view class="citation-badge" :class="sourceTypeClass(citation.sourceType)">
                      {{ sourceTypeLabel(citation.sourceType) }}
                    </view>
                    <view v-if="citation.url" class="citation-action">复制链接</view>
                  </view>
                  <view class="citation-title">{{ citation.title }}</view>
                </view>
              </view>
            </view>
          </view>

          <view id="chat-scroll-anchor" class="scroll-anchor" />
        </view>
      </scroll-view>

      <view class="assistant-composer glass-card">
        <view class="composer-head">
          <view class="composer-copy">
            <view class="composer-kicker">TACTICAL QUERY</view>
            <view class="composer-title">战术提问</view>
          </view>
          <view class="composer-meta">{{ draftCount }}/300</view>
        </view>

        <view class="composer-editor">
          <textarea
            v-model="draft"
            class="assistant-input"
            maxlength="300"
            auto-height
            :show-confirm-bar="false"
            :cursor-spacing="24"
            placeholder="比如：12人进阶推荐什么板子？女巫能不能自救？"
          />
        </view>

        <view class="composer-footer">
          <view class="composer-hint">{{ composerHint }}</view>
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
  gap: 20rpx;
}

.assistant-header {
  position: relative;
  overflow: hidden;
  flex-shrink: 0;
  padding: 28rpx;
  background:
    radial-gradient(circle at right top, rgba(255, 192, 0, 0.18), transparent 34%),
    linear-gradient(180deg, rgba(18, 18, 18, 0.98), rgba(8, 8, 8, 0.98));
}

.assistant-header::after {
  content: '';
  position: absolute;
  right: -20rpx;
  bottom: -28rpx;
  width: 220rpx;
  height: 220rpx;
  border-radius: 999rpx;
  background: radial-gradient(circle, rgba(255, 192, 0, 0.14), transparent 68%);
}

.assistant-header-kicker {
  position: relative;
  z-index: 1;
  color: #ffc000;
  font-size: 20rpx;
  font-weight: 700;
  letter-spacing: 4rpx;
}

.assistant-header-main {
  position: relative;
  z-index: 1;
  margin-top: 12rpx;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20rpx;
}

.assistant-header-copy {
  min-width: 0;
  flex: 1;
}

.assistant-header-title {
  font-size: 54rpx;
  font-weight: 700;
}

.assistant-header-desc {
  margin-top: 12rpx;
  color: #d8d0bd;
  line-height: 1.75;
  font-size: 26rpx;
}

.assistant-header-actions {
  display: flex;
  gap: 12rpx;
  flex-shrink: 0;
}

.header-action {
  min-width: 100rpx;
  height: 64rpx;
  padding: 0 22rpx;
  border-radius: 999rpx;
  background: rgba(255, 192, 0, 0.16);
  color: #ffc000;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22rpx;
  font-weight: 700;
}

.header-action--ghost {
  background: rgba(255, 255, 255, 0.06);
  color: #ece4cf;
}

.assistant-header-meta {
  position: relative;
  z-index: 1;
  display: flex;
  gap: 12rpx;
  margin-top: 18rpx;
}

.hero-chip {
  padding: 10rpx 18rpx;
  border-radius: 999rpx;
  background: rgba(255, 192, 0, 0.14);
  color: #ffc000;
  font-size: 22rpx;
  font-weight: 700;
}

.assistant-chat-scroll {
  flex: 1;
  min-height: 0;
}

.assistant-chat-content {
  padding-bottom: 8rpx;
}

.welcome-panel {
  margin-top: 0;
}

.suggestion-grid {
  display: grid;
  gap: 14rpx;
  margin-top: 20rpx;
}

.suggestion-card {
  padding: 18rpx 22rpx;
  border-radius: 16rpx;
  background: linear-gradient(180deg, rgba(255, 192, 0, 0.1), rgba(255, 255, 255, 0.03));
  border: 1rpx solid rgba(255, 192, 0, 0.18);
  color: #f7f2df;
  line-height: 1.6;
  box-shadow: 0 14rpx 26rpx rgba(0, 0, 0, 0.14);
}

.message-row {
  margin-top: 24rpx;
  display: grid;
  grid-template-columns: 72rpx 1fr;
  gap: 16rpx;
  align-items: start;
}

.message-row--assistant:first-of-type {
  margin-top: 0;
}

.message-avatar {
  width: 72rpx;
  height: 72rpx;
  border-radius: 22rpx;
  background: rgba(255, 255, 255, 0.08);
  color: #ffffff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22rpx;
  font-weight: 700;
}

.message-avatar--assistant {
  background: rgba(255, 192, 0, 0.16);
  color: #ffc000;
}

.message-bubble {
  padding: 20rpx;
}

.message-bubble--assistant {
  border: 1rpx solid rgba(255, 192, 0, 0.12);
  background:
    linear-gradient(180deg, rgba(255, 192, 0, 0.06), rgba(255, 255, 255, 0.03)),
    linear-gradient(180deg, rgba(18, 18, 18, 0.98), rgba(10, 10, 10, 0.98));
}

.message-bubble-head,
.citation-head,
.composer-head,
.composer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}

.answer-chip {
  padding: 6rpx 14rpx;
  border-radius: 999rpx;
  background: rgba(255, 192, 0, 0.14);
  color: #ffc000;
  font-size: 18rpx;
  font-weight: 700;
}

.answer-chip--web {
  background: rgba(255, 255, 255, 0.06);
  color: #ece4cf;
}

.answer-chip--streaming {
  background: rgba(255, 255, 255, 0.08);
  color: #ffd86b;
}

.message-content {
  margin-top: 12rpx;
}

.message-content--plain {
  white-space: pre-wrap;
  line-height: 1.7;
  font-size: 24rpx;
}

.message-content--markdown {
  display: block;
}

.board-stack,
.citation-stack {
  display: grid;
  gap: 16rpx;
  margin-top: 20rpx;
}

.board-card,
.citation-card {
  padding: 18rpx;
  border-radius: 16rpx;
  border: 1rpx solid rgba(255, 255, 255, 0.04);
}

.board-card {
  display: flex;
  gap: 16rpx;
  background:
    linear-gradient(180deg, rgba(255, 192, 0, 0.07), rgba(255, 255, 255, 0.03)),
    rgba(255, 255, 255, 0.02);
}

.citation-card {
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.035), rgba(255, 255, 255, 0.02)),
    rgba(255, 255, 255, 0.02);
}

.board-cover {
  width: 140rpx;
  height: 108rpx;
  border-radius: 14rpx;
  flex-shrink: 0;
}

.board-copy {
  min-width: 0;
  flex: 1;
}

.board-name,
.citation-title {
  font-size: 26rpx;
  font-weight: 700;
}

.board-reason,
.citation-snippet {
  margin-top: 8rpx;
  line-height: 1.65;
  font-size: 22rpx;
}

.board-reason {
  color: #cacaca;
}

.citation-snippet {
  color: #d8cfba;
}

.citation-badge {
  padding: 6rpx 14rpx;
  border-radius: 999rpx;
  font-size: 18rpx;
  font-weight: 700;
}

.citation-badge--structured {
  background: rgba(255, 192, 0, 0.16);
  color: #ffc000;
}

.citation-badge--document {
  background: rgba(255, 255, 255, 0.08);
  color: #f2ebe0;
}

.citation-badge--web {
  background: rgba(255, 114, 94, 0.14);
  color: #ff9b82;
}

.citation-badge--default {
  background: rgba(255, 255, 255, 0.08);
  color: #f2ebe0;
}

.citation-action {
  color: #8f8f8f;
  font-size: 20rpx;
}

.assistant-composer {
  flex-shrink: 0;
  position: relative;
  padding: 18rpx 18rpx 16rpx;
  border: 1rpx solid rgba(255, 192, 0, 0.18);
  background:
    radial-gradient(circle at top right, rgba(255, 192, 0, 0.14), transparent 34%),
    linear-gradient(180deg, rgba(255, 192, 0, 0.05), rgba(255, 255, 255, 0.025)),
    linear-gradient(180deg, rgba(17, 17, 17, 0.98), rgba(8, 8, 8, 0.98));
  box-shadow:
    0 22rpx 42rpx rgba(0, 0, 0, 0.24),
    inset 0 1rpx 0 rgba(255, 255, 255, 0.05);
}

.assistant-composer::before {
  content: '';
  position: absolute;
  left: 18rpx;
  right: 18rpx;
  top: 0;
  height: 2rpx;
  background: linear-gradient(90deg, rgba(255, 192, 0, 0.8), rgba(255, 192, 0, 0.1));
}

.composer-copy {
  display: grid;
  gap: 4rpx;
}

.composer-kicker {
  color: #ffc000;
  font-size: 18rpx;
  font-weight: 700;
  letter-spacing: 3rpx;
}

.composer-title {
  color: #f4efe4;
  font-size: 28rpx;
  font-weight: 700;
}

.composer-meta {
  padding: 8rpx 14rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.05);
  color: #bda97b;
  font-size: 20rpx;
}

.composer-editor {
  margin-top: 14rpx;
  padding: 10rpx 14rpx;
  border-radius: 18rpx;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.05), rgba(255, 255, 255, 0.03));
  border: 1rpx solid rgba(255, 255, 255, 0.04);
  box-shadow: inset 0 1rpx 0 rgba(255, 255, 255, 0.03);
}

.assistant-input {
  width: 100%;
  min-height: 68rpx;
  max-height: 160rpx;
  padding: 0;
  background: transparent;
  color: #ffffff;
  font-size: 28rpx;
  line-height: 1.65;
  box-sizing: border-box;
}

.composer-footer {
  margin-top: 14rpx;
}

.composer-hint {
  flex: 1;
  min-width: 0;
  color: #a69d8b;
  font-size: 22rpx;
  line-height: 1.55;
}

.composer-submit {
  margin: 0;
  width: 168rpx;
  height: 82rpx;
  line-height: 82rpx;
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
