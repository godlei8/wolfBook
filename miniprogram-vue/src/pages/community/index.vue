<script setup>
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import AssistantDock from '../../components/assistant/AssistantDock.vue'
import api from '../../services/api'
import { fromNow } from '../../utils/format'

const tabs = [
  { value: 'recommend', label: '推荐' },
  { value: 'latest', label: '最新' },
  { value: 'hot', label: '热门' },
]

const typeOptions = [
  { value: 'all', label: '全部' },
  { value: 'review', label: '复盘' },
  { value: 'board_discussion', label: '板型' },
  { value: 'qa', label: '问答' },
  { value: 'strategy', label: '战术' },
  { value: 'help', label: '新手' },
]

const typeLabelMap = {
  general: '分享',
  review: '复盘',
  board_discussion: '板型',
  qa: '问答',
  strategy: '战术',
  help: '新手',
  recruit: '组局',
}

const activeTab = ref('recommend')
const activeType = ref('all')
const keyword = ref('')
const posts = ref([])
const hotBoards = ref([])
const suggestedTags = ref([])
const loading = ref(false)

const pageContext = computed(() => ({
  page: 'community/index',
  tab: activeTab.value,
  type: activeType.value,
  keyword: keyword.value,
  postCount: posts.value.length,
}))

async function loadPosts() {
  loading.value = true
  try {
    const data = await api.getPosts(1, 20, {
      tab: activeTab.value,
      type: activeType.value === 'all' ? '' : activeType.value,
      q: keyword.value.trim(),
      sort: activeTab.value === 'latest' ? 'latest' : 'hot',
    })
    posts.value = (data.posts?.list || []).map((post) => ({
      ...post,
      relativeTime: fromNow(post.createTime),
      typeLabel: typeLabelMap[post.postType] || '分享',
    }))
    hotBoards.value = data.hotBoards || []
    suggestedTags.value = data.suggestedTags || []
  } catch (error) {
    uni.showToast({ title: '社区加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

function openPost(id) {
  uni.navigateTo({ url: `/pages/community/detail?id=${id}` })
}

function createPost() {
  const type = activeType.value !== 'all' ? `?type=${activeType.value}` : ''
  uni.navigateTo({ url: `/pages/community/post${type}` })
}

function openBoard(boardId) {
  if (!boardId) return
  uni.navigateTo({ url: `/pages/boards/detail?id=${boardId}` })
}

function changeTab(value) {
  if (activeTab.value === value) return
  activeTab.value = value
  loadPosts()
}

function changeType(value) {
  if (activeType.value === value) return
  activeType.value = value
  loadPosts()
}

function applyTag(tag) {
  keyword.value = tag
  loadPosts()
}

function submitSearch() {
  loadPosts()
}

function clearSearch() {
  keyword.value = ''
  loadPosts()
}

onLoad(loadPosts)
onShow(loadPosts)
onPullDownRefresh(async () => {
  await loadPosts()
  uni.stopPullDownRefresh()
})
</script>

<template>
  <view class="page-shell">
    <view class="hero-block">
      <view class="hero-title">社区</view>
      <view class="hero-subtitle">把复盘、板型讨论、规则问答和战术沉淀在一起。</view>
    </view>

    <view class="glass-card section-card search-card">
      <view class="search-row">
        <input v-model="keyword" class="search-input" confirm-type="search" placeholder="搜索帖子、板型或关键词" @confirm="submitSearch" />
        <button class="button-primary search-action" @tap="submitSearch">搜索</button>
      </view>
      <view v-if="keyword" class="search-helper" @tap="clearSearch">清空搜索</view>
      <view v-if="suggestedTags.length" class="tag-strip">
        <view v-for="tag in suggestedTags" :key="tag" class="tag-pill tag-outline" @tap="applyTag(tag)"># {{ tag }}</view>
      </view>
    </view>

    <view class="tab-row">
      <button
        v-for="item in tabs"
        :key="item.value"
        class="button-segment tab-pill"
        :class="{ active: activeTab === item.value }"
        @tap="changeTab(item.value)"
      >
        {{ item.label }}
      </button>
    </view>

    <scroll-view class="type-scroll" scroll-x enable-flex>
      <view class="type-row">
        <button
          v-for="item in typeOptions"
          :key="item.value"
          class="button-segment type-pill"
          :class="{ active: activeType === item.value }"
          @tap="changeType(item.value)"
        >
          {{ item.label }}
        </button>
      </view>
    </scroll-view>

    <view class="glass-card section-card quick-card" @tap="createPost">
      <view>
        <view class="quick-title">发一条内容</view>
        <view class="section-desc">复盘、提问、板型讨论都可以直接开始。</view>
      </view>
      <view class="quick-arrow">去发布</view>
    </view>

    <view v-if="hotBoards.length" class="glass-card section-card board-card">
      <view class="section-title">热门板子</view>
      <view class="board-list">
        <view v-for="board in hotBoards" :key="board.boardId" class="board-pill" @tap="openBoard(board.boardId)">
          <image v-if="board.coverImage" class="board-thumb" :src="board.coverImage" mode="aspectFill" />
          <view class="board-copy">
            <view class="board-name">{{ board.boardName }}</view>
            <view class="board-meta">{{ board.postCount }} 篇内容 · 热度 {{ Math.round(board.hotScore || 0) }}</view>
          </view>
        </view>
      </view>
    </view>

    <view v-if="posts.length === 0 && !loading" class="empty-state glass-card section-card">
      还没有符合条件的内容，去发第一条吧。
    </view>

    <view v-for="item in posts" :key="item.id" class="glass-card section-card post-card" @tap="openPost(item.id)">
      <view class="post-top">
        <view class="post-author">
          <image class="post-avatar" :src="item.avatar" mode="aspectFill" />
          <view>
            <view class="post-name">{{ item.nickname }}</view>
            <view class="section-meta">{{ item.relativeTime }}</view>
          </view>
        </view>
        <view v-if="item.featured || item.pinned" class="feature-stack">
          <view v-if="item.pinned" class="mark-pill mark-pinned">置顶</view>
          <view v-if="item.featured" class="mark-pill mark-featured">精选</view>
        </view>
      </view>

      <view class="meta-row">
        <view class="tag-pill tag-solid">{{ item.typeLabel }}</view>
        <view v-if="item.boardName" class="tag-pill tag-outline" @tap.stop="openBoard(item.boardId)">{{ item.boardName }}</view>
      </view>

      <view class="post-title">{{ item.title }}</view>
      <view class="post-summary">{{ item.summary || item.content }}</view>

      <view v-if="item.images && item.images.length" class="post-images">
        <image v-for="image in item.images.slice(0, 3)" :key="image" class="post-image" :src="image" mode="aspectFill" />
      </view>

      <view v-if="item.tagList && item.tagList.length" class="tag-strip compact">
        <view v-for="tag in item.tagList.slice(0, 4)" :key="tag" class="tag-pill mini"># {{ tag }}</view>
      </view>

      <view class="post-meta">
        <text>{{ item.viewCount }} 浏览</text>
        <text>{{ item.likeCount }} 赞</text>
        <text>{{ item.commentCount }} 评论</text>
        <text>{{ item.favoriteCount }} 收藏</text>
      </view>
    </view>

    <view class="floating-plus" @tap="createPost">+</view>
    <AssistantDock scene="community_index" :page-context="pageContext" />
  </view>
</template>

<style scoped lang="scss">
.hero-block {
  margin-bottom: 24rpx;
}

.search-card {
  display: grid;
  gap: 18rpx;
}

.search-row {
  display: flex;
  gap: 16rpx;
  align-items: center;
}

.search-input {
  flex: 1;
  height: 84rpx;
  line-height: 84rpx;
  padding: 0 24rpx;
  border-radius: 24rpx;
  background: rgba(255, 255, 255, 0.06);
  color: #f8f1e7;
}

.search-action {
  margin: 0;
  min-width: 132rpx;
  height: 84rpx;
  padding: 0 30rpx;
  line-height: 84rpx;
  border-radius: 24rpx;
  flex-shrink: 0;
}

.search-helper {
  font-size: 24rpx;
  color: #d2b288;
}

.tab-row {
  display: flex;
  gap: 0;
  margin: 24rpx -4rpx 18rpx;
  padding: 0 4rpx;
  box-sizing: border-box;
}

.tab-pill,
.type-pill {
  margin: 0 4rpx;
  flex-shrink: 0;
}

.type-scroll {
  white-space: nowrap;
}

.type-row {
  display: inline-flex;
  gap: 0;
  padding: 0 4rpx 8rpx;
}

.quick-card {
  margin-top: 20rpx;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.quick-title {
  font-size: 30rpx;
  font-weight: 700;
}

.quick-arrow {
  color: #f8d375;
  font-size: 26rpx;
  font-weight: 700;
}

.board-card {
  margin-top: 24rpx;
}

.board-list {
  display: grid;
  gap: 16rpx;
  margin-top: 18rpx;
}

.board-pill {
  display: flex;
  gap: 18rpx;
  align-items: center;
  padding: 18rpx;
  border-radius: 20rpx;
  background: rgba(255, 255, 255, 0.04);
}

.board-thumb {
  width: 92rpx;
  height: 92rpx;
  border-radius: 18rpx;
}

.board-name {
  font-size: 28rpx;
  font-weight: 700;
}

.board-meta {
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #a99984;
}

.post-card {
  margin-top: 24rpx;
}

.post-top {
  display: flex;
  justify-content: space-between;
  gap: 20rpx;
}

.post-author {
  display: flex;
  gap: 16rpx;
  align-items: center;
}

.post-avatar {
  width: 84rpx;
  height: 84rpx;
  border-radius: 999rpx;
}

.post-name {
  font-size: 28rpx;
  font-weight: 700;
}

.feature-stack {
  display: grid;
  gap: 10rpx;
}

.mark-pill {
  padding: 10rpx 16rpx;
  border-radius: 999rpx;
  font-size: 20rpx;
  text-align: center;
}

.mark-pinned {
  background: rgba(248, 211, 117, 0.16);
  color: #f8d375;
}

.mark-featured {
  background: rgba(201, 106, 44, 0.16);
  color: #ffb07a;
}

.meta-row,
.tag-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}

.meta-row {
  margin-top: 18rpx;
}

.tag-pill {
  padding: 10rpx 18rpx;
  border-radius: 999rpx;
  font-size: 22rpx;
}

.tag-solid {
  background: linear-gradient(180deg, rgba(108, 84, 24, 0.94) 0%, rgba(64, 49, 15, 0.98) 100%);
  color: #f0c35b;
}

.tag-outline {
  background: rgba(255, 255, 255, 0.04);
  color: #d7cab8;
}

.tag-strip.compact {
  margin-top: 18rpx;
}

.tag-pill.mini {
  padding: 8rpx 16rpx;
  font-size: 20rpx;
}

.post-title {
  margin-top: 20rpx;
  font-size: 34rpx;
  font-weight: 700;
  line-height: 1.35;
}

.post-summary {
  margin-top: 16rpx;
  color: #ddd1c1;
  font-size: 28rpx;
  line-height: 1.7;
}

.post-images {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12rpx;
  margin-top: 20rpx;
}

.post-image {
  width: 100%;
  height: 190rpx;
  border-radius: 18rpx;
}

.post-meta {
  margin-top: 18rpx;
  display: flex;
  flex-wrap: wrap;
  gap: 20rpx;
  color: #a99984;
  font-size: 23rpx;
}
</style>
