<script setup>
import { ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import BoardCard from '../../components/BoardCard.vue'
import api from '../../services/api'
import storage from '../../services/storage'

const boards = ref([])
const total = ref(0)
const keyword = ref('')
const playerFilter = ref('全部')
const difficultyFilter = ref('全部')
const tagFilter = ref('全部')
const favoriteIds = ref([])

const playerOptions = ['全部', '9', '12', '15+']
const difficultyOptions = ['全部', '入门', '进阶', '烧脑']
const tagOptions = ['全部', '经典', '教学', '娱乐', '高配', '第三方']

function syncFavorites() {
  favoriteIds.value = storage.getFavorites()
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
    syncFavorites()
  } catch (error) {
    uni.showToast({ title: '板子加载失败', icon: 'none' })
  }
}

function openDetail(id) {
  uni.navigateTo({ url: `/pages/boards/detail?id=${id}` })
}

function openRoles() {
  uni.navigateTo({ url: '/pages/roles/list' })
}

function toggleFavorite(boardId) {
  favoriteIds.value = storage.toggleFavorite(boardId)
  uni.showToast({
    title: favoriteIds.value.includes(boardId) ? '已加入收藏' : '已取消收藏',
    icon: 'none',
  })
}

onLoad(() => {
  syncFavorites()
  loadBoards()
})

onShow(() => {
  syncFavorites()
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
          :key="item"
          class="chip"
          :class="{ active: playerFilter === item }"
          @tap="playerFilter = item; loadBoards()"
        >
          {{ item }}
        </view>
      </scroll-view>

      <view class="section-meta filter-title">难度</view>
      <scroll-view scroll-x class="chip-row">
        <view
          v-for="item in difficultyOptions"
          :key="item"
          class="chip"
          :class="{ active: difficultyFilter === item }"
          @tap="difficultyFilter = item; loadBoards()"
        >
          {{ item }}
        </view>
      </scroll-view>

      <view class="section-meta filter-title">标签</view>
      <scroll-view scroll-x class="chip-row">
        <view
          v-for="item in tagOptions"
          :key="item"
          class="chip"
          :class="{ active: tagFilter === item }"
          @tap="tagFilter = item; loadBoards()"
        >
          {{ item }}
        </view>
      </scroll-view>
    </view>

    <view v-if="boards.length === 0" class="empty-state glass-card section-card">
      当前筛选条件下没有找到板子，换一个条件试试。
    </view>

    <BoardCard
      v-for="board in boards"
      :key="board.id"
      :board="board"
      :meta-text="`共 ${total} 个结果`"
      :favorite="isFavorite(board.id)"
      @select="openDetail"
      @favorite-toggle="toggleFavorite"
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
</style>
