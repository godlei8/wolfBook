<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import assistant from '../../services/assistant'
import { markdownToRichText } from '../../services/markdown'
import { formatDateTime, fromNow } from '../../utils/format'
import { normalizeChatMessage } from './message-model'
import { createAssistantStreamBuffer, shouldAutoScrollOnMessageAppend } from './stream-buffer'
import storage from '../../services/storage'
import api from '../../services/api'
import NavBar from '../../components/NavBar.vue'

const bootstrap = ref(null)
const sessions = ref([])
const messages = ref([])
const loading = ref(false)
const loadingHistory = ref(false)
const sending = ref(false)
const activeSessionId = ref('')
const scene = ref('general')
const pageContext = ref({})
const draft = ref('')
let activeStream = null
const chatScrollIntoView = ref('')
const loadError = ref('')
// 原型 D5：顶部的「正在看：某板子」上下文条、历史会话开关、从别的页面带过来的问题
const contextAvatars = ref([])
const showHistory = ref(false)
// 回答下面的「出处」角色卡：问题或回答里提到的角色（原型 D5）
const roleIndex = ref([])
let pendingQuestion = ''

const streamRenderDelayMs = 120
// 还没出字时的等待卡片：后端报的进度（「正在查站内资料」）和已经等了几秒
const waitSeconds = ref(0)
let waitTimer = null

const draftCount = computed(() => (draft.value || '').length)
const assistantEnabled = computed(() => bootstrap.value?.enabled !== false)
const canSend = computed(() => assistantEnabled.value && !!(draft.value || '').trim() && !sending.value)
// 每条回答配一张角色卡：先看前一条提问里点到的角色，再看回答本身
const answerRoles = computed(() => {
  const map = {}
  let lastQuestion = ''
  messages.value.forEach((item) => {
    if (item.role === 'USER') {
      lastQuestion = item.content || ''
      return
    }
    if (item.isStreaming) return
    map[item.id] = pickSourceRole(lastQuestion, item.content || '')
  })
  return map
})
const sourceLabel = computed(() => (pageContext.value?.boardName ? `出处：${pageContext.value.boardName} · 角色细则` : '出处：角色图鉴'))

function findRole(text) {
  if (!text) return null
  // 长名字优先，避免「狼人」抢掉「白狼王」
  return roleIndex.value.find((role) => text.includes(role.name)) || null
}

// 问题里点到的角色可能不止一个（「狼美人骑士里骑士怎么决斗」里板子名带着狼美人），优先选回答里也讲到的那个
function pickSourceRole(question, answer) {
  const asked = question ? roleIndex.value.filter((role) => question.includes(role.name)) : []
  return asked.find((role) => answer.includes(role.name)) || asked[0] || findRole(answer)
}

async function loadRoleIndex() {
  try {
    const roles = await api.getRoles()
    roleIndex.value = roles.filter((role) => role.name && role.name !== '平民').sort((a, b) => b.name.length - a.name.length)
  } catch (error) {
    roleIndex.value = []
  }
}

function openRole(id) {
  uni.navigateTo({ url: `/pages/roles/detail?id=${id}` })
}

function openBoard(id) {
  uni.navigateTo({ url: `/pages/boards/detail?id=${id}` })
}

function trySend() {
  if (!canSend.value) return
  sendQuestion()
}

