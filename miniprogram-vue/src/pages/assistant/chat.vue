<script setup>
import { computed, nextTick, ref } from 'vue'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import assistant, { appendNonOverlappingText } from '../../services/assistant'
import { markdownToRichText, plainTextToRichText } from '../../services/markdown'
import { requireAuth } from '../../utils/auth'

const bootstrap = ref(null)
const messages = ref([])
const loading = ref(false)
const loadingHistory = ref(false)
const sending = ref(false)
const activeSessionId = ref('')
const scene = ref('general')
const pageContext = ref({})
const draft = ref('')
const chatScrollIntoView = ref('')
const responseStage = ref('')
const citationExpanded = ref({})

const streamingBuffers = new Map()
let scrollTimer = null
let deltaFlushTimer = null
let stageTimer = null

const draftCount = computed(() => (draft.value || '').length)
const canSend = computed(() => !!(draft.value || '').trim() && !sending.value)
const composerHint = computed(() => responseStage.value || '可以问规则、角色技能冲突、板子推荐，或者直接让我帮你复盘。')

function scrollChatToBottom() {
  chatScrollIntoView.value = ''
  nextTick(() => {
    chatScrollIntoView.value = 'chat-scroll-anchor'
  })
}

function scheduleScrollToBottom(delay = 80) {
  if (scrollTimer) return
  scrollTimer = setTimeout(() => {
    scrollTimer = null
    scrollChatToBottom()
  }, delay)
}

function clearStageTimer() {
  if (!stageTimer) return
  clearTimeout(stageTimer)
  stageTimer = null
}

function clearDeltaFlushTimer() {
  if (!deltaFlushTimer) return
  clearTimeout(deltaFlushTimer)
  deltaFlushTimer = null
}

function clearStreamingState() {
  clearStageTimer()
  clearDeltaFlushTimer()
  if (scrollTimer) {
    clearTimeout(scrollTimer)
    scrollTimer = null
  }
  streamingBuffers.clear()
}

function scheduleDeepSearchHint() {
  clearStageTimer()
  stageTimer = setTimeout(() => {
    if (sending.value && responseStage.value === '正在查资料...') {
      responseStage.value = '正在整理站内资料...'
    }
  }, 2200)
}

function renderContent(message) {
  const content = message?.content || ''
  if ((message?.contentFormat || 'PLAIN_TEXT') === 'MARKDOWN') {
    return markdownToRichText(content, { streaming: false })
  }
  return plainTextToRichText(content)
}

function normalizeCitations(citations, boards) {
  if (!citations.length || !boards.length) {
    return citations
  }
  const boardIds = new Set(boards.map((board) => String(board.id)))
  return citations.filter((citation) => {
    if (citation?.sourceType !== 'STRUCTURED') {
      return true
    }
    return !boardIds.has(String(citation.sourceId))
  })
}

function normalizeMessage(message) {
  const citations = normalizeCitations(
    Array.isArray(message?.citations) ? message.citations : [],
    Array.isArray(message?.recommendedBoards) ? message.recommendedBoards : [],
  )
  const contentFormat = message?.contentFormat || (message?.role === 'ASSISTANT' ? 'MARKDOWN' : 'PLAIN_TEXT')

  return {
    ...message,
    citations,
    contentFormat,
    renderedContent:
      message?.role === 'ASSISTANT' && !message?.isStreaming
        ? renderContent({ ...message, citations, contentFormat })
        : '',
  }
}

function appendOptimisticUserMessage(message) {
  messages.value = [
    ...messages.value,
    normalizeMessage({
      id: `local-user-${Date.now()}`,
      role: 'USER',
      content: message,
      contentFormat: 'PLAIN_TEXT',
      citations: [],
      recommendedBoards: [],
      suggestedQuestions: [],
      usedWebSearch: false,
    }),
  ]
  scheduleScrollToBottom(0)
}

