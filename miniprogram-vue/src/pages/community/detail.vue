<script setup>
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import AssistantDock from '../../components/assistant/AssistantDock.vue'
import api from '../../services/api'
import storage from '../../services/storage'
import { formatDateTime } from '../../utils/format'

const post = ref(null)
const commentText = ref('')
const busy = ref(false)
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

function showActionError(error, fallback) {
  uni.showToast({ title: (error?.message || fallback).slice(0, 30), icon: 'none' })
}

async function toggleLike() {
  if (busy.value) return
  if (!ensureLogin()) return
  busy.value = true
  try {
    await api.togglePostLike(postId)
    await loadDetail()
  } catch (error) {
    showActionError(error, '点赞失败')
  } finally {
    busy.value = false
  }
}

async function submitComment() {
  if (busy.value) return
  if (!ensureLogin()) return
  if (!commentText.value.trim()) {
    uni.showToast({ title: '请输入评论内容', icon: 'none' })
    return
  }
  busy.value = true
  try {
    await api.createComment({ postId, content: commentText.value })
    // 只有成功才清空，失败时保留用户输入
    commentText.value = ''
    await loadDetail()
  } catch (error) {
    showActionError(error, '评论失败')
  } finally {
    busy.value = false
  }
}

function reportPost() {
  if (busy.value) return
  if (!ensureLogin()) return
  uni.showModal({
    title: '举报这条帖子',
    content: '确认举报吗？管理员会复核内容并处理。',
    confirmText: '举报',
    success: async (res) => {
      if (!res.confirm) return
      busy.value = true
      try {
        await api.createReport({ targetType: 'post', targetId: postId, reason: '需要管理员复核' })
        uni.showToast({ title: '已提交举报', icon: 'none' })
      } catch (error) {
        showActionError(error, '举报失败')
      } finally {
        busy.value = false
      }
    },
  })
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
      <button class="button-primary submit-btn" :loading="busy" :disabled="busy" @tap="submitComment">发送评论</button>
    </view>

    <AssistantDock
      scene="community_detail"
      :page-context="{ page: 'community/detail', postId: post.id, nickname: post.nickname, commentCount: (post.comments || []).length }"
    />
  </view>
</template>

<style scoped lang="scss">
.post-head,
.comment-head {
  display: flex;
  align-items: center;
  gap: 18rpx;
}

.post-avatar,
.comment-avatar {
  width: 84rpx;
  height: 84rpx;
  border-radius: 999rpx;
}

.post-name,
.comment-name {
  font-size: 28rpx;
  font-weight: 700;
}

.post-content,
.comment-content {
  margin-top: 16rpx;
  font-size: 28rpx;
  line-height: 1.82;
}

.post-images {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12rpx;
  margin-top: 16rpx;
}

.post-image {
  width: 100%;
  height: 180rpx;
  border-radius: 18rpx;
}

.detail-actions {
  display: flex;
  gap: 12rpx;
  margin-top: 16rpx;
}

.comment-item {
  margin-top: 16rpx;
  padding: 18rpx 20rpx;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.04);
}

.submit-btn {
  margin-top: 20rpx;
}
</style>