function openTools() {
  const items = [{ label: '新会话', run: startNewSession }]
  if (sessions.value.length) items.push({ label: showHistory.value ? '收起历史会话' : `历史会话（${sessions.value.length}）`, run: () => (showHistory.value = !showHistory.value) })
  if (latestSessionId.value && latestSessionId.value !== activeSessionId.value) items.push({ label: '恢复最近一次', run: resumeLatestSession })
  if (activeSessionId.value) items.push({ label: '清空当前会话', run: resetCurrentSession })
  uni.showActionSheet({
    itemList: items.map((item) => item.label),
    success: (res) => items[res.tapIndex]?.run(),
  })
}
const quickQuestions = computed(() => bootstrap.value?.quickQuestions || [])
const latestSessionId = computed(() => bootstrap.value?.latestSessionId || '')
const hasMessages = computed(() => messages.value.length > 0)
const showEmptyState = computed(() => !loading.value && !loadingHistory.value && !hasMessages.value)
const contextLabel = computed(() => {
  const ctx = pageContext.value || {}
  if (ctx.boardName) return `正在看：${ctx.boardName}，回答会按这个板子的规则来`
  if (ctx.roleName) return `正在看：${ctx.roleName}，回答会围绕这个角色`
  if (ctx.phase) return `正在主持：${ctx.boardName || '当前对局'}`
  return ''
})

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

function normalizeMessage(message) {
  return normalizeChatMessage(message, {
    markdownRenderer: (content) => markdownToRichText(content, { streaming: !!message?.isStreaming }),
  })
}

function patchMessage(messageId, patcher) {
  messages.value = messages.value.map((item) => (String(item.id) === String(messageId) ? normalizeMessage(patcher(item)) : item))
}

function startWaitClock() {
  stopWaitClock()
  waitSeconds.value = 0
  const startedAt = Date.now()
  waitTimer = setInterval(() => {
    waitSeconds.value = Math.floor((Date.now() - startedAt) / 1000)
  }, 1000)
}

function stopWaitClock() {
  if (waitTimer) {
    clearInterval(waitTimer)
    waitTimer = null
  }
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
    stageLabel: '正在理解问题',
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

  messages.value = messages.value.map((item) => (String(item.id) === String(messageId) ? nextMessage : item))
  scrollChatToBottom()
}

function markStreamingFailed(messageId, errorMessage) {
  patchMessage(messageId, () => ({
    role: 'ASSISTANT',
    content: `**没答上来**：${errorMessage || '请稍后再试一次。'}`,
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
    messages.value = []
    activeSessionId.value = ''
    return
  }

  loadingHistory.value = true
  loadError.value = ''
  activeSessionId.value = sessionId

  try {
    const rawMessages = await assistant.getMessages(sessionId)
    messages.value = rawMessages.map((message) => normalizeMessage(message))
    scrollChatToBottom()
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
  // 从板子 / 角色页的问题条进来：加载好就直接问
  if (pendingQuestion && !loadError.value) {
    const question = pendingQuestion
    pendingQuestion = ''
    sendQuestion(question)
  }
}

async function loadContextAvatars() {
  const boardId = Number(pageContext.value?.boardId || 0)
  if (!boardId) return
  try {
    const board = await api.getBoardDetail(boardId)
    contextAvatars.value = (board.cardRoles || []).filter((role) => role.portrait).slice(0, 4)
  } catch (error) {
    contextAvatars.value = []
  }
}

async function sendQuestion(question) {
  if (sending.value || !assistantEnabled.value) return

  // 只有发送的是输入框草稿时才清空草稿；快捷提问不能把用户正在写的内容清掉
  const usingDraft = question === undefined || question === null
  const message = (usingDraft ? draft.value : question || '').trim()
  if (!message) {
    uni.showToast({ title: '请输入问题', icon: 'none' })
    return
  }
  if (usingDraft) {
    draft.value = ''
  }

  loadError.value = ''
  sending.value = true
  appendOptimisticUserMessage(message)
  const streamingMessageId = addStreamingAssistantMessage()
  startWaitClock()
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
        onStage(event) {
          if (!event?.label) return
          patchMessage(streamingMessageId, (current) => ({ ...current, stageLabel: event.label }))
        },
        onDelta(event) {
          const delta = event?.delta || ''
          if (!delta) return
          stopWaitClock()
          streamBuffer.push(delta)
        },
        onDone(event) {
          streamBuffer.flush()
          replaceStreamingMessage(streamingMessageId, event)
        },
        onRequestTask(task) {
          activeStream = task
        },
      },
    )

    activeSessionId.value = response.sessionId
    loadSessionList().catch(() => {})
  } catch (error) {
    const messageText = error instanceof Error && error.message ? error.message : '发送失败，请稍后再试'
    markStreamingFailed(streamingMessageId, messageText)
    uni.showToast({ title: messageText.slice(0, 18), icon: 'none' })
    if (usingDraft && !draft.value) {
      draft.value = message
    }
  } finally {
    activeStream = null
    streamBuffer.dispose()
    stopWaitClock()
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
  showHistory.value = false
  await loadMessages(sessionId)
}

function startNewSession() {
  loadError.value = ''
  activeSessionId.value = ''
  messages.value = []
  draft.value = ''
}

function resetCurrentSession() {
  if (!activeSessionId.value) {
    startNewSession()
    return
  }
  uni.showModal({
    title: '清空当前会话',
    content: '会删除这个会话里的全部对话记录，且无法恢复。',
    confirmText: '清空',
    confirmColor: '#ff4d4f',
    success: (res) => {
      if (res.confirm) {
        resetCurrentSessionNow()
      }
    },
  })
}

async function resetCurrentSessionNow() {
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
  pendingQuestion = decodeURIComponent(options?.question || '')
  loadAll()
  loadContextAvatars()
  loadRoleIndex()
})

