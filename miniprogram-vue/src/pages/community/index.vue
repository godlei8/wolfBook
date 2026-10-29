<script setup>
// 酒馆（社区，原型 D10，tab 页）：酒馆横幅 + 分类 + 帖子卡片。
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onReachBottom, onShow } from '@dcloudio/uni-app'
import NavBar from '../../components/NavBar.vue'
import api from '../../services/api'
import storage from '../../services/storage'
import { fromNow } from '../../utils/format'
import { getNavInsets, rpxToPx } from '../../utils/nav'
import { syncTabBar } from '../../utils/tabbar'

const PAGE_SIZE = 20
const TABS = [
  { key: 'hot', label: '推荐', filters: { sort: 'hot' } },
  { key: 'latest', label: '最新', filters: { sort: 'latest' } },
  { key: 'report', label: '战报', filters: { category: '战报' } },
  { key: 'teach', label: '教学', filters: { category: '教学' } },
  { key: 'talk', label: '讨论', filters: { category: '讨论' } },
]

const insets = getNavInsets()
const posts = ref([])
const page = ref(1)
const hasMore = ref(false)
const loadingMore = ref(false)
const tab = ref('hot')
// 从法官房间「去酒馆聊聊」过来只看这个板子的帖子；从「我的」过来只看自己的帖子
const boardFilter = ref(null)
const bannerHeight = `${Math.round(insets.top + rpxToPx(300))}px`

const filters = computed(() => {
  const base = (TABS.find((item) => item.key === tab.value) || TABS[0]).filters
  if (!boardFilter.value) return base
  return boardFilter.value.mine ? { ...base, mine: true } : { ...base, boardId: boardFilter.value.boardId }
})

function decoratePost(post) {
  return { ...post, relativeTime: fromNow(post.createTime) }
}

async function loadPosts(options = {}) {
  const append = options.append === true
  if (append && (loadingMore.value || !hasMore.value)) return
  const nextPage = append ? page.value + 1 : 1
  if (append) loadingMore.value = true
  try {
    const data = await api.getPosts(nextPage, PAGE_SIZE, filters.value)
    const list = (data.list || []).map(decoratePost)
    posts.value = append ? posts.value.concat(list) : list
    page.value = nextPage
    const total = Number(data.total) || 0
    hasMore.value = list.length > 0 && posts.value.length < total
  } catch (error) {
    uni.showToast({ title: error?.message || '酒馆加载失败', icon: 'none' })
  } finally {
    loadingMore.value = false
  }
}

function switchTab(key) {
  if (tab.value === key) return
  tab.value = key
  loadPosts()
}

function clearBoardFilter() {
  boardFilter.value = null
  loadPosts()
}

function openPost(id) {
  uni.navigateTo({ url: `/pages/community/detail?id=${id}` })
}

function openBoard(id) {
  if (!id) return
  uni.navigateTo({ url: `/pages/boards/detail?id=${id}` })
}

function createPost() {
  if (!storage.getAuthToken()) {
    uni.showToast({ title: '登录后才能发帖', icon: 'none' })
    uni.switchTab({ url: '/pages/user/index' })
    return
  }
  const board = boardFilter.value?.boardId ? `?boardId=${boardFilter.value.boardId}` : ''
  uni.navigateTo({ url: `/pages/community/post${board}` })
}

async function toggleLike(post) {
  if (!storage.getAuthToken()) {
    uni.showToast({ title: '登录后才能点赞', icon: 'none' })
    return
  }
  try {
    const result = await api.togglePostLike(post.id)
    posts.value = posts.value.map((item) => (item.id === post.id ? { ...item, liked: result.liked, likeCount: result.likeCount } : item))
  } catch (error) {
    uni.showToast({ title: error?.message || '点赞失败', icon: 'none' })
  }
}

onLoad(() => loadPosts())
onShow(() => {
  syncTabBar()
  const context = storage.consumeCommunityFilterContext()
  if (context?.mine) {
    boardFilter.value = { mine: true, label: '只看我的帖子' }
    loadPosts()
  } else if (context?.boardId) {
    boardFilter.value = { boardId: context.boardId, label: `只看「${context.boardName || '这个板子'}」的帖子` }
    loadPosts()
  }
})
onPullDownRefresh(async () => {
  await loadPosts()
  uni.stopPullDownRefresh()
})
onReachBottom(() => loadPosts({ append: true }))
</script>

