<script setup>
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import api from '../../services/api'
import userData from '../../services/user-data'
import { createSessionModel } from '../../utils/session/normalizer'

const sessionId = ref('')
const sessionMode = ref('library')
const boards = ref([])
const boardsLoading = ref(false)
const boardLoadError = ref('')
const selectedBoardIndex = ref(-1)
const selectedBoardId = ref(0)

const customBoardName = ref('')
const customPlayerCount = ref(12)

function makeId(prefix) {
  return `${prefix}_${Date.now()}_${Math.random().toString(16).slice(2, 8)}`
}

const selectedBoard = computed(() => {
  if (selectedBoardIndex.value < 0 || selectedBoardIndex.value >= boards.value.length) {
    return null
  }
  return boards.value[selectedBoardIndex.value]
})

const selectedBoardLabel = computed(() => {
  if (boardsLoading.value) {
    return '正在加载板子库...'
  }
  if (selectedBoard.value) {
    return selectedBoard.value.pickerLabel
  }
  if (boardLoadError.value) {
    return '板子库加载失败，请改用自定义或重试'
  }
  if (!boards.value.length) {
    return '暂无可选板子'
  }
  return '点击选择小程序内置板子'
})

const customBoardPreviewName = computed(() => customBoardName.value.trim() || '未命名自定义板子')
const customPlayerLabel = computed(() => (customPlayerCount.value > 0 ? `${customPlayerCount.value} 人局` : '待填写人数'))

function setSessionMode(mode) {
  if (mode === 'custom' && selectedBoard.value) {
    if (!customBoardName.value.trim()) {
      customBoardName.value = selectedBoard.value.name
    }
    customPlayerCount.value = selectedBoard.value.playerCount
  }
  sessionMode.value = mode
}

function chooseBoard(event) {
  const index = Number(event.detail.value)
  selectedBoardIndex.value = index
  selectedBoardId.value = boards.value[index]?.id || 0
}

function handleCustomBoardNameInput(event) {
  customBoardName.value = event.detail?.value || ''
}

function handleCustomPlayerCountInput(event) {
  const digits = String(event.detail?.value || '').replace(/[^\d]/g, '')
  if (!digits) {
    customPlayerCount.value = 0
    return
  }
  customPlayerCount.value = Math.min(20, Math.max(1, Number(digits)))
}

async function loadBoardOptions() {
  boardsLoading.value = true
  boardLoadError.value = ''
  try {
    const data = await api.getBoards({ page: 1, size: 100 })
    boards.value = (data.list || []).map((item) => ({
      ...item,
      pickerLabel: `${item.name} · ${item.playerCount}人${item.difficulty ? ` · ${item.difficulty}` : ''}`,
    }))
    if (selectedBoardId.value) {
      const index = boards.value.findIndex((item) => item.id === selectedBoardId.value)
      selectedBoardIndex.value = index
    }
  } catch (error) {
    boards.value = []
    boardLoadError.value = '板子库加载失败'
    uni.showToast({ title: '板子库加载失败，可使用自定义板子', icon: 'none' })
  } finally {
    boardsLoading.value = false
  }
}

function hydrateSession(session) {
  sessionId.value = session.sessionId
  if (session.boardMode === 'library' && session.boardId) {
    const index = boards.value.findIndex((item) => item.id === session.boardId)
    if (index >= 0) {
      sessionMode.value = 'library'
      selectedBoardIndex.value = index
      selectedBoardId.value = session.boardId
      customBoardName.value = session.boardName || ''
      customPlayerCount.value = session.playerCount || 12
      return
    }
  }

  sessionMode.value = 'custom'
  customBoardName.value = session.boardName || ''
  customPlayerCount.value = session.playerCount || 12
  selectedBoardIndex.value = -1
  selectedBoardId.value = 0
}

function buildSessionPayload() {
  if (sessionMode.value === 'library') {
    if (!selectedBoard.value) {
      uni.showToast({ title: '请选择小程序中的板子', icon: 'none' })
      return null
    }
    return {
      boardMode: 'library',
      boardId: selectedBoard.value.id,
      boardName: selectedBoard.value.name,
      playerCount: selectedBoard.value.playerCount,
    }
  }

  if (!customBoardName.value.trim()) {
    uni.showToast({ title: '请输入自定义板子名称', icon: 'none' })
    return null
  }
  if (!Number.isFinite(customPlayerCount.value) || customPlayerCount.value <= 0) {
    uni.showToast({ title: '请输入有效玩家人数', icon: 'none' })
    return null
  }

  return {
    boardMode: 'custom',
    boardId: null,
    boardName: customBoardName.value.trim(),
    playerCount: customPlayerCount.value,
  }
}

