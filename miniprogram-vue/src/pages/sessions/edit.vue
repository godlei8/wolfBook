<script setup>
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import NavBar from '../../components/NavBar.vue'
import api from '../../services/api'
import userData from '../../services/user-data'

const sessionId = ref('')
const sessionMode = ref('library')
const boards = ref([])
const boardsLoading = ref(false)
const boardLoadError = ref('')
const selectedBoardIndex = ref(-1)
const selectedBoardId = ref(0)

const customBoardName = ref('')
const customPlayerCount = ref(12)
const saving = ref(false)

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

function stepPlayerCount(delta) {
  customPlayerCount.value = Math.min(20, Math.max(1, (customPlayerCount.value || 0) + delta))
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
  if (saving.value) return
  const base = buildSessionPayload()
  if (!base) return

  // 成功后 redirectTo 会卸载本页，saving 保持 true 避免连点生成两条笔记
  saving.value = true
  try {
    const current = sessionId.value ? await userData.getSessionById(sessionId.value) : null
    const now = new Date().toISOString()
    const session = {
      sessionId: sessionId.value || makeId('session'),
      ...base,
      createTime: current ? current.createTime : now,
      updateTime: now,
      records: current ? current.records || [] : [],
    }
    const saved = await userData.saveSession(session)
    uni.redirectTo({ url: `/pages/sessions/detail?id=${saved.sessionId}` })
  } catch (error) {
    saving.value = false
    uni.showToast({ title: error?.message || '保存失败', icon: 'none' })
  }
}

function preselectBoard(options) {
  const boardId = Number(options?.boardId || 0)
  let boardName = ''
  try {
    boardName = decodeURIComponent(options?.boardName || '')
  } catch (error) {
    boardName = options?.boardName || ''
  }
  if (boardId > 0) {
    const index = boards.value.findIndex((item) => item.id === boardId)
    if (index >= 0) {
      sessionMode.value = 'library'
      selectedBoardIndex.value = index
      selectedBoardId.value = boardId
      return
    }
  }
  if (boardName) {
    sessionMode.value = 'custom'
    customBoardName.value = boardName
  }
}

async function initialize(options) {
  let editingSession = null
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
  } else if (options?.boardId || options?.boardName) {
    // 从法官房间"记笔记"进来时带上板子，直接预选
    preselectBoard(options)
  }
}

onLoad((options) => {
  initialize(options)
})
</script>

