<script setup>
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import api from '../../services/api'
import storage from '../../services/storage'
import { formatDateTime } from '../../utils/format'

const post = ref(null)
const commentText = ref('')
let postId = 0

async function loadDetail() {
  try {
    const data = await api.getPostDetail(postId)
    post.value = {
      ...data,
      createLabel: formatDateTime(data.createTime),
      comments: (data.comments || []).map((item) => ({
        ...item,
        createLabel: formatDateTime(item.createTime),
      })),
    }
  } catch (error) {
    uni.showToast({ title: '帖子加载失败', icon: 'none' })
  }
}

function ensureLogin() {
  if (!storage.getAuthToken()) {
    uni.showToast({ title: '请先登录', icon: 'none' })
    return false
  }
  return true
}

async function toggleLike() {
  if (!ensureLogin()) return
  await api.togglePostLike(postId)
  await loadDetail()
}

async function submitComment() {
  if (!ensureLogin()) return
  if (!commentText.value.trim()) {
    uni.showToast({ title: '请输入评论内容', icon: 'none' })
    return
  }
  await api.createComment({ postId, content: commentText.value })
  commentText.value = ''
  await loadDetail()
}

async function reportPost() {
  if (!ensureLogin()) return
  await api.createReport({ targetType: 'post', targetId: postId, reason: '需要管理员复核' })
  uni.showToast({ title: '已提交举报', icon: 'none' })
}

onLoad((options) => {
  postId = Number(options?.id || 0)
  loadDetail()
})
</script>

<template>
  <view v-if="post" class="page-shell">
    <view class="glass-card section-card">
      <view class="post-head">
        <image class="post-avatar" :src="post.avatar" mode="aspectFill" />
        <view>
          <view class="post-name">{{ post.nickname }}</view>
          <view class="section-meta">{{ post.createLabel }}</view>
        </view>
      </view>
      <view class="post-content">{{ post.content }}</view>
      <view v-if="post.images && post.images.length" class="post-images">
        <image v-for="image in post.images" :key="image" class="post-image" :src="image" mode="aspectFill" />
      </view>
      <view class="detail-actions">
        <view class="pill pill-gold" @tap="toggleLike">{{ post.likeCount }} 赞</view>
        <view class="pill pill-white" @tap="reportPost">举报</view>
      </view>
    </view>

    <view class="glass-card section-card">
      <view class="section-title">评论</view>
      <view v-if="!post.comments || !post.comments.length" class="section-desc">还没有评论，抢个首评吧。</view>
      <view v-for="item in post.comments" :key="item.id" class="comment-item">
        <view class="comment-head">
          <image class="comment-avatar" :src="item.avatar" mode="aspectFill" />
          <view>
            <view class="comment-name">{{ item.nickname }}</view>
            <view class="section-meta">{{ item.createLabel }}</view>
          </view>
        </view>
        <view class="comment-content">{{ item.content }}</view>
      </view>
    </view>

    <view class="glass-card section-card">
      <view class="section-title">发表评论</view>
      <textarea v-model="commentText" class="field-textarea" placeholder="输入你的判断或复盘结论" />
      <button class="button-primary submit-btn" @tap="submitComment">发送评论</button>
    </view>
  </view>
</template>

<style scoped lang="scss">
.post-head,
.comment-head {
  display: flex;
  align-items: center;
  gap: 16rpx;
}

.post-avatar,
.comment-avatar {
  width: 80rpx;
  height: 80rpx;
  border-radius: 999rpx;
}

.post-name,
.comment-name {
  font-size: 28rpx;
  font-weight: 700;
}

.post-content,
.comment-content {
  margin-top: 18rpx;
  font-size: 28rpx;
  line-height: 1.8;
}

.post-images {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12rpx;
  margin-top: 18rpx;
}

.post-image {
  width: 100%;
  height: 180rpx;
  border-radius: 16rpx;
}

.detail-actions {
  display: flex;
  gap: 12rpx;
  margin-top: 18rpx;
}

.comment-item {
  margin-top: 20rpx;
  padding: 18rpx 20rpx;
  border-radius: 16rpx;
  background: rgba(255, 255, 255, 0.04);
}

.submit-btn {
  margin-top: 18rpx;
}
</style>