async function saveSession() {
  const base = buildSessionPayload()
  if (!base) return

  try {
    const current = sessionId.value ? await userData.getSessionById(sessionId.value) : null
    const now = new Date().toISOString()
    const session = createSessionModel({
      sessionId: sessionId.value || makeId('session'),
      ...base,
      status: current?.status || 'active',
      currentDay: current?.currentDay || 1,
      currentPhase: current?.currentPhase || 'day_speech',
      resultCamp: current?.resultCamp || '',
      sheriffSeat: current?.sheriffSeat || null,
      players: current?.players,
      summary: current?.summary,
      createTime: current ? current.createTime : now,
      updateTime: now,
      records: current ? current.records || [] : [],
    })
    const saved = await userData.saveSession(session)
    uni.redirectTo({ url: `/pages/sessions/detail?id=${saved.sessionId}` })
  } catch (error) {
    uni.showToast({ title: error?.message || '保存失败', icon: 'none' })
  }
}

async function initialize(options) {
  let editingSession = null
  const preferredBoardId = Number(options?.boardId || 0)
  if (options?.id) {
    try {
      editingSession = await userData.getSessionById(options.id)
    } catch (error) {
      uni.showToast({ title: error?.message || '原笔记不存在，已切换为新建', icon: 'none' })
    }
  }
  await loadBoardOptions()
  if (editingSession) {
    hydrateSession(editingSession)
    return
  }

  if (preferredBoardId) {
    const index = boards.value.findIndex((item) => item.id === preferredBoardId)
    if (index >= 0) {
      sessionMode.value = 'library'
      selectedBoardIndex.value = index
      selectedBoardId.value = preferredBoardId
      customBoardName.value = boards.value[index].name
      customPlayerCount.value = boards.value[index].playerCount
    }
  }
}

onLoad((options) => {
  initialize(options)
})
</script>

