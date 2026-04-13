<script setup>
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import AssistantDock from '../../components/assistant/AssistantDock.vue'
import api from '../../services/api'
import storage from '../../services/storage'
import { formatDateTime, fromNow } from '../../utils/format'

const typeLabelMap = {
  general: '分享',
  review: '复盘',
  board_discussion: '板型',
  qa: '问答',
  strategy: '战术',
  help: '新手',
  recruit: '组局',
}

const post = ref(null)
const commentText = ref('')
const submitting = ref(false)
let postId = 0

async function loadDetail() {
  try {
    const data = await api.getPostDetail(postId)
    post.value = {
      ...data,
      typeLabel: typeLabelMap[data.postType] || '分享',
      createLabel: formatDateTime(data.createTime),
      updateLabel: data.updateTime ? formatDateTime(data.updateTime) : '',
      comments: (data.comments || []).map((item) => ({
        ...item,
        createLabel: fromNow(item.createTime),
      })),
      relatedPosts: (data.relatedPosts || []).map((item) => ({
        ...item,
        typeLabel: typeLabelMap[item.postType] || '分享',
        relativeTime: fromNow(item.createTime),
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

function confirmDialog(title, content) {
  return new Promise((resolve) => {
    uni.showModal({
      title,
      content,
      success: (res) => resolve(Boolean(res.confirm)),
      fail: () => resolve(false),
    })
  })
}

function previewImages(current) {
  if (!post.value?.images?.length) return
  uni.previewImage({
    current,
    urls: post.value.images,
  })
}

function openBoard() {
  if (!post.value?.board?.boardId) return
  uni.navigateTo({ url: `/pages/boards/detail?id=${post.value.board.boardId}` })
}

function openRelated(id) {
  if (!id) return
  uni.navigateTo({ url: `/pages/community/detail?id=${id}` })
}

async function toggleLike() {
  if (!ensureLogin()) return
  await api.togglePostLike(postId)
  await loadDetail()
}

async function toggleFavorite() {
  if (!ensureLogin()) return
  if (post.value?.favorited) {
    await api.unfavoritePost(postId)
  } else {
    await api.favoritePost(postId)
  }
  await loadDetail()
}

async function toggleCommentLike(id) {
  if (!ensureLogin()) return
  await api.toggleCommentLike(id)
  await loadDetail()
}

async function submitComment() {
  if (!ensureLogin()) return
  if (!commentText.value.trim()) {
    uni.showToast({ title: '请输入评论内容', icon: 'none' })
    return
  }
  try {
    submitting.value = true
    await api.createComment({ postId, content: commentText.value })
    commentText.value = ''
    await loadDetail()
  } finally {
    submitting.value = false
  }
}

async function reportPost() {
  if (!ensureLogin()) return
  await api.createReport({ targetType: 'post', targetId: postId, reason: '需要管理员复核' })
  uni.showToast({ title: '已提交举报', icon: 'none' })
}

async function reportComment(commentId) {
  if (!ensureLogin()) return
  await api.createReport({ targetType: 'comment', targetId: commentId, reason: '需要管理员复核' })
  uni.showToast({ title: '已提交举报', icon: 'none' })
}

async function confirmDeletePost() {
  if (!ensureLogin() || !post.value?.owned) return
  const confirmed = await confirmDialog('删除帖子', '删除后不可恢复，确认继续吗？')
  if (!confirmed) return
  await api.deletePost(postId)
  uni.showToast({ title: '已删除', icon: 'success' })
  setTimeout(() => {
    uni.navigateBack()
  }, 500)
}

async function confirmDeleteComment(commentId) {
  if (!ensureLogin()) return
  const confirmed = await confirmDialog('删除评论', '确认删除这条评论吗？')
  if (!confirmed) return
  await api.deleteComment(commentId)
  await loadDetail()
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
        <view class="post-author">
          <image class="post-avatar" :src="post.avatar" mode="aspectFill" />
          <view>
            <view class="post-name">{{ post.nickname }}</view>
            <view class="section-meta">{{ post.createLabel }}</view>
          </view>
        </view>
        <view v-if="post.owned" class="owner-badge">楼主</view>
      </view>

      <view class="meta-row">
        <view class="tag-pill tag-solid">{{ post.typeLabel }}</view>
        <view v-if="post.boardName" class="tag-pill tag-outline" @tap="openBoard">{{ post.boardName }}</view>
        <view v-if="post.pinned" class="tag-pill tag-warm">置顶</view>
        <view v-if="post.featured" class="tag-pill tag-amber">精选</view>
      </view>

      <view class="post-title">{{ post.title }}</view>
      <view v-if="post.summary" class="post-summary">{{ post.summary }}</view>
      <view class="post-content">{{ post.content }}</view>

      <view v-if="post.images && post.images.length" class="post-images">
        <image
          v-for="image in post.images"
          :key="image"
          class="post-image"
          :src="image"
          mode="aspectFill"
          @tap="previewImages(image)"
        />
      </view>

      <view v-if="post.tagList && post.tagList.length" class="tag-strip">
        <view v-for="tag in post.tagList" :key="tag" class="tag-pill tag-outline mini"># {{ tag }}</view>
      </view>

      <view v-if="post.board" class="board-panel" @tap="openBoard">
        <image v-if="post.board.coverImage" class="board-cover" :src="post.board.coverImage" mode="aspectFill" />
        <view>
          <view class="board-title">{{ post.board.boardName }}</view>
          <view class="section-meta">查看板子详情</view>
        </view>
      </view>

      <view class="detail-actions">
        <button class="action-pill" :class="{ 'action-pill--active': post.liked }" @tap="toggleLike">
          <text class="action-icon action-icon--like">♥</text>
          <text>{{ post.likeCount }} 赞</text>
        </button>
        <button class="action-pill" :class="{ 'action-pill--active': post.favorited }" @tap="toggleFavorite">
          <text class="action-icon action-icon--favorite">★</text>
          <text>{{ post.favoriteCount }} 收藏</text>
        </button>
        <button class="action-pill action-pill--report" @tap="reportPost">
          <text class="action-icon action-icon--report">!</text>
          <text>举报</text>
        </button>
        <button v-if="post.owned" class="action-pill action-pill--danger" @tap="confirmDeletePost">删除</button>
      </view>

      <view class="stat-row">
        <text>{{ post.viewCount }} 浏览</text>
        <text>{{ post.commentCount }} 评论</text>
        <text v-if="post.updateLabel">更新于 {{ post.updateLabel }}</text>
      </view>
    </view>

    <view class="glass-card section-card">
      <view class="section-title">评论</view>
      <view v-if="!post.comments || !post.comments.length" class="section-desc">还没有评论，抢个首评吧。</view>
      <view v-for="item in post.comments" :key="item.id" class="comment-item">
        <view class="comment-head">
          <view class="comment-user">
            <image class="comment-avatar" :src="item.avatar" mode="aspectFill" />
            <view>
              <view class="comment-name">
                {{ item.nickname }}
                <text v-if="item.postAuthor" class="comment-tag">楼主</text>
              </view>
              <view class="section-meta">{{ item.createLabel }}</view>
            </view>
          </view>
          <view class="comment-actions">
            <text class="comment-link" @tap="toggleCommentLike(item.id)">{{ item.likeCount }} 赞</text>
            <text class="comment-link" @tap="reportComment(item.id)">举报</text>
            <text v-if="item.owned" class="comment-link danger" @tap="confirmDeleteComment(item.id)">删除</text>
          </view>
        </view>
        <view class="comment-content">
          <text v-if="item.replyToNickname" class="reply-label">回复 {{ item.replyToNickname }}：</text>
          {{ item.content }}
        </view>
      </view>
    </view>

    <view v-if="post.relatedPosts && post.relatedPosts.length" class="glass-card section-card">
      <view class="section-title">相关推荐</view>
      <view v-for="item in post.relatedPosts" :key="item.id" class="related-item" @tap="openRelated(item.id)">
        <view class="related-meta">
          <view class="tag-pill tag-solid mini">{{ item.typeLabel }}</view>
          <view class="section-meta">{{ item.relativeTime }}</view>
        </view>
        <view class="related-title">{{ item.title }}</view>
        <view class="related-summary">{{ item.summary || item.content }}</view>
      </view>
    </view>

    <view class="glass-card section-card">
      <view class="section-title">发表评论</view>
      <textarea v-model="commentText" class="field-textarea" maxlength="600" placeholder="输入你的判断、疑问或复盘补充" />
      <button class="button-primary submit-btn" :disabled="submitting" @tap="submitComment">
        {{ submitting ? '发送中...' : '发送评论' }}
      </button>
    </view>

    <AssistantDock
      scene="community_detail"
      :page-context="{ page: 'community/detail', postId: post.id, postType: post.postType, boardId: post.boardId, commentCount: (post.comments || []).length }"
    />
  </view>
</template>

<style scoped lang="scss">
.post-head,
.comment-head {
  display: flex;
  justify-content: space-between;
  gap: 18rpx;
}

.post-author,
.comment-user {
  display: flex;
  gap: 16rpx;
  align-items: center;
}

.post-avatar,
.comment-avatar {
  width: 84rpx;
  height: 84rpx;
  border-radius: 999rpx;
}

.post-name,
.comment-name,
.board-title {
  font-size: 30rpx;
  font-weight: 700;
}

.owner-badge,
.comment-tag {
  margin-left: 10rpx;
  padding: 4rpx 12rpx;
  border-radius: 999rpx;
  background: rgba(248, 211, 117, 0.16);
  color: #f8d375;
  font-size: 20rpx;
}

.meta-row,
.tag-strip,
.detail-actions,
.stat-row {
  display: flex;
  flex-wrap: wrap;
  gap: 14rpx;
}

.meta-row {
  margin-top: 20rpx;
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
  background: rgba(255, 255, 255, 0.05);
  color: #d7cab8;
}

.tag-warm {
  background: rgba(255, 176, 122, 0.14);
  color: #ffb07a;
}

.tag-amber {
  background: rgba(201, 106, 44, 0.16);
  color: #ffc38f;
}

.tag-pill.mini {
  padding: 8rpx 14rpx;
  font-size: 20rpx;
}

.post-title {
  margin-top: 20rpx;
  font-size: 38rpx;
  font-weight: 700;
  line-height: 1.35;
}

.post-summary {
  margin-top: 18rpx;
  padding: 18rpx 20rpx;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.04);
  color: #f0e1c9;
  line-height: 1.65;
}

.post-content,
.comment-content,
.related-summary {
  margin-top: 20rpx;
  font-size: 28rpx;
  line-height: 1.8;
  color: #ddd1c1;
}

.post-images {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12rpx;
  margin-top: 20rpx;
}

.post-image {
  width: 100%;
  height: 198rpx;
  border-radius: 18rpx;
}

.board-panel {
  margin-top: 24rpx;
  display: flex;
  gap: 18rpx;
  align-items: center;
  padding: 18rpx;
  border-radius: 22rpx;
  background: rgba(255, 255, 255, 0.05);
}

.board-cover {
  width: 104rpx;
  height: 104rpx;
  border-radius: 18rpx;
}

.detail-actions {
  margin-top: 24rpx;
  display: flex;
  gap: 26rpx;
  flex-wrap: wrap;
  align-items: center;
}

.action-pill {
  margin: 0;
  min-width: 0;
  height: auto;
  padding: 0;
  border-radius: 0;
  background: transparent;
  border: none;
  box-shadow: none;
  color: #d7cab8;
  font-size: 24rpx;
  gap: 10rpx;
  flex-shrink: 0;
  font-weight: 700;
  line-height: 1.1;
}

.action-pill::after {
  border: none;
}

.action-pill--active {
  color: #f0c35b;
}

.action-pill--report {
  color: #e7d8c8;
}

.action-pill--danger {
  color: #ff9b8f;
}

.action-icon {
  font-size: 28rpx;
  font-weight: 800;
  line-height: 1;
}

.action-icon--like,
.action-icon--favorite {
  color: currentColor;
}

.action-icon--report {
  color: #ff6f68;
}

.stat-row {
  margin-top: 18rpx;
  color: #a99984;
  font-size: 22rpx;
}

.comment-item {
  margin-top: 22rpx;
  padding: 20rpx;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.04);
}

.comment-actions {
  display: flex;
  gap: 16rpx;
  align-items: center;
  flex-wrap: wrap;
}

.comment-link {
  color: #d7cab8;
  font-size: 22rpx;
}

.comment-link.danger {
  color: #ff9b8f;
}

.reply-label {
  color: #f8d375;
}

.related-item {
  margin-top: 20rpx;
  padding: 18rpx 20rpx;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.04);
}

.related-meta {
  display: flex;
  justify-content: space-between;
  gap: 14rpx;
  align-items: center;
}

.related-title {
  margin-top: 14rpx;
  font-size: 30rpx;
  font-weight: 700;
  line-height: 1.45;
}

.submit-btn {
  margin-top: 18rpx;
}
</style>
