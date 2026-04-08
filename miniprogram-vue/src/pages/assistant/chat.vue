<script setup>
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import assistant from '../../services/assistant'
import storage from '../../services/storage'

const bootstrap = ref(null)
const sessions = ref([])
const messages = ref([])
const loading = ref(false)
const sending = ref(false)
const activeSessionId = ref('')
const scene = ref('general')
const pageContext = ref({})
const draft = ref('')

const draftCount = computed(() => (draft.value || '').length)
const canSend = computed(() => !!(draft.value || '').trim() && !sending.value)
const composerHint = computed(() => '\u53ef\u4ee5\u95ee\u89c4\u5219\u3001\u89d2\u8272\u6280\u80fd\u51b2\u7a81\uff0c\u6216\u6309\u4eba\u6570\u63a8\u8350\u677f\u5b50\u3002')

function normalizeMessage(message) {
  const citations = Array.isArray(message?.citations) ? message.citations : []
  const boards = Array.isArray(message?.recommendedBoards) ? message.recommendedBoards : []

  if (!citations.length || !boards.length) {
    return {
      ...message,
      visibleCitations: citations,
    }
  }

  const boardIds = new Set(boards.map((board) => String(board.id)))
  const visibleCitations = citations.filter((citation) => {
    if (citation?.sourceType !== 'STRUCTURED') {
      return true
    }
    return !boardIds.has(String(citation.sourceId))
  })

  return {
    ...message,
    visibleCitations,
  }
}

async function loadBootstrap() {
  bootstrap.value = await assistant.bootstrap()
  if (!activeSessionId.value && bootstrap.value?.latestSessionId) {
    activeSessionId.value = bootstrap.value.latestSessionId
  }
}