<template>
  <view class="page-shell">
    <view class="glass-card section-card">
      <view class="section-title">对局基础信息</view>
      <view class="section-desc">选择站内板子快速开局，或者创建一个自定义板子继续记录本局信息。</view>

      <view class="mode-switch">
        <view class="mode-chip" :class="{ active: sessionMode === 'library' }" @tap="setSessionMode('library')">
          选择板子库
        </view>
        <view class="mode-chip" :class="{ active: sessionMode === 'custom' }" @tap="setSessionMode('custom')">
          自定义板子
        </view>
      </view>

      <template v-if="sessionMode === 'library'">
        <view class="section-meta section-space">板子选择</view>
        <picker
          mode="selector"
          :range="boards"
          range-key="pickerLabel"
          :value="selectedBoardIndex >= 0 ? selectedBoardIndex : 0"
          :disabled="!boards.length"
          @change="chooseBoard"
        >
          <view class="field-input picker-field" :class="{ disabled: !boards.length }">
            <view class="picker-copy">
              <view class="picker-kicker">BOARD LIBRARY</view>
              <view class="picker-value">{{ selectedBoardLabel }}</view>
            </view>
            <view class="picker-arrow">›</view>
          </view>
        </picker>

        <view v-if="boardLoadError" class="helper-text helper-warning" @tap="loadBoardOptions">
          {{ boardLoadError }}，点击重试
        </view>

        <view v-if="selectedBoard" class="board-preview board-preview--library">
          <image
            v-if="selectedBoard.coverImage"
            class="board-preview-cover"
            :src="selectedBoard.coverImage"
            mode="aspectFill"
          />
          <view class="board-preview-content">
            <view class="board-preview-kicker">LIBRARY BOARD</view>
            <view class="board-preview-head">
              <view class="board-preview-name">{{ selectedBoard.name }}</view>
              <view class="pill pill-gold">{{ selectedBoard.playerCount }} 人</view>
            </view>
            <view class="board-preview-meta">
              {{ selectedBoard.difficulty || '标准难度' }} · {{ selectedBoard.lineupSummary || selectedBoard.briefConfig || '已配置阵容' }}
            </view>
            <view v-if="selectedBoard.tags?.length" class="board-preview-tags">
              <view v-for="tag in selectedBoard.tags.slice(0, 4)" :key="tag" class="board-tag-chip">{{ tag }}</view>
            </view>
            <view class="board-stat-grid">
              <view class="board-stat-card">
                <view class="board-stat-label">玩家人数</view>
                <view class="board-stat-value">{{ selectedBoard.playerCount }} 人</view>
              </view>
              <view class="board-stat-card">
                <view class="board-stat-label">难度层级</view>
                <view class="board-stat-value">{{ selectedBoard.difficulty || '标准' }}</view>
              </view>
              <view class="board-stat-card">
                <view class="board-stat-label">阵容配置</view>
                <view class="board-stat-value">{{ selectedBoard.roles?.length || 0 }} 个角色位</view>
              </view>
            </view>
            <view v-if="selectedBoard.cardRoles?.length" class="board-role-strip">
              <view
                v-for="role in selectedBoard.cardRoles.slice(0, 6)"
                :key="`${selectedBoard.id}_${role.roleId}`"
                class="board-role-pill"
                :class="`board-role-pill--${role.toneClass || 'good'}`"
              >
                {{ role.name }}
              </view>
            </view>
            <view v-if="selectedBoard.cardDescription" class="board-preview-desc">
              {{ selectedBoard.cardDescription }}
            </view>
          </view>
        </view>

        <view v-else class="board-preview board-preview--ghost">
          <view class="board-preview-kicker">BOARD LIBRARY</view>
          <view class="board-preview-name">从站内板子库快速开局</view>
          <view class="board-preview-meta">优先复用已经整理好的板子封面、人数、难度和阵容信息。</view>
          <view class="board-preview-desc">选中板子后，这里会显示封面预览、阵容摘要和角色情报，方便你确认本局模板。</view>
        </view>
      </template>

      <template v-else>
        <view class="custom-preview">
          <view class="custom-kicker">CUSTOM BOARD</view>
          <view class="custom-name">{{ customBoardPreviewName }}</view>
          <view class="custom-meta">{{ customPlayerLabel }} · 笔记记录专用</view>
          <view class="custom-desc">适合临时组板、线下复盘或局中快速开一局，不依赖站内板库也能继续记笔记。</view>
        </view>

        <view class="section-meta section-space">板子名称</view>
        <input
          :value="customBoardName"
          class="field-input board-name-input"
          maxlength="20"
          confirm-type="done"
          placeholder="例如：预女猎白 / 机械狼通灵师"
          @input="handleCustomBoardNameInput"
        />

        <view class="section-meta section-space">玩家人数</view>
        <view class="player-input-wrap">
          <input
            :value="customPlayerCount > 0 ? String(customPlayerCount) : ''"
            class="field-input player-count-input"
            type="number"
            maxlength="2"
            confirm-type="done"
            placeholder="手动输入人数"
            @input="handleCustomPlayerCountInput"
          />
          <view class="player-count-unit">人</view>
        </view>
        <view class="helper-text player-helper">
          建议填写 6-15 人，支持手动调整特殊人数板型。
        </view>
        <view class="player-quick-summary">
          <view class="player-summary-card">
            <view class="player-summary-label">当前人数</view>
            <view class="player-summary-value">{{ customPlayerCount > 0 ? `${customPlayerCount} 人` : '未填写' }}</view>
          </view>
          <view class="player-summary-card">
            <view class="player-summary-label">记录模式</view>
            <view class="player-summary-value">自定义板子</view>
          </view>
        </view>
      </template>
    </view>

    <button class="button-primary save-button" @tap="saveSession">保存并进入记录</button>
  </view>
</template>

<style scoped lang="scss">
.mode-switch {
  display: flex;
  gap: 16rpx;
  margin-top: 24rpx;
}

.mode-chip {
  flex: 1;
  height: 78rpx;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.04);
  color: #c7c7c7;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26rpx;
  font-weight: 700;
}

.mode-chip.active {
  background: linear-gradient(180deg, rgba(108, 84, 24, 0.94) 0%, rgba(64, 49, 15, 0.98) 100%);
  color: #f0c35b;
  box-shadow: inset 0 0 0 2rpx rgba(242, 194, 84, 0.04);
}

.section-space {
  margin-top: 24rpx;
}

.picker-field {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 88rpx;
}

.picker-field.disabled {
  color: #6f6f6f;
}

.picker-copy {
  min-width: 0;
  flex: 1;
}

.picker-kicker,
.board-preview-kicker,
.custom-kicker {
  color: #ffc000;
  font-size: 20rpx;
  font-weight: 700;
  letter-spacing: 4rpx;
}

