<script setup>
import { ref } from 'vue'
import { onLoad, onPullDownRefresh, onReachBottom, onShow } from '@dcloudio/uni-app'
import AssistantDock from '../../components/assistant/AssistantDock.vue'
import api from '../../services/api'
import storage from '../../services/storage'
import { fromNow } from '../../utils/format'

const PAGE_SIZE = 20

const posts = ref([])
const page = ref(1)
const hasMore = ref(false)
const loadingMore = ref(false)
const sourceContext = ref(null)

function decoratePost(post) {
  return { ...post, relativeTime: fromNow(post.createTime) }
}

async function loadPosts(options = {}) {
  const append = options.append === true
  if (append && (loadingMore.value || !hasMore.value)) return
  const nextPage = append ? page.value + 1 : 1
  if (append) {
    loadingMore.value = true
  }
  try {
    const data = await api.getPosts(nextPage, PAGE_SIZE)
    const list = (data.list || []).map(decoratePost)
    posts.value = append ? posts.value.concat(list) : list
    page.value = nextPage
    const total = Number(data.total) || 0
    hasMore.value = list.length > 0 && posts.value.length < total
  } catch (error) {
    uni.showToast({ title: error?.message || '社区加载失败', icon: 'none' })
  } finally {
    loadingMore.value = false
  }
}

function dismissSourceContext() {
  sourceContext.value = null
}

function openPost(id) {
  uni.navigateTo({ url: `/pages/community/detail?id=${id}` })
}

function createPost() {
  uni.navigateTo({ url: '/pages/community/post' })
}

onLoad(() => loadPosts())
onShow(() => {
  const context = storage.consumeCommunityFilterContext()
  if (context?.boardName) {
    sourceContext.value = context
  }
  loadPosts()
})
onPullDownRefresh(async () => {
  await loadPosts()
  uni.stopPullDownRefresh()
})
onReachBottom(() => loadPosts({ append: true }))
</script>

<template>
  <view class="page-shell">
    <view class="hero-title">社区</view>
    <view class="hero-subtitle">分享板型心得、复盘记录和局内判断。</view>

    <view v-if="sourceContext" class="source-chip" @tap="dismissSourceContext">
      来自法官房间 · {{ sourceContext.boardName }}，聊聊这局？ ✕
    </view>

    <view v-if="posts.length === 0" class="empty-state glass-card section-card">还没有帖子，去发第一条吧。</view>

    <view v-for="item in posts" :key="item.id" class="glass-card section-card post-card" @tap="openPost(item.id)">
      <view class="post-head">
        <image class="post-avatar" :src="item.avatar" mode="aspectFill" />
        <view class="post-user">
          <view class="post-name">{{ item.nickname }}</view>
          <view class="section-meta">{{ item.relativeTime }}</view>
        </view>
      </view>
      <view class="post-content">{{ item.content }}</view>
      <view v-if="item.images && item.images.length" class="post-images">
        <image v-for="image in item.images.slice(0, 3)" :key="image" class="post-image" :src="image" mode="aspectFill" />
      </view>
      <view class="post-meta">{{ item.likeCount }} 赞 · {{ item.commentCount }} 评论</view>
    </view>

    <view v-if="posts.length" class="list-footer">
      {{ loadingMore ? '加载中…' : hasMore ? '上拉加载更多' : '没有更多了' }}
    </view>

    <view class="floating-plus" @tap="createPost">+</view>
    <AssistantDock scene="community_index" :page-context="{ page: 'community/index', postCount: posts.length }" />
  </view>
</template>

<style scoped lang="scss">
.post-head {
  display: flex;
  align-items: center;
  gap: 18rpx;
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

.post-content {
  margin-top: 16rpx;
  line-height: 1.82;
  font-size: 28rpx;
}

.post-images {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12rpx;
  margin-top: 16rpx;
}

.post-image {
  display: block;
  width: 100%;
  height: 180rpx;
  border-radius: 18rpx;
}

.post-meta {
  margin-top: 14rpx;
  color: #8f8f8f;
  font-size: 24rpx;
  line-height: 1.55;
}

.source-chip {
  display: inline-flex;
  align-items: center;
  margin-top: var(--content-gap);
  padding: 12rpx 22rpx;
  border-radius: 999rpx;
  background: rgba(255, 192, 0, 0.14);
  color: #ffc000;
  font-size: 24rpx;
  line-height: 1.4;
}

.list-footer {
  padding: 28rpx 0 8rpx;
  text-align: center;
  color: #8f8f8f;
  font-size: 24rpx;
}
</style>