function addStreamingAssistantMessage() {
  const messageId = `local-assistant-${Date.now()}`
  messages.value = [
    ...messages.value,
    normalizeMessage({
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
    }),
  ]
  scheduleScrollToBottom(0)
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

function flushStreamingDeltas() {
  clearDeltaFlushTimer()
  if (!streamingBuffers.size) {
    return
  }
  streamingBuffers.forEach((delta, messageId) => {
    patchMessage(messageId, (current) => ({
      ...current,
      content: appendNonOverlappingText(current.content || '', delta),
      isStreaming: true,
    }))
  })
  streamingBuffers.clear()
  scheduleScrollToBottom()
}

function queueStreamingDelta(messageId, delta) {
  if (!delta) return
  streamingBuffers.set(messageId, appendNonOverlappingText(streamingBuffers.get(messageId) || '', delta))
  if (deltaFlushTimer) return
  deltaFlushTimer = setTimeout(() => {
    flushStreamingDeltas()
  }, 80)
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
    retryQuestion: '',
    isStreaming: false,
  }))
  scheduleScrollToBottom()
}

function markStreamingFailed(messageId, errorMessage, retryQuestion) {
  patchMessage(messageId, (current) => {
    const partialContent = (current.content || '').trim()
    const content = partialContent
      ? `${partialContent}\n\n> 输出中断：${errorMessage || '当前消息发送失败，请稍后再试。'}`
      : `## 发送失败\n\n- ${errorMessage || '当前消息发送失败，请稍后再试。'}`
    return {
      ...current,
      content,
      contentFormat: 'MARKDOWN',
      answerType: partialContent ? 'INTERRUPTED' : 'REFUSAL',
      citations: [],
      recommendedBoards: [],
      suggestedQuestions: [],
      usedWebSearch: false,
      retryQuestion,
      isStreaming: false,
    }
  })
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
  scheduleScrollToBottom(0)
}

async function loadAll() {
  if (!requireAuth()) {
    return
  }
  loading.value = true
  try {
    await loadBootstrap()
  } catch (error) {
    uni.showToast({ title: 'AI 助手加载失败', icon: 'none' })
    loading.value = false
    return
  } finally {
    loading.value = false
  }

  if (!activeSessionId.value) {
    return
  }

  loadingHistory.value = true
  try {
    await loadMessages()
  } catch (error) {
    uni.showToast({ title: '会话历史加载失败', icon: 'none' })
  } finally {
    loadingHistory.value = false
  }
}

async function sendQuestion(question = draft.value, options = {}) {
  if (sending.value) return
  const message = (question || '').trim()
  if (!message) {
    uni.showToast({ title: '请输入问题', icon: 'none' })
    return
  }

  if (options.newSession) {
    activeSessionId.value = ''
    messages.value = []
  }

  const retryQuestion = message
  sending.value = true
  responseStage.value = '正在查资料...'
  scheduleDeepSearchHint()
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
          responseStage.value = '正在查资料...'
          scheduleDeepSearchHint()
        },
        onDelta(event) {
          const delta = event?.delta || ''
          if (!delta) return
          clearStageTimer()
          responseStage.value = '正在生成回答...'
          queueStreamingDelta(streamingMessageId, delta)
        },
        onDone(event) {
          flushStreamingDeltas()
          replaceStreamingMessage(streamingMessageId, event)
          responseStage.value = ''
        },
      },
    )

    activeSessionId.value = response.sessionId
    draft.value = ''
  } catch (error) {
    flushStreamingDeltas()
    const messageText = error instanceof Error && error.message ? error.message : '发送失败，请稍后再试'
    markStreamingFailed(streamingMessageId, messageText, retryQuestion)
    uni.showToast({ title: messageText.slice(0, 18), icon: 'none' })
  } finally {
    sending.value = false
    responseStage.value = ''
    clearStageTimer()
  }
}

function openBoard(boardId) {
  uni.navigateTo({ url: `/pages/boards/detail?id=${boardId}` })
}

function openBoardNote(board) {
  uni.navigateTo({ url: `/pages/sessions/edit?boardId=${board.id}` })
}

function openBoardJudge(board) {
  uni.navigateTo({ url: `/pages/judge/create?source=board&boardId=${board.id}` })
}

function answerTypeLabel(answerType) {
  if (answerType === 'STRUCTURED_RECOMMENDATION') return '站内推荐'
  if (answerType === 'RAG_ANSWER') return '知识库回答'
  if (answerType === 'WEB_AUGMENTED_ANSWER') return '联网增强'
  if (answerType === 'INTERRUPTED') return '输出中断'
  if (answerType === 'REFUSAL') return '边界提示'
  if (answerType === 'STREAMING') return '生成中'
  return 'AI 回答'
}

