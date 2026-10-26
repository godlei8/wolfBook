<script setup>
import { ref } from 'vue'
import { onLoad, onPullDownRefresh, onReachBottom, onShow } from '@dcloudio/uni-app'
import BoardCard from '../../components/BoardCard.vue'
import AssistantDock from '../../components/assistant/AssistantDock.vue'
import api from '../../services/api'
import userData from '../../services/user-data'

const PAGE_SIZE = 20

const boards = ref([])
const total = ref(0)
const page = ref(1)
const hasMore = ref(false)
const loadingMore = ref(false)
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

// 模板里 @confirm / @tap 会把事件对象传进来，所以只认显式的 { append: true }
async function loadBoards(options = {}) {
  const append = options.append === true
  if (append && (loadingMore.value || !hasMore.value)) return
  const nextPage = append ? page.value + 1 : 1
  if (append) {
    loadingMore.value = true
  }
  try {
    const data = await api.getBoards({
      page: nextPage,
      size: PAGE_SIZE,
      playerCount: playerFilter.value,
      difficulty: difficultyFilter.value,
      tag: tagFilter.value,
      keyword: keyword.value,
    })
    const list = data.list || []
    boards.value = append ? boards.value.concat(list) : list
    total.value = data.total || 0
    page.value = nextPage
    hasMore.value = list.length > 0 && boards.value.length < total.value
    if (!append) {
      await syncFavoritesSafely()
    }
  } catch (error) {
    console.error('load boards failed', error)
    uni.showToast({ title: error?.message || '板子加载失败', icon: 'none' })
  } finally {
    loadingMore.value = false
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

onReachBottom(() => loadBoards({ append: true }))
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

    <view v-if="boards.length" class="list-footer">
      {{ loadingMore ? '加载中…' : hasMore ? '上拉加载更多' : '没有更多了' }}
    </view>

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
  gap: 18rpx;
}

.hero-role-entry {
  min-width: 112rpx;
  height: 70rpx;
  padding: 0 28rpx;
  border-radius: 16rpx;
  background: rgba(255, 192, 0, 0.14);
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
  gap: 14rpx;
}

.search-input {
  flex: 1;
  min-height: 84rpx;
  padding: 0 24rpx;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.05);
  color: #ffffff;
  box-sizing: border-box;
}

.search-action {
  width: 118rpx;
  min-height: 84rpx;
  border-radius: 18rpx;
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
  margin: 22rpx 0 12rpx;
}

.chip-row {
  margin: 0;
  padding: 2rpx 0 10rpx;
  box-sizing: border-box;
}

.chip {
  min-width: 112rpx;
  padding: 0 24rpx;
}

.list-footer {
  padding: 28rpx 0 8rpx;
  text-align: center;
  color: #8f8f8f;
  font-size: 24rpx;
}
</style>
