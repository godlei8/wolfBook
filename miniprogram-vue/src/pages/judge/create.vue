<script setup>
// 建房：选板子（带阵容头像的列表，点选）→ 选法官模式 → 创建并进入等待入座。
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import NavBar from '../../components/NavBar.vue'
import api from '../../services/api'
import { getJudgeSupportMeta, judgeModeOptions } from '../../utils/judge/meta'

const boards = ref([])
const selectedBoardIndex = ref(0)
const loading = ref(false)
const creating = ref(false)
const judgeMode = ref('observer')

const selectedBoard = computed(() => boards.value[selectedBoardIndex.value] || null)
const supportMeta = computed(() => getJudgeSupportMeta(selectedBoard.value?.judgeSupportLevel))
const joinedDisabledReason = computed(() => {
  if (!selectedBoard.value) return '请先选择板子'
  if (selectedBoard.value.judgeSupportLevel === 'manual_only') return '当前板型仅支持主持人模式'
  return '系统执法模式二期开放'
})

async function loadBoards(preferredBoardId = 0) {
  loading.value = true
  try {
    const data = await api.getBoards({ page: 1, size: 50 })
    boards.value = data.list || []
    if (preferredBoardId) {
      const index = boards.value.findIndex((item) => item.id === preferredBoardId)
      selectedBoardIndex.value = index >= 0 ? index : 0
    }
  } catch (error) {
    uni.showToast({ title: error?.message || '板子加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

function chooseBoard(index) {
  selectedBoardIndex.value = index
  judgeMode.value = 'observer'
}

function chooseMode(mode) {
  if (mode === 'joined') {
    uni.showToast({ title: joinedDisabledReason.value, icon: 'none' })
    return
  }
  judgeMode.value = mode
}

async function createRoom() {
  if (creating.value) return
  if (!selectedBoard.value) {
    uni.showToast({ title: '请先选择板子', icon: 'none' })
    return
  }
  creating.value = true
  try {
    const snapshot = await api.createJudgeRoom({
      boardId: selectedBoard.value.id,
      judgeMode: judgeMode.value,
    })
    uni.redirectTo({ url: `/pages/judge/room?roomId=${snapshot.roomId}` })
  } catch (error) {
    uni.showToast({ title: error?.message || '创建房间失败', icon: 'none' })
  } finally {
    creating.value = false
  }
}

onLoad((options) => {
  loadBoards(Number(options?.boardId || 0))
})
</script>

<template>
  <view class="nt-page nt-page--bar">
    <NavBar back title="建房" />

    <view v-if="selectedBoard" class="nt-gutter">
      <view class="nt-ink jc-preview">
        <image v-if="selectedBoard.backgroundImage" class="jc-preview__bg" :src="selectedBoard.backgroundImage" mode="aspectFill" webp />
        <view
          v-for="art in selectedBoard.lineupArt"
          :key="art.roleId"
          class="nt-card jc-preview__card"
          :class="`jc-preview__card--${art.slot}`"
        >
          <view class="nt-card__frame" :style="{ backgroundColor: art.accent }">
            <image class="nt-card__img" :src="art.image" mode="aspectFill" webp />
          </view>
        </view>
        <view class="jc-preview__plate">
          <view class="jc-preview__copy">
            <text class="jc-preview__eyebrow">今晚开这一局</text>
            <text class="jc-preview__name serif">{{ selectedBoard.name }}</text>
          </view>
          <text class="nt-meta jc-preview__meta">{{ selectedBoard.playerCount }} 人 · {{ supportMeta.label }}</text>
        </view>
      </view>
    </view>

    <view class="nt-gutter nt-stack jc-body">
      <view class="nt-panel">
        <view class="nt-panel__head">
          <text class="nt-h2">法官模式</text>
        </view>
        <view class="jc-modes">
          <view
            v-for="item in judgeModeOptions"
            :key="item.value"
            class="jc-mode"
            :class="{ 'jc-mode--on': judgeMode === item.value, 'jc-mode--off': item.value === 'joined' }"
            @tap="chooseMode(item.value)"
          >
            <text class="jc-mode__title serif">{{ item.title }}</text>
            <text class="jc-mode__desc">{{ item.value === 'joined' ? joinedDisabledReason : item.subtitle }}</text>
          </view>
        </view>
      </view>

      <view class="nt-panel">
        <view class="nt-panel__head">
          <text class="nt-h2">选板子</text>
          <text class="nt-meta">{{ boards.length }} 个</text>
        </view>
        <view v-if="loading" class="nt-empty">正在加载板子…</view>
        <view
          v-for="(board, index) in boards"
          :key="board.id"
          class="jc-board"
          :class="{ 'jc-board--on': index === selectedBoardIndex }"
          @tap="chooseBoard(index)"
        >
          <view class="jc-board__main">
            <text class="jc-board__name">{{ board.name }}</text>
            <text class="nt-meta">{{ board.playerCount }} 人 · {{ board.difficulty }}{{ board.winCondition ? ` · ${board.winCondition}` : '' }}</text>
          </view>
          <view class="jc-board__stack">
            <view
              v-for="(role, roleIndex) in (board.cardRoles || []).slice(0, 4)"
              :key="role.roleId"
              class="nt-token jc-board__token"
              :style="{ marginLeft: roleIndex ? '-14rpx' : '0', zIndex: 5 - roleIndex }"
            >
              <image v-if="role.portrait" class="nt-token__img" :src="role.portrait" mode="aspectFill" :style="{ borderColor: role.accent }" webp />
              <view v-else class="nt-token__text jc-board__text" :style="{ borderColor: role.accent }">{{ role.name.slice(0, 1) }}</view>
            </view>
          </view>
          <view class="jc-board__check">{{ index === selectedBoardIndex ? '✓' : '' }}</view>
        </view>
      </view>
    </view>

    <view class="nt-bar">
      <view class="nt-btn nt-btn--gold jc-create" :class="{ 'nt-btn--disabled': creating || !selectedBoard }" @tap="createRoom">
        {{ creating ? '正在建房…' : '☾ 创建并进入房间' }}
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.jc-preview {
  position: relative;
  margin-top: 16rpx;
  height: 400rpx;
  border-radius: 40rpx;
  overflow: hidden;
  background: #1b1724;
}

.jc-preview__bg {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
}

.jc-preview__card {
  position: absolute;
  width: 136rpx;
  border-radius: 18rpx;
  box-shadow: 4rpx 4rpx 0 var(--ink);
}

.jc-preview__card--l1 {
  left: 170rpx;
  top: 60rpx;
  transform: rotate(-9deg);
}

.jc-preview__card--c {
  left: 263rpx;
  top: 32rpx;
  z-index: 2;
}

.jc-preview__card--r1 {
  left: 356rpx;
  top: 60rpx;
  transform: rotate(9deg);
}

.jc-preview__plate {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 3;
  padding: 22rpx 32rpx;
  background: var(--ink);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.jc-preview__copy {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
  min-width: 0;
}

.jc-preview__eyebrow {
  color: var(--gold);
  font-size: 24rpx;
  font-weight: 700;
}

.jc-preview__name {
  font-size: 40rpx;
  font-weight: 900;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.jc-preview__meta {
  flex-shrink: 0;
}

.jc-body {
  margin-top: 32rpx;
}

.jc-modes {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20rpx;
}

.jc-mode {
  padding: 28rpx;
  border-radius: 28rpx;
  background: var(--panel-raised);
  border: 4rpx solid transparent;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
  box-sizing: border-box;
}

.jc-mode--on {
  border-color: var(--moon);
}

.jc-mode--off {
  opacity: 0.5;
}

.jc-mode__title {
  font-size: 34rpx;
  font-weight: 900;
}

.jc-mode__desc {
  color: var(--text-2);
  font-size: 24rpx;
  line-height: 1.5;
}

.jc-board {
  display: flex;
  align-items: center;
  gap: 20rpx;
  min-height: 112rpx;
  padding: 18rpx 24rpx;
  border-radius: 28rpx;
  background: var(--panel-raised);
  border: 4rpx solid transparent;
  box-sizing: border-box;
}

.jc-board--on {
  border-color: var(--moon);
}

.jc-board__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.jc-board__name {
  font-size: 30rpx;
  font-weight: 700;
}

.jc-board__stack {
  display: flex;
  flex-shrink: 0;
}

.jc-board__token {
  position: relative;
  width: 56rpx;
  height: 56rpx;
}

.jc-board__text {
  font-size: 24rpx;
}

.jc-board__check {
  width: 40rpx;
  color: var(--moon);
  font-size: 36rpx;
  font-weight: 900;
  text-align: center;
  flex-shrink: 0;
}

.jc-create {
  flex: 1;
}
</style>
