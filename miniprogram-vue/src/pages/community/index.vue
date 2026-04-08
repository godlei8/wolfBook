<script setup>
import { ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import api from '../../services/api'
import { fromNow } from '../../utils/format'

const posts = ref([])

async function loadPosts() {
  try {
    const data = await api.getPosts(1, 20)
    posts.value = (data.list || []).map((post) => ({ ...post, relativeTime: fromNow(post.createTime) }))
  } catch (error) {
    uni.showToast({ title: '社区加载失败', icon: 'none' })
  }
}

function openPost(id) {
  uni.navigateTo({ url: `/pages/community/detail?id=${id}` })
}

function createPost() {
  uni.navigateTo({ url: '/pages/community/post' })
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
    <view class="hero-title">社区</view>
    <view class="hero-subtitle">分享板型心得、复盘记录和局内判断。</view>

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

    <view class="floating-plus" @tap="createPost">+</view>
  </view>
</template>

<style scoped lang="scss">
.post-card {
  margin-top: 24rpx;
}

.post-head {
  display: flex;
  align-items: center;
  gap: 16rpx;
}

.post-avatar {
  width: 88rpx;
  height: 88rpx;
  border-radius: 999rpx;
}

.post-name {
  font-size: 28rpx;
  font-weight: 700;
}

.post-content {
  margin-top: 18rpx;
  line-height: 1.8;
  font-size: 28rpx;
}

.post-images {
  display: flex;
  gap: 12rpx;
  margin-top: 18rpx;
}

.post-image {
  flex: 1;
  height: 180rpx;
  border-radius: 16rpx;
}

.post-meta {
  margin-top: 16rpx;
  color: #8f8f8f;
  font-size: 24rpx;
}
</style>