onUnload(() => {
  stopWaitClock()
  if (activeStream) {
    activeStream.abort()
    activeStream = null
  }
})
</script>

<template>
  <view class="nt-page chat-page">
    <NavBar back title="规则速查">
      <template #side>
        <view class="chat-more" @tap="openTools"><view class="chat-more__dot" /><view class="chat-more__dot" /><view class="chat-more__dot" /></view>
      </template>
    </NavBar>

    <view class="nt-gutter chat-top">
      <view v-if="contextLabel" class="chat-context">
        <view v-if="contextAvatars.length" class="chat-context__stack">
          <view
            v-for="(role, index) in contextAvatars"
            :key="role.roleId"
            class="nt-token chat-context__token"
            :style="{ marginLeft: index ? '-16rpx' : '0', zIndex: 4 - index }"
          >
            <image class="nt-token__img" :src="role.portrait" mode="aspectFill" :style="{ borderColor: role.accent }" webp />
          </view>
        </view>
        <text class="chat-context__text">{{ contextLabel }}</text>
      </view>

      <view v-if="showHistory && sessions.length" class="chat-history">
        <view
          v-for="item in sessions.slice(0, 8)"
          :key="item.sessionId"
          class="nt-row chat-history__row"
          :class="{ 'chat-history__row--on': item.sessionId === activeSessionId }"
          @tap="openSession(item.sessionId)"
        >
          <text class="chat-history__title">{{ item.title }}</text>
          <text class="nt-meta">{{ item.relativeUpdate || item.updateLabel }}</text>
        </view>
      </view>
    </view>

    <scroll-view class="chat-scroll" scroll-y :show-scrollbar="false" :scroll-into-view="chatScrollIntoView">
      <view class="nt-gutter chat-list">
        <view v-if="loadError" class="nt-panel chat-state">
          <text class="nt-h2">当前加载失败</text>
          <text class="nt-meta">{{ loadError }}</text>
          <view class="chat-state__actions">
            <view class="nt-btn nt-btn--gold nt-btn--small" @tap="retryCurrentView">重新加载</view>
            <view v-if="!storage.getAuthToken()" class="nt-btn nt-btn--ghost nt-btn--small" @tap="goLogin">去登录</view>
          </view>
        </view>

        <view v-if="showEmptyState && !loadError" class="chat-empty">
          <view class="chat-avatar">☾</view>
          <view class="chat-bubble">
            <text>我是法官台的规则速查。问我板子规则、角色技能或对局里的判定，会尽量按你正在看的板子来回答。</text>
          </view>
        </view>

        <view v-if="(loading || loadingHistory) && !hasMessages" class="nt-empty">
          {{ loading ? '正在连接规则速查…' : '正在载入历史对话…' }}
        </view>

        <view v-for="item in messages" :key="item.id" :id="`message-${item.id}`">
          <view v-if="item.role === 'USER'" class="chat-self">
            <text class="chat-self__bubble">{{ item.content }}</text>
          </view>
          <view v-else class="chat-answer">
            <view class="chat-avatar">☾</view>
            <view class="chat-answer__body">
              <view v-if="item.isStreaming && !item.content" class="chat-bubble chat-wait">
                <view class="chat-wait__moon" />
                <view class="chat-wait__body">
                  <view class="chat-wait__row">
                    <text class="chat-wait__label">{{ item.stageLabel || '正在理解问题' }}</text>
                    <view class="chat-wait__dots">
                      <view class="chat-wait__dot" />
                      <view class="chat-wait__dot chat-wait__dot--2" />
                      <view class="chat-wait__dot chat-wait__dot--3" />
                    </view>
                  </view>
                  <text v-if="waitSeconds >= 8" class="chat-wait__hint">已等 {{ waitSeconds }} 秒，模型在组织语言，马上就好</text>
                </view>
              </view>
              <view v-else class="chat-bubble">
                <rich-text v-if="item.renderMode === 'markdown'" class="chat-markdown" :nodes="item.renderedContent" />
                <text v-else class="chat-plain">{{ item.content }}</text>
              </view>
              <view v-for="board in item.recommendedBoards || []" :key="board.id" class="chat-ref" @tap="openBoard(board.id)">
                <view class="chat-ref__mark"><text>{{ board.playerCount }}</text></view>
                <view class="chat-ref__text">
                  <text class="chat-ref__name">{{ board.name }}</text>
                  <text class="chat-ref__meta">{{ board.playerCount }} 人 · {{ board.difficulty }}</text>
                </view>
                <text class="nt-arrow">›</text>
              </view>
              <view v-if="answerRoles[item.id]" class="chat-ref" @tap="openRole(answerRoles[item.id].id)">
                <view class="nt-token chat-ref__token">
                  <image v-if="answerRoles[item.id].portrait" class="nt-token__img" :src="answerRoles[item.id].portrait" mode="aspectFill" :style="{ borderColor: answerRoles[item.id].accent }" webp />
                  <view v-else class="nt-token__text" :style="{ borderColor: answerRoles[item.id].accent }">{{ answerRoles[item.id].name.slice(0, 1) }}</view>
                </view>
                <view class="chat-ref__text">
                  <text class="chat-ref__name">{{ answerRoles[item.id].name }}</text>
                  <text class="chat-ref__meta">{{ sourceLabel }}</text>
                </view>
                <text class="nt-arrow">›</text>
              </view>
              <view v-if="item.visibleCitations && item.visibleCitations.length" class="chat-cite">
                <text class="chat-cite__label">联网来源</text>
                <text v-for="(cite, index) in item.visibleCitations.slice(0, 3)" :key="index" class="chat-cite__item">{{ index + 1 }}. {{ cite.title || cite.url }}</text>
              </view>
            </view>
          </view>
        </view>

        <view id="chat-scroll-anchor" class="chat-anchor" />
      </view>
    </scroll-view>

    <view class="chat-composer">
      <scroll-view v-if="quickQuestions.length" scroll-x class="nt-chips" :show-scrollbar="false">
        <view class="nt-chips__inner chat-quick">
          <text v-for="item in quickQuestions" :key="item" class="nt-chip" @tap="sendQuestion(item)">{{ item }}</text>
        </view>
      </scroll-view>
      <view class="chat-input-row">
        <view class="chat-input">
          <textarea
            v-model="draft"
            class="chat-input__field"
            maxlength="300"
            auto-height
            :show-confirm-bar="false"
            :cursor-spacing="24"
            placeholder="问规则、问打法"
            placeholder-class="nt-placeholder"
          />
          <text v-if="draftCount" class="chat-input__count">{{ draftCount }}/300</text>
        </view>
        <view class="nt-btn nt-btn--gold chat-send" :class="{ 'nt-btn--disabled': sending }" @tap="trySend">
          <text v-if="sending">…</text>
          <image v-else class="chat-send__plane" src="/static/icons/send.png" mode="aspectFit" />
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.chat-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chat-top {
  flex-shrink: 0;
  padding-top: 8rpx;
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}