<template>
  <view class="nt-page nt-page--tab">
    <view class="cm-banner" :style="{ height: bannerHeight }">
      <image class="cm-banner__bg" src="/static/scenes/tavern.webp" mode="aspectFill" webp />
      <view class="cm-banner__shade" />
      <NavBar overlay />
      <view class="cm-banner__foot">
        <view class="cm-banner__copy">
          <text class="cm-banner__mark">TAVERN</text>
          <text class="cm-banner__title serif">酒馆</text>
        </view>
        <view class="nt-btn nt-btn--gold nt-btn--small" @tap="createPost">＋ 发帖</view>
      </view>
    </view>

    <view v-if="boardFilter" class="nt-gutter">
      <view class="cm-filter" @tap="clearBoardFilter">
        <text>{{ boardFilter.label }}</text>
        <text class="cm-filter__x">✕</text>
      </view>
    </view>

    <scroll-view scroll-x class="nt-chips cm-tabs" :show-scrollbar="false">
      <view class="nt-chips__inner">
        <text v-for="item in TABS" :key="item.key" class="nt-chip" :class="{ 'nt-chip--on': tab === item.key }" @tap="switchTab(item.key)">
          {{ item.label }}
        </text>
      </view>
    </scroll-view>

    <view class="nt-gutter nt-stack cm-list">
      <view v-if="posts.length === 0" class="nt-empty">这里还没有帖子，发第一条吧。</view>

      <view v-for="item in posts" :key="item.id" class="nt-ink cm-post" @tap="openPost(item.id)">
        <view class="cm-post__head">
          <view class="nt-token cm-post__avatar">
            <image class="nt-token__img" :src="item.avatar" mode="aspectFill" />
          </view>
          <view class="cm-post__who">
            <text class="cm-post__name">{{ item.nickname }}</text>
            <text class="cm-post__time">{{ item.relativeTime }}</text>
          </view>
          <text v-if="item.boardName" class="nt-tag cm-post__board" @tap.stop="openBoard(item.boardId)">{{ item.boardName }}</text>
          <text v-else-if="item.category" class="nt-tag">{{ item.category }}</text>
        </view>
        <text v-if="item.title" class="cm-post__title serif">{{ item.title }}</text>
        <text class="cm-post__text">{{ item.content }}</text>
        <view v-if="item.images && item.images.length" class="cm-post__images">
          <view v-for="image in item.images.slice(0, 3)" :key="image" class="cm-post__image">
            <image class="cm-post__img" :src="image" mode="aspectFill" />
          </view>
        </view>
        <view class="cm-post__actions">
          <text class="cm-post__act" :class="{ 'cm-post__act--on': item.liked }" @tap.stop="toggleLike(item)">{{ item.liked ? '♥' : '♡' }} {{ item.likeCount }}</text>
          <text class="cm-post__act">◌ {{ item.commentCount }}</text>
        </view>
      </view>

      <view v-if="posts.length" class="nt-footer">
        {{ loadingMore ? '加载中…' : hasMore ? '上拉加载更多' : '没有更多了' }}
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.cm-banner {
  position: relative;
  overflow: hidden;
  border-bottom: 4rpx solid var(--ink);
}

.cm-banner__bg {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
}

.cm-banner__shade {
  position: absolute;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  background: rgba(21, 18, 28, 0.3);
}

.cm-banner__foot {
  position: absolute;
  left: 40rpx;
  right: 40rpx;
  bottom: 32rpx;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
}

.cm-banner__copy {
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}

.cm-banner__mark {
  color: var(--gold);
  font-family: var(--serif);
  font-size: 26rpx;
  font-weight: 700;
  letter-spacing: 8rpx;
  text-shadow: 4rpx 4rpx 0 var(--ink);
}

.cm-banner__title {
  font-size: 68rpx;
  font-weight: 900;
  line-height: 1.1;
  text-shadow: 6rpx 6rpx 0 var(--ink);
}

.cm-filter {
  margin-top: 28rpx;
  padding: 18rpx 26rpx;
  border-radius: 24rpx;
  background: #3a2e14;
  color: var(--gold);
  font-size: 26rpx;
  display: flex;
  justify-content: space-between;
}

.cm-filter__x {
  font-weight: 900;
}

.cm-tabs {
  margin-top: 32rpx;
}

.cm-list {
  margin-top: 28rpx;
}

.cm-post {
  padding: 32rpx;
  display: flex;
  flex-direction: column;
  gap: 22rpx;
}

.cm-post__head {
  display: flex;
  align-items: center;
  gap: 20rpx;
}

.cm-post__avatar {
  width: 72rpx;
  height: 72rpx;
}

.cm-post__who {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2rpx;
}

.cm-post__name {
  font-size: 28rpx;
  font-weight: 700;
}

.cm-post__time {
  color: var(--text-3);
  font-size: 24rpx;
}

.cm-post__board {
  color: var(--moon);
  max-width: 240rpx;
  overflow: hidden;
  text-overflow: ellipsis;
}

.cm-post__title {
  font-size: 36rpx;
  font-weight: 900;
  line-height: 1.4;
}

.cm-post__text {
  color: var(--text-soft);
  font-size: 28rpx;
  line-height: 1.75;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 4;
  overflow: hidden;
}

.cm-post__images {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16rpx;
}

.cm-post__image {
  position: relative;
  padding-top: 100%;
  border-radius: 20rpx;
  border: 4rpx solid var(--ink);
  box-shadow: 4rpx 4rpx 0 var(--ink);
  overflow: hidden;
  background: var(--panel-raised);
}

.cm-post__img {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
}

.cm-post__actions {
  display: flex;
  gap: 36rpx;
  color: var(--text-2);
  font-size: 26rpx;
}

.cm-post__act {
  min-width: 80rpx;
}

.cm-post__act--on {
  color: var(--gold);
}
</style>