function sourceTypeLabel(sourceType) {
  if (sourceType === 'STRUCTURED') return '站内资料'
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

function isCitationExpanded(messageId) {
  return !!citationExpanded.value[String(messageId)]
}

function toggleCitationExpansion(messageId) {
  const key = String(messageId)
  citationExpanded.value = {
    ...citationExpanded.value,
    [key]: !citationExpanded.value[key],
  }
}

function visibleCitations(message) {
  const citations = Array.isArray(message?.citations) ? message.citations : []
  if (isCitationExpanded(message?.id) || citations.length <= 2) {
    return citations
  }
  return citations.slice(0, 2)
}

function hiddenCitationCount(message) {
  const citations = Array.isArray(message?.citations) ? message.citations : []
  return Math.max(citations.length - visibleCitations(message).length, 0)
}

function handleCitationAction(citation) {
  if (citation?.sourceType === 'WEB' && citation?.url) {
    uni.setClipboardData({
      data: citation.url,
      success: () => uni.showToast({ title: '链接已复制', icon: 'none' }),
    })
    return
  }

  if (
    citation?.sourceType === 'STRUCTURED'
    && citation?.sourceId
    && /^\d+$/.test(String(citation.sourceId))
    && /板子名称|规则类型|人数/.test(citation?.snippet || '')
  ) {
    openBoard(Number(citation.sourceId))
    return
  }

  const content = [citation?.title, citation?.snippet].filter(Boolean).join('\n\n')
  uni.showModal({
    title: sourceTypeLabel(citation?.sourceType),
    content: content || '当前来源暂时没有更多可展示内容。',
    showCancel: false,
  })
}

function retryFailedMessage(item) {
  if (!item?.retryQuestion) return
  sendQuestion(item.retryQuestion)
}

function retryInNewSession(item) {
  if (!item?.retryQuestion) return
  sendQuestion(item.retryQuestion, { newSession: true })
}

function startNewSession() {
  activeSessionId.value = ''
  messages.value = []
  draft.value = ''
  responseStage.value = ''
  citationExpanded.value = {}
  clearStreamingState()
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

onUnload(() => {
  clearStreamingState()
})
</script>

<template>
  <view class="page-shell assistant-page">
    <view class="assistant-shell">
      <view class="glass-card assistant-header">
        <view class="assistant-header-kicker">WOLFBOOK AI ASSISTANT</view>
        <view class="assistant-header-main">
          <view class="assistant-header-copy">
            <view class="assistant-header-title">AI 战术顾问</view>
            <view class="assistant-header-desc">规则解释、板子推荐、局后复盘和站内知识，都可以在这里快速问。</view>
          </view>
          <view class="assistant-header-actions">
            <button class="button-primary header-action" @tap="startNewSession">新建</button>
            <button class="button-ghost header-action" @tap="resetCurrentSession">重置</button>
          </view>
        </view>
        <view class="assistant-header-meta">
          <view class="hero-chip">{{ bootstrap?.appearance?.dockLabel || 'AI战术顾问' }}</view>
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
            <view class="section-desc">正在准备 AI 助手入口和快捷提问...</view>
          </view>

          <view v-if="loadingHistory && !messages.length" class="glass-card section-card welcome-panel">
            <view class="section-desc">正在同步最近一段会话记录...</view>
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
                v-if="item.role === 'ASSISTANT' && !item.isStreaming"
                class="message-content message-content--markdown"
                :nodes="item.renderedContent"
              />
              <view v-else-if="item.role === 'ASSISTANT'" class="message-content message-content--plain message-content--streaming">
                {{ item.content || '正在整理回答...' }}
              </view>
              <view v-else class="message-content message-content--plain">{{ item.content }}</view>

              <view v-if="item.recommendedBoards && item.recommendedBoards.length" class="board-stack">
                <view
                  v-for="board in item.recommendedBoards"
                  :key="board.id"
                  class="board-card"
                >
                  <image v-if="board.coverImage" class="board-cover" :src="board.coverImage" mode="aspectFill" />
                  <view class="board-copy">
                    <view class="board-name">{{ board.name }}</view>
                    <view class="section-meta">{{ board.playerCount }} 人 / {{ board.difficulty }}</view>
                    <view class="board-reason">{{ board.reason }}</view>
                    <view class="board-actions">
                      <button class="button-ghost board-action" @tap.stop="openBoard(board.id)">查看板子</button>
                      <button class="button-ghost board-action" @tap.stop="openBoardNote(board)">开笔记</button>
                      <button class="button-primary board-action" @tap.stop="openBoardJudge(board)">开法官局</button>
                    </view>
                  </view>
                </view>
              </view>

              <view v-if="false && item.citations && item.citations.length" class="citation-stack">
                <view class="citation-toolbar">
                  <view class="section-meta">参考来源</view>
                  <view
                    v-if="hiddenCitationCount(item)"
                    class="citation-toggle"
                    @tap="toggleCitationExpansion(item.id)"
                  >
                    {{ isCitationExpanded(item.id) ? '收起来源' : `展开其余 ${hiddenCitationCount(item)} 条` }}
                  </view>
                </view>
                <view
                  v-for="citation in visibleCitations(item)"
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
                  <view v-if="citation.snippet" class="citation-snippet">{{ citation.snippet }}</view>
                </view>
              </view>

              <view v-if="false && item.suggestedQuestions && item.suggestedQuestions.length && !item.isStreaming" class="followup-stack">
                <view class="section-meta">继续追问</view>
                <view class="followup-grid">
                  <view
                    v-for="question in item.suggestedQuestions"
                    :key="question"
                    class="followup-chip"
                    @tap="sendQuestion(question)"
                  >
                    {{ question }}
                  </view>
                </view>
              </view>

              <view v-if="item.retryQuestion && !item.isStreaming" class="retry-actions">
                <button class="button-ghost retry-button" @tap="retryFailedMessage(item)">重试发送</button>
                <button class="button-ghost retry-button" @tap="retryInNewSession(item)">新会话重试</button>
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
            placeholder="比如：12人进阶推荐什么板子？女巫能不能自救？这局复盘该先看谁？"
          />
        </view>

        <view class="composer-footer">
          <view class="composer-hint">{{ composerHint }}</view>
          <button class="button-primary composer-submit" :disabled="!canSend" :loading="sending" @tap="sendQuestion()">
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
  margin: 0;
  min-width: 100rpx;
  height: 64rpx;
  line-height: 64rpx;
  padding: 0 22rpx;
  border-radius: 999rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22rpx;
  font-weight: 700;
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
  grid-template-columns: 56rpx minmax(0, 1fr);
  gap: 12rpx;
  align-items: start;
}

.message-row--assistant:first-of-type {
  margin-top: 0;
}

.message-avatar {
  width: 56rpx;
  height: 56rpx;
  border-radius: 18rpx;
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
  min-width: 0;
  overflow: hidden;
  padding: 22rpx;
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
  max-width: 100%;
  overflow: hidden;
}

.message-content--plain {
  white-space: pre-wrap;
  line-height: 1.55;
  font-size: 12px;
}

.message-content--streaming {
  color: #f7f1df;
}

.message-content--markdown {
  display: block;
  word-break: break-word;
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
  font-size: 20rpx;
}

.board-reason {
  color: #cacaca;
}

.board-actions {
  display: flex;
  gap: 12rpx;
  flex-wrap: wrap;
  margin-top: 16rpx;
}

.board-action,
.retry-button {
  margin: 0;
  min-width: 0;
  height: 60rpx;
  line-height: 60rpx;
  padding: 0 18rpx;
  border-radius: 999rpx;
  font-size: 22rpx;
}

.citation-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}

.citation-toggle {
  color: #d3b562;
  font-size: 20rpx;
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

.followup-stack,
.retry-actions {
  margin-top: 20rpx;
}

.followup-grid,
.retry-actions {
  display: flex;
  gap: 12rpx;
  flex-wrap: wrap;
}

.followup-chip {
  padding: 10rpx 18rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.05);
  color: #f2ead6;
  font-size: 22rpx;
  line-height: 1.45;
  border: 1rpx solid rgba(255, 255, 255, 0.06);
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
  font-size: 30rpx;
  flex-shrink: 0;
}

.scroll-anchor {
  height: 1rpx;
}
</style>