.chat-context {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 20rpx 24rpx;
  border-radius: 28rpx;
  background: var(--panel);
  border: 2rpx solid var(--panel-line);
}

.chat-context__stack {
  display: flex;
  padding-left: 4rpx;
  flex-shrink: 0;
}

.chat-context__token {
  position: relative;
  width: 56rpx;
  height: 56rpx;
}

.chat-context__text {
  flex: 1;
  min-width: 0;
  color: var(--text-2);
  font-size: 26rpx;
  line-height: 1.5;
}

.chat-more {
  width: 72rpx;
  height: 72rpx;
  border-radius: 24rpx;
  border: 4rpx solid var(--ink);
  background: var(--panel);
  box-shadow: 4rpx 4rpx 0 var(--ink);
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
}

.chat-more__dot {
  width: 8rpx;
  height: 8rpx;
  margin: 0 3rpx;
  border-radius: 50%;
  background: var(--text);
}

.chat-history {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
  max-height: 520rpx;
  overflow-y: auto;
}

.chat-history__row--on {
  border: 3rpx solid var(--moon);
}

.chat-history__title {
  min-width: 0;
  flex: 1;
  font-size: 28rpx;
  font-weight: 700;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.chat-scroll {
  flex: 1;
  min-height: 0;
}

.chat-list {
  padding-top: 32rpx;
  padding-bottom: 24rpx;
  display: flex;
  flex-direction: column;
  gap: 32rpx;
}

.chat-state__actions {
  display: flex;
  gap: 16rpx;
}

.chat-self {
  display: flex;
  justify-content: flex-end;
}

.chat-self__bubble {
  max-width: 78%;
  padding: 24rpx 28rpx;
  border-radius: 32rpx 32rpx 8rpx 32rpx;
  background: var(--text);
  color: var(--ink);
  border: 4rpx solid var(--ink);
  box-shadow: 4rpx 4rpx 0 var(--ink);
  font-size: 30rpx;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  box-sizing: border-box;
}

.chat-answer,
.chat-empty {
  display: flex;
  align-items: flex-start;
  gap: 20rpx;
}

.chat-avatar {
  width: 72rpx;
  height: 72rpx;
  border-radius: 24rpx;
  background: var(--panel-raised);
  border: 4rpx solid var(--ink);
  color: var(--gold);
  font-size: 36rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  flex-shrink: 0;
}

.chat-bubble {
  flex: 1;
  min-width: 0;
  max-width: 86%;
  padding: 28rpx;
  border-radius: 8rpx 32rpx 32rpx 32rpx;
  background: var(--panel);
  border: 2rpx solid var(--panel-line);
  color: var(--text);
  font-size: 30rpx;
  line-height: 1.75;
  box-sizing: border-box;
}

.chat-markdown {
  display: block;
}

.chat-plain {
  white-space: pre-wrap;
  word-break: break-word;
}

.chat-wait {
  display: flex;
  align-items: center;
  gap: 20rpx;
  min-width: 360rpx;
}

.chat-wait__moon {
  flex-shrink: 0;
  width: 36rpx;
  height: 36rpx;
  border-radius: 50%;
  background: var(--moon);
  box-shadow: 0 0 0 0 rgba(95, 227, 224, 0.5);
  animation: chat-wait-pulse 1.6s ease-out infinite;
}

.chat-wait__body {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.chat-wait__row {
  display: flex;
  align-items: center;
  gap: 12rpx;
}

.chat-wait__label {
  color: var(--text);
  font-size: 28rpx;
}

.chat-wait__dots {
  display: flex;
  align-items: center;
  gap: 8rpx;
}

.chat-wait__dot {
  width: 10rpx;
  height: 10rpx;
  border-radius: 50%;
  background: var(--gold);
  opacity: 0.25;
  animation: chat-wait-dot 1.2s ease-in-out infinite;
}

.chat-wait__dot--2 {
  animation-delay: 0.2s;
}

.chat-wait__dot--3 {
  animation-delay: 0.4s;
}

.chat-wait__hint {
  color: var(--text-3);
  font-size: 22rpx;
}

@keyframes chat-wait-pulse {
  0% {
    box-shadow: 0 0 0 0 rgba(95, 227, 224, 0.5);
  }
  70% {
    box-shadow: 0 0 0 18rpx rgba(95, 227, 224, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(95, 227, 224, 0);
  }
}

@keyframes chat-wait-dot {
  0%,
  100% {
    opacity: 0.25;
    transform: translateY(0);
  }
  50% {
    opacity: 1;
    transform: translateY(-6rpx);
  }
}

.chat-anchor {
  height: 2rpx;
}

.chat-composer {
  flex-shrink: 0;
  padding: 20rpx 0 calc(24rpx + env(safe-area-inset-bottom));
  background: var(--bar-bg);
  border-top: 4rpx solid var(--ink);
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}

.chat-quick .nt-chip {
  height: 60rpx;
  font-size: 26rpx;
}

.chat-input-row {
  padding: 0 32rpx;
  display: flex;
  align-items: flex-end;
  gap: 20rpx;
}

.chat-input {
  flex: 1;
  min-width: 0;
  min-height: 96rpx;
  padding: 22rpx 28rpx;
  border-radius: 28rpx;
  background: var(--panel);
  border: 4rpx solid var(--ink);
  box-sizing: border-box;
  position: relative;
}

.chat-input__field {
  width: 100%;
  min-height: 44rpx;
  max-height: 200rpx;
  color: var(--text);
  font-size: 30rpx;
  line-height: 1.5;
}

.chat-input__count {
  position: absolute;
  right: 20rpx;
  bottom: 6rpx;
  color: var(--text-3);
  font-size: 20rpx;
}

.chat-send {
  width: 96rpx;
  padding: 0;
  font-size: 36rpx;
  flex-shrink: 0;
}

.chat-send__plane {
  width: 44rpx;
  height: 44rpx;
}

.chat-answer__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 16rpx;
  padding-right: 24rpx;
}

.chat-answer__body .chat-bubble {
  max-width: none;
}

.chat-ref {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 20rpx 24rpx;
  border-radius: 28rpx;
  background: var(--panel-raised);
  border: 4rpx solid var(--ink);
  box-shadow: 4rpx 4rpx 0 var(--ink);
}

.chat-ref__token {
  width: 84rpx;
  height: 84rpx;
  flex-shrink: 0;
}

.chat-ref__mark {
  width: 84rpx;
  height: 84rpx;
  flex-shrink: 0;
  border-radius: 20rpx;
  background: #3a2e14;
  color: var(--gold);
  font-family: var(--serif);
  font-size: 34rpx;
  font-weight: 900;
  display: flex;
  align-items: center;
  justify-content: center;
}

.chat-ref__text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.chat-ref__name {
  font-size: 30rpx;
  font-weight: 900;
}

.chat-ref__meta {
  color: var(--text-2);
  font-size: 24rpx;
}

.chat-cite {
  display: flex;
  flex-direction: column;
  gap: 6rpx;
  padding: 0 8rpx;
}

.chat-cite__label {
  color: var(--text-3);
  font-size: 22rpx;
}

.chat-cite__item {
  color: var(--moon);
  font-size: 24rpx;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
</style>
