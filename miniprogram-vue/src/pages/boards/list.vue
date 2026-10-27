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
      <view class="hero-heading">
        <view class="hero-wordmark">WOLFBOOK</view>
        <view class="hero-title">今晚开哪一局？</view>
      </view>
      <view class="hero-role-entry" @tap="openRoles">角色图鉴</view>
    </view>

    <view class="search-box">
      <input
        v-model="keyword"
        class="search-input"
        placeholder="搜板子名称"
        placeholder-class="search-placeholder"
        confirm-type="search"
        @confirm="loadBoards"
      />
      <view class="search-action" @tap="loadBoards">搜索</view>
    </view>

    <view class="filter-panel glass-card">
      <view class="filter-row">
        <text class="filter-label">人数</text>
        <scroll-view scroll-x class="chip-row filter-chips">
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
      </view>
      <view class="filter-row">
        <text class="filter-label">难度</text>
        <scroll-view scroll-x class="chip-row filter-chips">
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
      </view>
      <view class="filter-row">
        <text class="filter-label">标签</text>
        <scroll-view scroll-x class="chip-row filter-chips">
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
    </view>

    <view class="list-heading">
      <text class="list-title serif">全部板子</text>
      <text class="list-count">共 {{ total }} 个</text>
    </view>

    <view v-if="boards.length === 0" class="empty-state glass-card section-card">
      当前筛选条件下没有找到板子，换一个条件试试。
    </view>

    <BoardCard
      v-for="board in boards"
      :key="board.id"
      :board-id="board.id"
      :name="board.name"
      :player-count="board.playerCount"
      :difficulty="board.difficulty"
      :tags="board.tags || []"
      :background-image="board.backgroundImage || ''"
      :lineup-art="board.lineupArt || []"
      :cover-image="board.coverImage || ''"
      :card-roles="board.cardRoles || []"
      :camp-segments="board.campSegments || []"
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
  align-items: flex-end;
  justify-content: space-between;
  gap: 18rpx;
}

.hero-heading {
  min-width: 0;
}

.hero-wordmark {
  color: var(--gold);
  font-family: var(--serif);
  font-size: 24rpx;
  font-weight: 700;
  letter-spacing: 8rpx;
  margin-bottom: 8rpx;
}

.hero-role-entry {
  height: 72rpx;
  padding: 0 24rpx;
  border-radius: 24rpx;
  border: 4rpx solid var(--ink);
  box-shadow: 4rpx 4rpx 0 var(--ink);
  background: var(--panel-raised);
  color: var(--moon);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26rpx;
  font-weight: 700;
  flex-shrink: 0;
  box-sizing: border-box;
}

.search-box {
  margin-top: 32rpx;
  height: 100rpx;
  padding: 0 10rpx 0 28rpx;
  display: flex;
  align-items: center;
  gap: 14rpx;
  border-radius: 32rpx;
  background: var(--panel);
  border: 4rpx solid var(--ink);
  box-shadow: 6rpx 6rpx 0 var(--ink);
  box-sizing: border-box;
}

.search-input {
  flex: 1;
  min-width: 0;
  height: 80rpx;
  color: var(--text);
  font-size: 28rpx;
}

.search-placeholder {
  color: var(--text-3);
}

.search-action {
  height: 72rpx;
  padding: 0 28rpx;
  border-radius: 22rpx;
  background: var(--gold);
  color: var(--ink);
  border: 4rpx solid var(--ink);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26rpx;
  font-weight: 700;
  flex-shrink: 0;
  box-sizing: border-box;
}

.filter-panel {
  margin-top: 28rpx;
  padding: 18rpx 24rpx 8rpx;
}

.filter-row {
  display: flex;
  align-items: center;
  gap: 18rpx;
}

.filter-label {
  flex-shrink: 0;
  width: 56rpx;
  color: var(--text-3);
  font-size: 24rpx;
}

.filter-chips {
  flex: 1;
  min-width: 0;
  margin: 0;
  padding: 8rpx 4rpx 14rpx;
  box-sizing: border-box;
}

.chip {
  min-width: 96rpx;
  min-height: 60rpx;
  padding: 0 22rpx;
}

.list-heading {
  margin-top: 40rpx;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.list-title {
  font-size: 40rpx;
  font-weight: 900;
}

.list-count {
  color: var(--text-3);
  font-size: 24rpx;
}

.list-footer {
  padding: 36rpx 0 8rpx;
  text-align: center;
  color: var(--text-3);
  font-size: 24rpx;
}
</style>