<template>
  <view class="nt-page nt-page--bar">
    <NavBar back :title="sessionId ? '改对局信息' : '记一局'" />

    <view class="nt-gutter nt-stack se-body">
      <view class="se-modes">
        <text class="nt-chip" :class="{ 'nt-chip--on': sessionMode === 'library' }" @tap="setSessionMode('library')">从板子库选</text>
        <text class="nt-chip" :class="{ 'nt-chip--on': sessionMode === 'custom' }" @tap="setSessionMode('custom')">自定义板子</text>
      </view>

      <block v-if="sessionMode === 'library'">
        <picker
          mode="selector"
          :range="boards"
          range-key="pickerLabel"
          :value="selectedBoardIndex >= 0 ? selectedBoardIndex : 0"
          :disabled="!boards.length"
          @change="chooseBoard"
        >
          <view class="nt-row">
            <view class="se-pick">
              <text class="nt-label">板子</text>
              <text class="se-pick__value" :class="{ 'c-moon': selectedBoard }">{{ selectedBoardLabel }}</text>
            </view>
            <text class="nt-arrow">›</text>
          </view>
        </picker>
        <text v-if="boardLoadError" class="se-error" @tap="loadBoardOptions">{{ boardLoadError }}，点这里重试</text>

        <view v-if="selectedBoard" class="nt-ink se-preview">
          <view class="se-preview__banner">
            <image v-if="selectedBoard.backgroundImage" class="se-preview__bg" :src="selectedBoard.backgroundImage" mode="aspectFill" webp />
            <view
              v-for="art in selectedBoard.lineupArt || []"
              :key="art.roleId"
              class="nt-card se-preview__card"
              :class="`se-preview__card--${art.slot}`"
            >
              <view class="nt-card__frame" :style="{ backgroundColor: art.accent }">
                <image class="nt-card__img" :src="art.image" mode="aspectFill" webp />
              </view>
            </view>
            <text class="se-preview__plate serif">{{ selectedBoard.name }}</text>
          </view>
          <view class="se-preview__body">
            <text class="nt-meta">{{ selectedBoard.playerCount }} 人 · {{ selectedBoard.difficulty || '标准' }}{{ selectedBoard.winCondition ? ` · ${selectedBoard.winCondition}` : '' }}</text>
            <view class="se-tokens">
              <view v-for="role in selectedBoard.cardRoles || []" :key="role.roleId" class="nt-token se-token">
                <image v-if="role.portrait" class="nt-token__img" :src="role.portrait" mode="aspectFill" :style="{ borderColor: role.accent }" webp />
                <view v-else class="nt-token__text se-token__text" :style="{ borderColor: role.accent }">{{ role.name.slice(0, 1) }}</view>
                <text v-if="role.count > 1" class="nt-token__count">×{{ role.count }}</text>
              </view>
            </view>
          </view>
        </view>
        <view v-else class="nt-panel">
          <text class="nt-h2">从板子库快速开局</text>
          <text class="nt-meta">选好板子后，人数和阵容直接带进笔记，局中只管记发言、票型和夜间信息。</text>
        </view>
      </block>

      <block v-else>
        <view class="nt-panel">
          <text class="nt-label">板子名称</text>
          <view class="nt-field">
            <input
              :value="customBoardName"
              class="nt-field__input"
              maxlength="20"
              confirm-type="done"
              placeholder="例如：预女猎白 / 机械狼通灵师"
              placeholder-class="nt-placeholder"
              @input="handleCustomBoardNameInput"
            />
          </view>
          <text class="nt-label">玩家人数</text>
          <view class="se-count">
            <view class="nt-iconbtn" @tap="stepPlayerCount(-1)">－</view>
            <input
              :value="customPlayerCount > 0 ? String(customPlayerCount) : ''"
              class="se-count__input serif"
              type="number"
              maxlength="2"
              placeholder="12"
              placeholder-class="nt-placeholder"
              @input="handleCustomPlayerCountInput"
            />
            <view class="nt-iconbtn" @tap="stepPlayerCount(1)">＋</view>
            <text class="nt-meta">人</text>
          </view>
          <text class="nt-meta">建议 6 ~ 15 人，特殊板型可以手动改。</text>
        </view>
      </block>
    </view>

    <view class="nt-bar">
      <view class="nt-btn nt-btn--gold se-save" :class="{ 'nt-btn--disabled': saving }" @tap="saveSession">
        {{ saving ? '保存中…' : '保存并开始记录' }}
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.se-body {
  margin-top: 16rpx;
}

.se-modes {
  display: flex;
  gap: 16rpx;
}

.se-pick {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
  min-width: 0;
}

.se-pick__value {
  font-size: 30rpx;
  font-weight: 700;
}

.se-error {
  color: var(--camp-wolf);
  font-size: 26rpx;
}

.se-preview {
  overflow: hidden;
}

.se-preview__banner {
  position: relative;
  height: 264rpx;
  background: #1b1724;
  border-bottom: 4rpx solid var(--ink);
  overflow: hidden;
}

.se-preview__bg {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
}

.se-preview__card {
  position: absolute;
  width: 124rpx;
  border-radius: 16rpx;
  box-shadow: 4rpx 4rpx 0 var(--ink);
}

.se-preview__card--l1 {
  left: 330rpx;
  top: 60rpx;
  transform: rotate(-10deg);
}

.se-preview__card--c {
  left: 430rpx;
  top: 32rpx;
  z-index: 2;
}

.se-preview__card--r1 {
  left: 530rpx;
  top: 60rpx;
  transform: rotate(10deg);
}

.se-preview__plate {
  position: absolute;
  left: 0;
  bottom: 0;
  z-index: 3;
  padding: 16rpx 32rpx 14rpx;
  border-top-right-radius: 28rpx;
  background: var(--ink);
  font-size: 40rpx;
  font-weight: 900;
}

.se-preview__body {
  padding: 24rpx 32rpx 32rpx;
  display: flex;
  flex-direction: column;
  gap: 24rpx;
}

.se-tokens {
  display: flex;
  flex-wrap: wrap;
  gap: 18rpx;
  padding-left: 4rpx;
}

.se-token {
  width: 68rpx;
  height: 68rpx;
}

.se-token__text {
  font-size: 28rpx;
}

.se-count {
  display: flex;
  align-items: center;
  gap: 20rpx;
}

.se-count__input {
  width: 140rpx;
  height: 96rpx;
  border-radius: 28rpx;
  background: var(--bg);
  border: 4rpx solid var(--ink);
  color: var(--gold);
  font-size: 48rpx;
  font-weight: 900;
  text-align: center;
  box-sizing: border-box;
}

.se-save {
  flex: 1;
}
</style>
