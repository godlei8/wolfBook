<script setup>
// 帖子详情：帖子卡（标题、正文、全部图片、关联板子）+ 评论（可点赞）+ 底部评论输入。
import { ref } from 'vue'
import { onLoad, onShareAppMessage } from '@dcloudio/uni-app'
import NavBar from '../../components/NavBar.vue'
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
    const result = await api.togglePostLike(postId)
    post.value = { ...post.value, liked: result.liked, likeCount: result.likeCount }
  } catch (error) {
    showActionError(error, '点赞失败')
  } finally {
    busy.value = false
  }
}

async function toggleCommentLike(comment) {
  if (!ensureLogin()) return
  try {
    const result = await api.toggleCommentLike(comment.id)
    post.value = {
      ...post.value,
      comments: post.value.comments.map((item) => (item.id === comment.id ? { ...item, liked: result.liked, likeCount: result.likeCount } : item)),
    }
  } catch (error) {
    showActionError(error, '点赞失败')
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

function previewImage(current) {
  uni.previewImage({ current, urls: post.value?.images || [] })
}

function openBoard() {
  if (!post.value?.boardId) return
  uni.navigateTo({ url: `/pages/boards/detail?id=${post.value.boardId}` })
}

onLoad((options) => {
  postId = Number(options?.id || 0)
  loadDetail()
})

onShareAppMessage(() => ({
  title: post.value?.title || (post.value?.content || '').slice(0, 24) || '酒馆里的一条帖子',
  path: `/pages/community/detail?id=${postId}`,
}))
</script>

<template>
  <view class="nt-page nt-page--bar">
    <NavBar back title="帖子" />

    <view v-if="post" class="nt-gutter nt-stack cd-body">
      <view class="nt-ink cd-post">
        <view class="cd-head">
          <view class="nt-token cd-avatar">
            <image class="nt-token__img" :src="post.avatar" mode="aspectFill" />
          </view>
          <view class="cd-who">
            <text class="cd-name">{{ post.nickname }}</text>
            <text class="cd-time">{{ post.createLabel }}</text>
          </view>
          <text v-if="post.category" class="nt-tag">{{ post.category }}</text>
        </view>
        <text v-if="post.title" class="cd-title serif">{{ post.title }}</text>
        <text class="cd-text">{{ post.content }}</text>
        <view v-if="post.images && post.images.length" class="cd-images">
          <view v-for="image in post.images" :key="image" class="cd-image" @tap="previewImage(image)">
            <image class="cd-image__img" :src="image" mode="aspectFill" />
          </view>
        </view>
        <view v-if="post.boardName" class="nt-row cd-board" @tap="openBoard">
          <view class="cd-board__text">
            <text class="nt-label">关联板子</text>
            <text class="cd-board__name">{{ post.boardName }}</text>
          </view>
          <text class="nt-arrow">›</text>
        </view>
        <view class="cd-actions">
          <view class="nt-btn nt-btn--small" :class="post.liked ? 'nt-btn--gold' : 'nt-btn--ghost'" @tap="toggleLike">
            {{ post.liked ? '♥' : '♡' }} {{ post.likeCount }}
          </view>
          <text class="cd-report" @tap="reportPost">举报</text>
        </view>
      </view>

      <view class="nt-panel nt-panel--tight">
        <view class="nt-panel__head cd-comments__head">
          <text class="nt-h2">评论</text>
          <text class="nt-meta">{{ post.comments.length }} 条</text>
        </view>
        <view v-if="!post.comments.length" class="nt-empty cd-empty">还没有评论，抢个首评。</view>
        <view v-for="item in post.comments" :key="item.id" class="cd-comment">
          <view class="nt-token cd-comment__avatar">
            <image class="nt-token__img" :src="item.avatar" mode="aspectFill" />
          </view>
          <view class="cd-comment__body">
            <view class="cd-comment__top">
              <text class="cd-comment__name">{{ item.nickname }}</text>
              <text class="cd-comment__like" :class="{ 'c-gold': item.liked }" @tap="toggleCommentLike(item)">{{ item.liked ? '♥' : '♡' }} {{ item.likeCount || 0 }}</text>
            </view>
            <text class="cd-comment__text">{{ item.content }}</text>
            <text class="cd-time">{{ item.createLabel }}</text>
          </view>
        </view>
      </view>
    </view>

    <view class="nt-bar">
      <view class="nt-field cd-input">
        <input v-model="commentText" class="nt-field__input" maxlength="200" placeholder="说说你的判断" placeholder-class="nt-placeholder" confirm-type="send" @confirm="submitComment" />
      </view>
      <view class="nt-btn nt-btn--gold" :class="{ 'nt-btn--disabled': busy }" @tap="submitComment">发送</view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.cd-body {
  margin-top: 16rpx;
}

.cd-post {
  padding: 32rpx;
  display: flex;
  flex-direction: column;
  gap: 24rpx;
}

.cd-head {
  display: flex;
  align-items: center;
  gap: 20rpx;
}

.cd-avatar {
  width: 80rpx;
  height: 80rpx;
}

.cd-who {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2rpx;
}

.cd-name {
  font-size: 30rpx;
  font-weight: 700;
}

.cd-time {
  color: var(--text-3);
  font-size: 24rpx;
}

.cd-title {
  font-size: 42rpx;
  font-weight: 900;
  line-height: 1.4;
}

.cd-text {
  color: var(--text-soft);
  font-size: 30rpx;
  line-height: 1.8;
  white-space: pre-wrap;
}

.cd-images {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16rpx;
}

.cd-image {
  position: relative;
  padding-top: 100%;
  border-radius: 20rpx;
  border: 4rpx solid var(--ink);
  box-shadow: 4rpx 4rpx 0 var(--ink);
  overflow: hidden;
}

.cd-image__img {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
}

.cd-board__text {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.cd-board__name {
  color: var(--moon);
  font-size: 30rpx;
  font-weight: 700;
}

.cd-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.cd-report {
  color: var(--text-3);
  font-size: 26rpx;
}

.cd-comments__head {
  padding-bottom: 16rpx;
}

.cd-empty {
  padding: 40rpx 0;
}

.cd-comment {
  display: flex;
  gap: 20rpx;
  padding: 24rpx 0;
  border-top: 2rpx solid var(--panel-line);
}

.cd-comment__avatar {
  width: 64rpx;
  height: 64rpx;
}

.cd-comment__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.cd-comment__top {
  display: flex;
  justify-content: space-between;
  gap: 16rpx;
}

.cd-comment__name {
  font-size: 28rpx;
  font-weight: 700;
}

.cd-comment__like {
  color: var(--text-3);
  font-size: 26rpx;
}

.cd-comment__text {
  font-size: 28rpx;
  line-height: 1.7;
}

.cd-input {
  flex: 1;
  min-height: 88rpx;
}
</style>