async function loadSessions() {
  sessions.value = await assistant.getSessions()
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
    await loadSessions()
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
  try {
    const response = await assistant.ask({
      sessionId: activeSessionId.value || undefined,
      message,
      scene: scene.value,
      pageContext: pageContext.value,
      clientTimestamp: new Date().toISOString(),
    })
    activeSessionId.value = response.sessionId
    draft.value = ''
    await loadSessions()
    await loadMessages()
  } catch (error) {
    const message = error instanceof Error && error.message ? error.message : '发送失败，请稍后再试'
    uni.showToast({ title: message.slice(0, 18), icon: 'none' })
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

async function chooseSession(sessionId) {
  activeSessionId.value = sessionId
  await loadMessages()
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
    await loadSessions()
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
    <view class="glass-card assistant-hero">
      <view class="assistant-hero-kicker">WOLFBOOK AI ASSISTANT</view>
      <view class="assistant-hero-title">AI 狼人顾问</view>
      <view class="assistant-hero-desc">规则、角色、板子和站内知识，都可以在这里快速问。</view>
      <view v-if="bootstrap" class="assistant-hero-meta">
        <view class="hero-chip">{{ bootstrap.appearance?.dockLabel || 'AI狼顾问' }}</view>
        <view class="hero-chip hero-chip--muted">{{ sessions.length }} 会话</view>
      </view>
    </view>

    <view class="glass-card section-card session-strip tactical-strip">
      <view class="strip-kicker">SESSION CHANNEL</view>
      <view class="session-strip-head">
        <view class="strip-copy">
          <view class="section-title section-title--compact">最近会话</view>
          <view class="section-meta strip-meta">保留最近会话，便于继续追问。</view>
        </view>
        <view class="session-actions">
          <view class="mini-action" @tap="startNewSession">新建</view>
          <view class="mini-action" @tap="resetCurrentSession">重置</view>
        </view>
      </view>
      <scroll-view scroll-x class="chip-row chip-row--dense">
        <view
          v-for="item in sessions"
          :key="item.sessionId"
        class="chip chip--session"
        :class="{ active: activeSessionId === item.sessionId }"
        @tap="chooseSession(item.sessionId)"
      >
          {{ item.title || '新会话' }}
      </view>
      </scroll-view>
    </view>

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
      <view class="section-desc">正在整理最近会话和知识来源...</view>
    </view>

    <view
      v-for="item in messages"
      :key="item.id"
      class="message-row"
      :class="{ 'message-row--assistant': item.role === 'ASSISTANT' }"
    >
      <view class="message-avatar" :class="{ 'message-avatar--assistant': item.role === 'ASSISTANT' }">
        {{ item.role === 'ASSISTANT' ? 'AI' : '我' }}
      </view>
      <view class="glass-card message-bubble" :class="{ 'message-bubble--assistant': item.role === 'ASSISTANT' }">
        <view v-if="item.role === 'ASSISTANT'" class="message-bubble-head">
          <view class="answer-chip">{{ answerTypeLabel(item.answerType) }}</view>
          <view v-if="item.usedWebSearch" class="answer-chip answer-chip--web">联网补充</view>
        </view>

        <view class="message-content">{{ item.content }}</view>

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
            <view class="citation-snippet">{{ citation.snippet }}</view>
          </view>
        </view>
      </view>
    </view>

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
</template>

<style scoped lang="scss">
.assistant-page {
  padding-bottom: 344rpx;
}

.assistant-hero {
  position: relative;
  overflow: hidden;
  padding: 30rpx;
  background:
    radial-gradient(circle at right top, rgba(255, 192, 0, 0.18), transparent 34%),
    linear-gradient(180deg, rgba(18, 18, 18, 0.98), rgba(8, 8, 8, 0.98));
}

.assistant-hero::after {
  content: '';
  position: absolute;
  right: -20rpx;
  bottom: -28rpx;
  width: 220rpx;
  height: 220rpx;
  border-radius: 999rpx;
  background: radial-gradient(circle, rgba(255, 192, 0, 0.14), transparent 68%);
}

.assistant-hero-kicker {
  position: relative;
  z-index: 1;
  color: #ffc000;
  font-size: 20rpx;
  font-weight: 700;
  letter-spacing: 4rpx;
}

.assistant-hero-title {
  position: relative;
  z-index: 1;
  margin-top: 14rpx;
  font-size: 54rpx;
  font-weight: 700;
}

.assistant-hero-desc {
  position: relative;
  z-index: 1;
  margin-top: 14rpx;
  color: #d8d0bd;
  line-height: 1.8;
  font-size: 26rpx;
}

.assistant-hero-meta {
  position: relative;
  z-index: 1;
  display: flex;
  flex-wrap: wrap;
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

.hero-chip--muted {
  background: rgba(255, 255, 255, 0.06);
  color: #dedede;
}

.session-strip-head,
.session-actions,
.citation-head,
.message-bubble-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12rpx;
}

.tactical-strip {
  position: relative;
  overflow: hidden;
  border: 1rpx solid rgba(255, 192, 0, 0.12);
  background:
    radial-gradient(circle at top right, rgba(255, 192, 0, 0.08), transparent 32%),
    linear-gradient(180deg, rgba(20, 20, 20, 0.98), rgba(10, 10, 10, 0.98));
}

.tactical-strip::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 2rpx;
  background: linear-gradient(90deg, rgba(255, 192, 0, 0.75), rgba(255, 192, 0, 0.08));
}

.strip-kicker {
  color: #ffc000;
  font-size: 18rpx;
  font-weight: 700;
  letter-spacing: 3rpx;
}

.strip-copy {
  min-width: 0;
  flex: 1;
}

.strip-meta {
  margin-top: 6rpx;
  font-size: 22rpx;
  color: #9f9786;
}

.section-title--compact {
  font-size: 30rpx;
}

.chip-row--dense {
  margin-top: 16rpx;
  padding-bottom: 4rpx;
}

.chip--session {
  padding: 12rpx 18rpx;
  font-size: 22rpx;
  border: 1rpx solid rgba(255, 255, 255, 0.04);
  background: rgba(255, 255, 255, 0.04);
  color: #d1c9b6;
}

.chip--session.active {
  border-color: rgba(255, 192, 0, 0.22);
  background: linear-gradient(180deg, rgba(255, 192, 0, 0.14), rgba(255, 192, 0, 0.06));
  color: #ffc94c;
  box-shadow: inset 0 1rpx 0 rgba(255, 220, 132, 0.22);
}

.mini-action {
  padding: 10rpx 18rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.05);
  color: #ddd5c2;
  font-size: 22rpx;
  border: 1rpx solid rgba(255, 255, 255, 0.04);
}

.welcome-panel {
  margin-top: 24rpx;
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
  padding: 24rpx;
}

.message-bubble--assistant {
  border: 1rpx solid rgba(255, 192, 0, 0.12);
  background:
    linear-gradient(180deg, rgba(255, 192, 0, 0.06), rgba(255, 255, 255, 0.03)),
    linear-gradient(180deg, rgba(18, 18, 18, 0.98), rgba(10, 10, 10, 0.98));
}

.answer-chip {
  padding: 8rpx 16rpx;
  border-radius: 999rpx;
  background: rgba(255, 192, 0, 0.14);
  color: #ffc000;
  font-size: 20rpx;
  font-weight: 700;
}

.answer-chip--web {
  background: rgba(255, 255, 255, 0.06);
  color: #ece4cf;
}

.message-content {
  white-space: pre-wrap;
  line-height: 1.8;
  font-size: 28rpx;
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
  font-size: 28rpx;
  font-weight: 700;
}

.citation-badge {
  padding: 8rpx 16rpx;
  border-radius: 999rpx;
  font-size: 20rpx;
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
  font-size: 22rpx;
}

.board-reason,
.citation-snippet {
  margin-top: 10rpx;
  line-height: 1.7;
  font-size: 24rpx;
}

.board-reason {
  color: #cacaca;
}

.citation-snippet {
  color: #d8cfba;
}

.assistant-composer {
  position: fixed;
  left: 24rpx;
  right: 24rpx;
  bottom: calc(env(safe-area-inset-bottom) + 24rpx);
  padding: 18rpx 18rpx 16rpx;
  z-index: 25;
  border: 1rpx solid rgba(255, 192, 0, 0.18);
  background:
    radial-gradient(circle at top right, rgba(255, 192, 0, 0.14), transparent 34%),
    linear-gradient(180deg, rgba(255, 192, 0, 0.05), rgba(255, 255, 255, 0.025)),
    linear-gradient(180deg, rgba(17, 17, 17, 0.98), rgba(8, 8, 8, 0.98));
  box-shadow:
    0 22rpx 42rpx rgba(0, 0, 0, 0.34),
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

.composer-head,
.composer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}

.composer-head {
  margin-bottom: 14rpx;
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

@media (max-width: 520px) {
  .assistant-page {
    padding-bottom: 388rpx;
  }
}
</style>