.picker-value {
  margin-top: 6rpx;
  color: #ffffff;
  font-size: 28rpx;
  line-height: 1.4;
}

.picker-arrow {
  margin-left: 18rpx;
  color: #ffc000;
  font-size: 38rpx;
  line-height: 1;
}

.helper-text {
  margin-top: 12rpx;
  font-size: 22rpx;
  line-height: 1.6;
}

.helper-warning {
  color: #f0b64f;
}

.board-preview,
.custom-preview {
  margin-top: 18rpx;
  padding: 22rpx;
  border-radius: 20rpx;
  background:
    radial-gradient(circle at top right, rgba(255, 192, 0, 0.1), transparent 40%),
    rgba(255, 255, 255, 0.04);
  border: 1rpx solid rgba(255, 192, 0, 0.08);
}

.board-preview--library {
  padding: 0;
  overflow: hidden;
}

.board-preview--ghost {
  background:
    radial-gradient(circle at top right, rgba(255, 192, 0, 0.12), transparent 42%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.05), rgba(255, 255, 255, 0.03));
}

.board-preview-cover {
  width: 100%;
  height: 240rpx;
  display: block;
}

.board-preview-content {
  padding: 22rpx;
}

.board-preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}

.board-preview-name,
.custom-name {
  font-size: 32rpx;
  font-weight: 700;
  color: #ffffff;
}

.board-preview-meta,
.custom-meta {
  margin-top: 10rpx;
  color: #b5b5b5;
  font-size: 24rpx;
  line-height: 1.7;
}

.board-preview-desc,
.custom-desc {
  margin-top: 12rpx;
  color: #8f8f8f;
  font-size: 24rpx;
  line-height: 1.7;
}

.board-preview-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 16rpx;
}

.board-tag-chip,
.board-role-pill {
  padding: 10rpx 18rpx;
  border-radius: 999rpx;
  font-size: 22rpx;
  line-height: 1;
}

.board-tag-chip {
  background: rgba(255, 192, 0, 0.12);
  color: #ffd45c;
  border: 1rpx solid rgba(255, 192, 0, 0.18);
}

.board-stat-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12rpx;
  margin-top: 18rpx;
}

.board-stat-card {
  min-width: 0;
  padding: 16rpx 14rpx;
  border-radius: 16rpx;
  background: rgba(255, 255, 255, 0.045);
  border: 1rpx solid rgba(255, 255, 255, 0.05);
}

.board-stat-label {
  color: #8f8f8f;
  font-size: 20rpx;
}

.board-stat-value {
  margin-top: 8rpx;
  color: #ffffff;
  font-size: 24rpx;
  line-height: 1.35;
  font-weight: 700;
}

.board-role-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 18rpx;
}

.board-role-pill {
  color: #f5f5f5;
  background: rgba(255, 255, 255, 0.06);
}

.board-role-pill--good {
  background: rgba(255, 192, 0, 0.12);
  color: #ffe082;
}

.board-role-pill--wolf {
  background: rgba(183, 70, 58, 0.22);
  color: #ff9d8d;
}

.board-role-pill--third {
  background: rgba(115, 96, 166, 0.22);
  color: #d2c2ff;
}

.board-name-input {
  min-height: 84rpx;
  line-height: 84rpx;
  font-size: 26rpx;
}

.player-input-wrap {
  position: relative;
  margin-top: 14rpx;
}

.player-count-input {
  min-height: 84rpx;
  line-height: 84rpx;
  padding-right: 92rpx;
  font-size: 28rpx;
}

.player-count-unit {
  position: absolute;
  top: 50%;
  right: 26rpx;
  transform: translateY(-50%);
  color: #ffc000;
  font-size: 26rpx;
  font-weight: 700;
}

.player-helper {
  margin-top: 10rpx;
  color: #8f8f8f;
}

.player-quick-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14rpx;
  margin-top: 16rpx;
}

.player-summary-card {
  min-height: 88rpx;
  padding: 18rpx 20rpx;
  border-radius: 20rpx;
  background: rgba(255, 255, 255, 0.05);
  border: 1rpx solid rgba(255, 255, 255, 0.05);
  box-sizing: border-box;
}

.player-summary-label {
  color: #8f8f8f;
  font-size: 22rpx;
}

.player-summary-value {
  margin-top: 8rpx;
  color: #ffffff;
  font-size: 28rpx;
  font-weight: 700;
  line-height: 1.2;
}

.save-button {
  margin-top: 28rpx;
}
</style>
