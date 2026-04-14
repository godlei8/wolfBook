<script setup>
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import AssistantDock from '../../components/assistant/AssistantDock.vue'
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
    const data = await api.getBoards({ page: 1, size: 100 })
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

function chooseBoard(event) {
  selectedBoardIndex.value = Number(event.detail?.value || 0)
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
  <view class="page-shell">
    <view class="hero-title">创建法官房间</view>
    <view class="hero-subtitle">优先支持主持人模式，先把线下对局和结构化流程稳定跑起来。</view>

    <view class="glass-card section-card">
      <view class="section-title">选择板子</view>
      <view class="section-desc">会自动带入人数和法官支持层级，后续还能继续扩展到系统执法。</view>
      <picker
        mode="selector"
        :range="boards"
        range-key="name"
        :value="selectedBoardIndex"
        @change="chooseBoard"
      >
        <view class="field-input picker-field">
          <view class="picker-value">{{ selectedBoard ? selectedBoard.name : '选择板子' }}</view>
          <view class="picker-arrow">></view>
        </view>
      </picker>

      <view v-if="selectedBoard" class="board-preview">
        <image v-if="selectedBoard.coverImage" class="board-cover" :src="selectedBoard.coverImage" mode="aspectFill" />
        <view class="board-copy">
          <view class="board-top">
            <view class="pill pill-gold">{{ selectedBoard.playerCount }} 人局</view>
            <view class="pill pill-white">{{ supportMeta.label }}</view>
          </view>
          <view class="board-name">{{ selectedBoard.name }}</view>
          <view class="board-meta">{{ selectedBoard.difficulty }} · {{ supportMeta.description }}</view>
          <view class="board-desc">{{ selectedBoard.cardDescription || selectedBoard.summary || selectedBoard.briefConfig }}</view>
        </view>
      </view>
    </view>

    <view class="glass-card section-card">
      <view class="section-title">法官模式</view>
      <view class="section-desc">一期先开放主持人模式，系统执法模式预留入口但暂不开放。</view>
      <view class="mode-grid">
        <view
          v-for="item in judgeModeOptions"
          :key="item.value"
          class="mode-card"
          :class="{ active: judgeMode === item.value, disabled: item.value === 'joined' }"
          @tap="chooseMode(item.value)"
        >
          <view class="mode-title">{{ item.title }}</view>
          <view class="mode-desc">{{ item.value === 'joined' ? joinedDisabledReason : item.subtitle }}</view>
        </view>
      </view>
    </view>

    <button class="button-primary create-button" :loading="creating" @tap="createRoom">创建并进入房间</button>

    <AssistantDock
      scene="judge_create"
      :page-context="{ page: 'judge/create', boardId: selectedBoard?.id || 0, boardName: selectedBoard?.name || '', judgeMode }"
    />
  </view>
</template>

<style scoped lang="scss">
.picker-field {
  display: flex;
  justify-content: space-between;
  align-items: center;
  min-height: 88rpx;
}

.picker-value {
  font-size: 28rpx;
  color: #ffffff;
}

.picker-arrow {
  color: #ffc000;
  font-size: 32rpx;
}

.board-preview {
  margin-top: 18rpx;
  overflow: hidden;
  border-radius: 20rpx;
  background: rgba(255, 255, 255, 0.04);
}

.board-cover {
  width: 100%;
  height: 240rpx;
  display: block;
}

.board-copy {
  padding: 22rpx;
}

.board-top {
  display: flex;
  gap: 12rpx;
}

.board-name {
  margin-top: 16rpx;
  font-size: 34rpx;
  font-weight: 700;
}

.board-meta {
  margin-top: 10rpx;
  color: #c9b483;
  font-size: 24rpx;
}

.board-desc {
  margin-top: 12rpx;
  color: #8f8f8f;
  font-size: 24rpx;
  line-height: 1.7;
}

.mode-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16rpx;
  margin-top: 20rpx;
}

.mode-card {
  min-height: 180rpx;
  padding: 22rpx;
  border-radius: 20rpx;
  background: rgba(255, 255, 255, 0.04);
  border: 1rpx solid rgba(255, 255, 255, 0.08);
}

.mode-card.active {
  background: rgba(255, 192, 0, 0.12);
  border-color: rgba(255, 192, 0, 0.2);
}

.mode-card.disabled {
  opacity: 0.7;
}

.mode-title {
  font-size: 30rpx;
  font-weight: 700;
}

.mode-desc {
  margin-top: 12rpx;
  color: #9d9d9d;
  font-size: 22rpx;
  line-height: 1.7;
}

.create-button {
  margin-top: 28rpx;
}
</style>
