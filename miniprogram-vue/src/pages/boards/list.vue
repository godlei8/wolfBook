<script setup>
import { ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import BoardCard from '../../components/BoardCard.vue'
import AssistantDock from '../../components/assistant/AssistantDock.vue'
import api from '../../services/api'
import userData from '../../services/user-data'

const boards = ref([])
const total = ref(0)
const keyword = ref('')
const playerFilter = ref('all')
const difficultyFilter = ref('all')
const tagFilter = ref('all')
const favoriteIds = ref([])

const playerOptions = [
  { label: '全部', value: 'all' },
  { label: '9', value: '9' },
  { label: '12', value: '12' },
  { label: '15+', value: '15+' },
]

const difficultyOptions = [
  { label: '全部', value: 'all' },
  { label: '入门', value: '入门' },
  { label: '进阶', value: '进阶' },
  { label: '烧脑', value: '烧脑' },
]

const tagOptions = [
  { label: '全部', value: 'all' },
  { label: '经典', value: '经典' },
  { label: '教学', value: '教学' },
  { label: '娱乐', value: '娱乐' },
  { label: '高配', value: '高配' },
  { label: '第三方', value: '第三方' },
]

async function syncFavoritesSafely() {
  try {
    const state = await userData.loadFavoriteBoards()
    favoriteIds.value = state.boardIds || []
  } catch (error) {
    favoriteIds.value = []
    console.warn('favorite sync failed', error)
  }
}

function isFavorite(boardId) {
  return favoriteIds.value.includes(boardId)
}

async function loadBoards() {
  try {
    const data = await api.getBoards({
      page: 1,
      size: 20,
      playerCount: playerFilter.value,
      difficulty: difficultyFilter.value,
      tag: tagFilter.value,
      keyword: keyword.value,
    })
    boards.value = data.list || []
    total.value = data.total || 0
    await syncFavoritesSafely()
  } catch (error) {
    console.error('load boards failed', error)
    uni.showToast({ title: error?.message || '板子加载失败', icon: 'none' })
  }
}

function openDetail(id) {
  uni.navigateTo({ url: `/pages/boards/detail?id=${id}` })
}

function openRoles() {
  uni.navigateTo({ url: '/pages/roles/list' })
}

async function toggleFavorite(boardId) {
  try {
    const state = await userData.toggleFavorite(boardId)
    favoriteIds.value = state.boardIds || []
    uni.showToast({
      title: favoriteIds.value.includes(boardId) ? '已加入收藏' : '已取消收藏',
      icon: 'none',
    })
  } catch (error) {
    uni.showToast({ title: error?.message || '收藏更新失败', icon: 'none' })
  }
}

function applyPlayerFilter(item) {
  playerFilter.value = item.value
  loadBoards()
}

function applyDifficultyFilter(item) {
  difficultyFilter.value = item.value
  loadBoards()
}

function applyTagFilter(item) {
  tagFilter.value = item.value
  loadBoards()
}

onLoad(() => {
  loadBoards()
})

onShow(() => {
  if (boards.value.length) {
    syncFavoritesSafely()
    return
  }
  loadBoards()
})

onPullDownRefresh(async () => {
  await loadBoards()
  uni.stopPullDownRefresh()
})
</script>

<template>
  <view class="page-shell">
    <view class="hero-header">
      <view class="hero-title">板子库</view>
      <view class="hero-role-entry" @tap="openRoles">角色</view>
    </view>
    <view class="hero-subtitle">黑夜规则、角色配置和对局节奏，都从这里开始。</view>

    <view class="glass-card section-card">
      <view class="search-box">
        <input
          v-model="keyword"
          class="search-input"
          placeholder="搜索板子名称"
          confirm-type="search"
          @confirm="loadBoards"
        />
        <view class="search-action" @tap="loadBoards">搜索</view>
      </view>

      <view class="section-meta filter-title">人数</view>
      <scroll-view scroll-x class="chip-row">
        <view
          v-for="item in playerOptions"
          :key="item.value"
          class="chip"
          :class="{ active: playerFilter === item.value }"
          @tap="applyPlayerFilter(item)"
        >
          {{ item.label }}
        </view>
      </scroll-view>

      <view class="section-meta filter-title">难度</view>
      <scroll-view scroll-x class="chip-row">
        <view
          v-for="item in difficultyOptions"
          :key="item.value"
          class="chip"
          :class="{ active: difficultyFilter === item.value }"
          @tap="applyDifficultyFilter(item)"
        >
          {{ item.label }}
        </view>
      </scroll-view>

      <view class="section-meta filter-title">标签</view>
      <scroll-view scroll-x class="chip-row">
        <view
          v-for="item in tagOptions"
          :key="item.value"
          class="chip"
          :class="{ active: tagFilter === item.value }"
          @tap="applyTagFilter(item)"
        >
          {{ item.label }}
        </view>
      </scroll-view>
    </view>

    <view v-if="boards.length === 0" class="empty-state glass-card section-card">
      当前筛选条件下没有找到板子，换一个条件试试。
    </view>

    <BoardCard
      v-for="board in boards"
      :key="board.id"
      :board-id="board.id"
      :cover-image="board.coverImage"
      :difficulty="board.difficulty"
      :meta-text="`共 ${total} 个结果`"
      :name="board.name"
      :player-count="board.playerCount"
      :card-roles="board.cardRoles || []"
      :description="board.cardDescription || board.summary || ''"
      :favorite="isFavorite(board.id)"
      @select="openDetail"
      @favorite-toggle="toggleFavorite"
    />

    <AssistantDock
      scene="boards_list"
      :page-context="{
        page: 'boards/list',
        keyword,
        playerFilter,
        difficultyFilter,
        tagFilter,
      }"
    />
  </view>
</template>

<style scoped lang="scss">
.hero-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.hero-role-entry {
  min-width: 112rpx;
  height: 72rpx;
  padding: 0 28rpx;
  border-radius: 14rpx;
  background: rgba(255, 192, 0, 0.16);
  color: #ffc000;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24rpx;
  font-weight: 700;
  flex-shrink: 0;
}

.search-box {
  display: flex;
  align-items: center;
  gap: 16rpx;
}

.search-input {
  flex: 1;
  height: 76rpx;
  padding: 0 24rpx;
  border-radius: 16rpx;
  background: rgba(255, 255, 255, 0.05);
  color: #ffffff;
  box-sizing: border-box;
}

.search-action {
  width: 120rpx;
  height: 76rpx;
  border-radius: 16rpx;
  background: linear-gradient(135deg, rgba(255, 213, 77, 0.96), rgba(166, 127, 8, 0.96));
  color: #1b1506;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24rpx;
  font-weight: 700;
  flex-shrink: 0;
  box-shadow: 0 10rpx 22rpx rgba(255, 192, 0, 0.18);
}

.filter-title {
  margin: 18rpx 0 10rpx;
}

.chip-row {
  margin: 0 -6rpx;
  padding: 4rpx 10rpx 8rpx 6rpx;
  box-sizing: border-box;
}

.chip {
  padding: 10rpx 24rpx;
}
</style>
