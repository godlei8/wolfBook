<script setup>
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import api from '../../services/api'
import storage from '../../services/storage'

const sessionId = ref('')
const sessionMode = ref('library')
const boards = ref([])
const boardsLoading = ref(false)
const boardLoadError = ref('')
const selectedBoardIndex = ref(-1)
const selectedBoardId = ref(0)

const customBoardName = ref('')
const customPlayerCount = ref(12)
const playerOptions = [6, 9, 12, 15]
const customPlayerIndex = ref(2)

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

function syncCustomPlayerIndex() {
  const index = playerOptions.findIndex((value) => value === customPlayerCount.value)
  customPlayerIndex.value = index >= 0 ? index : 2
}

function setSessionMode(mode) {
  if (mode === 'custom' && selectedBoard.value) {
    if (!customBoardName.value.trim()) {
      customBoardName.value = selectedBoard.value.name
    }
    customPlayerCount.value = selectedBoard.value.playerCount
    syncCustomPlayerIndex()
  }
  sessionMode.value = mode
}

function chooseBoard(event) {
  const index = Number(event.detail.value)
  selectedBoardIndex.value = index
  selectedBoardId.value = boards.value[index]?.id || 0
}

function chooseCustomPlayerCount(event) {
  const index = Number(event.detail.value)
  customPlayerIndex.value = index
  customPlayerCount.value = playerOptions[index]
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
      syncCustomPlayerIndex()
      return
    }
  }
  sessionMode.value = 'custom'
  customBoardName.value = session.boardName || ''
  customPlayerCount.value = session.playerCount || 12
  selectedBoardIndex.value = -1
  selectedBoardId.value = 0
  syncCustomPlayerIndex()
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

  return {
    boardMode: 'custom',
    boardId: null,
    boardName: customBoardName.value.trim(),
    playerCount: customPlayerCount.value,
  }
}

function saveSession() {
  const base = buildSessionPayload()
  if (!base) {
    return
  }

  const current = sessionId.value ? storage.getSessionById(sessionId.value) : null
  const now = new Date().toISOString()
  const session = {
    sessionId: sessionId.value || makeId('session'),
    ...base,
    createTime: current ? current.createTime : now,
    updateTime: now,
    records: current ? current.records : [],
  }

  storage.upsertSession(session)
  uni.redirectTo({ url: `/pages/sessions/detail?id=${session.sessionId}` })
}

async function initialize(options) {
  const editingSession = options?.id ? storage.getSessionById(options.id) : null
  await loadBoardOptions()
  if (editingSession) {
    hydrateSession(editingSession)
  } else {
    syncCustomPlayerIndex()
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
            {{ selectedBoardLabel }}
          </view>
        </picker>

        <view v-if="boardLoadError" class="helper-text helper-warning" @tap="loadBoardOptions">
          {{ boardLoadError }}，点击重试
        </view>

        <view v-if="selectedBoard" class="board-preview">
          <view class="board-preview-name">{{ selectedBoard.name }}</view>
          <view class="board-preview-meta">
            {{ selectedBoard.playerCount }}人 · {{ selectedBoard.difficulty }} · {{ selectedBoard.lineupSummary || selectedBoard.briefConfig }}
          </view>
          <view v-if="selectedBoard.cardDescription" class="board-preview-desc">
            {{ selectedBoard.cardDescription }}
          </view>
        </view>
      </template>

      <template v-else>
        <view class="section-meta section-space">板子名称</view>
        <input v-model="customBoardName" class="field-input" placeholder="例如：预女猎白" />

        <view class="section-meta section-space">玩家人数</view>
        <picker mode="selector" :range="playerOptions" :value="customPlayerIndex" @change="chooseCustomPlayerCount">
          <view class="field-input picker-field">{{ customPlayerCount }} 人</view>
        </picker>
      </template>
    </view>

    <button class="button-primary" @tap="saveSession">保存并进入记录</button>
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
  height: 72rpx;
  border-radius: 16rpx;
  background: rgba(255, 255, 255, 0.04);
  color: #c7c7c7;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24rpx;
  font-weight: 700;
}

.mode-chip.active {
  background: rgba(255, 192, 0, 0.16);
  color: #ffc000;
}

.section-space {
  margin-top: 22rpx;
}

.picker-field {
  display: flex;
  align-items: center;
  min-height: 88rpx;
}

.picker-field.disabled {
  color: #6f6f6f;
}

.helper-text {
  margin-top: 12rpx;
  font-size: 22rpx;
  line-height: 1.6;
}

.helper-warning {
  color: #f0b64f;
}

.board-preview {
  margin-top: 18rpx;
  padding: 20rpx;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.04);
}

.board-preview-name {
  font-size: 30rpx;
  font-weight: 700;
  color: #ffffff;
}

.board-preview-meta {
  margin-top: 10rpx;
  color: #b5b5b5;
  font-size: 24rpx;
  line-height: 1.7;
}

.board-preview-desc {
  margin-top: 12rpx;
  color: #8f8f8f;
  font-size: 24rpx;
  line-height: 1.7;
}
</style>
