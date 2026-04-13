<script setup>
import { computed, ref, watch } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import api from '../../services/api'
import storage from '../../services/storage'

const DRAFT_KEY = 'community_post_draft_v2'

const postTypes = [
  { value: 'review', label: '复盘', hint: '适合记录关键轮次、票型和局势转折。' },
  { value: 'board_discussion', label: '板型', hint: '围绕某个板子聊配置、节奏和打法。' },
  { value: 'qa', label: '问答', hint: '把你的疑问抛出来，让大家一起解答。' },
  { value: 'strategy', label: '战术', hint: '总结站边、发言、狼坑等打法经验。' },
  { value: 'help', label: '求助', hint: '描述你的困惑，便于老玩家给建议。' },
  { value: 'general', label: '分享', hint: '适合日常心得、趣事和观察。' },
]

const postType = ref('review')
const title = ref('')
const summary = ref('')
const content = ref('')
const tagInput = ref('')
const images = ref([])
const boards = ref([])
const selectedBoardId = ref(0)
const sessionId = ref('')
const submitting = ref(false)

const currentType = computed(() => postTypes.find((item) => item.value === postType.value) || postTypes[0])
const typeHint = computed(() => currentType.value?.hint || '')
const selectedBoard = computed(() => boards.value.find((item) => item.id === selectedBoardId.value) || null)
const boardPickerIndex = computed(() => {
  const index = boards.value.findIndex((item) => item.id === selectedBoardId.value)
  return index >= 0 ? index : 0
})
const titleCount = computed(() => title.value.trim().length)
const summaryCount = computed(() => summary.value.trim().length)
const contentCount = computed(() => content.value.trim().length)
const tags = computed(() =>
  tagInput.value
    .split(/[\s,，]+/)
    .map((item) => item.trim())
    .filter(Boolean)
    .slice(0, 8),
)

const publishMeta = computed(() => [
  `${contentCount.value || 0} 字正文`,
  `${tags.value.length} 个标签`,
  `${images.value.length} 张图片`,
].join(' · '))

function ensureLogin() {
  if (!storage.getAuthToken()) {
    uni.showToast({ title: '请先登录', icon: 'none' })
    return false
  }
  return true
}

async function loadBoards() {
  try {
    const data = await api.getBoards({ page: 1, size: 80 })
    boards.value = data.list || []
    if (selectedBoardId.value && !boards.value.find((item) => item.id === selectedBoardId.value)) {
      selectedBoardId.value = 0
    }
  } catch (error) {
    boards.value = []
  }
}

function chooseImages() {
  uni.chooseImage({
    count: Math.max(1, 9 - images.value.length),
    success: (res) => {
      const next = [...images.value, ...(res.tempFilePaths || [])]
      images.value = next.slice(0, 9)
    },
  })
}

function removeImage(index) {
  images.value = images.value.filter((_, current) => current !== index)
}

function previewImage(current) {
  uni.previewImage({
    current,
    urls: images.value,
  })
}

function saveDraft() {
  uni.setStorageSync(DRAFT_KEY, {
    postType: postType.value,
    title: title.value,
    summary: summary.value,
    content: content.value,
    tagInput: tagInput.value,
    selectedBoardId: selectedBoardId.value,
    images: images.value,
    sessionId: sessionId.value,
  })
}

function loadDraft() {
  const draft = uni.getStorageSync(DRAFT_KEY)
  if (!draft || typeof draft !== 'object') return
  postType.value = draft.postType || postType.value
  title.value = draft.title || ''
  summary.value = draft.summary || ''
  content.value = draft.content || ''
  tagInput.value = draft.tagInput || ''
  selectedBoardId.value = Number(draft.selectedBoardId || 0)
  images.value = Array.isArray(draft.images) ? draft.images.slice(0, 9) : []
  sessionId.value = draft.sessionId || ''
}

function clearDraft() {
  uni.removeStorageSync(DRAFT_KEY)
}

function selectType(value) {
  postType.value = value
}

function handleBoardChange(event) {
  selectedBoardId.value = boards.value[event.detail.value]?.id || 0
}

function applyPrefill(options) {
  if (!options) return
  if (options.type) postType.value = options.type
  if (options.title) title.value = options.title
  if (options.summary) summary.value = options.summary
  if (options.content) content.value = options.content
  if (options.tags) tagInput.value = decodeURIComponent(options.tags)
  if (options.boardId) selectedBoardId.value = Number(options.boardId || 0)
  if (options.sessionId) sessionId.value = options.sessionId
}

function validateForm() {
  if (!title.value.trim()) {
    uni.showToast({ title: '请先填写标题', icon: 'none' })
    return false
  }
  if (title.value.trim().length < 4) {
    uni.showToast({ title: '标题建议至少 4 个字', icon: 'none' })
    return false
  }
  if (!content.value.trim()) {
    uni.showToast({ title: '请先填写正文', icon: 'none' })
    return false
  }
  if (content.value.trim().length < 10) {
    uni.showToast({ title: '正文建议再补充一点背景', icon: 'none' })
    return false
  }
  return true
}

async function submitPost() {
  if (!ensureLogin() || !validateForm()) return
  try {
    submitting.value = true
    const uploadedImages = []
    for (const image of images.value) {
      if (image.startsWith('http://') || image.startsWith('https://')) {
        uploadedImages.push(image)
      } else {
        const result = await api.uploadImage(image)
        uploadedImages.push(result.url)
      }
    }
    const result = await api.createPost({
      postType: postType.value,
      title: title.value.trim(),
      summary: summary.value.trim(),
      content: content.value.trim(),
      images: uploadedImages,
      boardId: selectedBoardId.value || null,
      boardName: selectedBoard.value?.name || '',
      tagList: tags.value,
      roleTags: [],
      sessionId: sessionId.value || '',
    })
    clearDraft()
    uni.showToast({ title: '发布成功', icon: 'success' })
    setTimeout(() => {
      uni.redirectTo({ url: `/pages/community/detail?id=${result.id}` })
    }, 450)
  } catch (error) {
    uni.showToast({ title: '发布失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

watch([postType, title, summary, content, tagInput, selectedBoardId, images, sessionId], saveDraft, { deep: true })

onLoad(async (options) => {
  loadDraft()
  applyPrefill(options)
  await loadBoards()
})
</script>

<template>
  <view class="page-shell post-page">
    <view class="hero-block">
      <view class="hero-title">发布帖子</view>
      <view class="hero-subtitle">把你的复盘、问题或板型讨论整理成一条更容易被看懂的内容。</view>
      <view class="hero-meta">
        <view class="tag-pill tag-solid">{{ currentType.label }}</view>
        <view v-if="selectedBoard" class="tag-pill tag-outline">{{ selectedBoard.name }}</view>
        <view v-if="sessionId" class="tag-pill tag-outline">关联对局</view>
      </view>
    </view>

    <view class="glass-card section-card form-card">
      <view class="section-head">
        <view class="section-title">帖子类型</view>
        <view class="section-side">{{ currentType.label }}</view>
      </view>
      <view class="type-grid">
        <button
          v-for="item in postTypes"
          :key="item.value"
          class="button-card-toggle type-chip"
          :class="{ active: postType === item.value }"
          @tap="selectType(item.value)"
        >
          {{ item.label }}
        </button>
      </view>
      <view class="type-hint-card">
        <view class="type-hint-label">当前方向</view>
        <view class="type-hint-text">{{ typeHint }}</view>
      </view>
    </view>

    <view class="glass-card section-card form-card">
      <view class="section-head">
        <view class="section-title">标题</view>
        <view class="section-side">{{ titleCount }}/60</view>
      </view>
      <input
        v-model="title"
        class="field-input"
        maxlength="60"
        cursor-spacing="140"
        placeholder="例如：12 人狼美人骑士，这局 7 号为什么一定是狼？"
      />
      <view class="field-tip">建议 10 到 40 字，更容易被快速理解。</view>
    </view>

    <view class="glass-card section-card form-card">
      <view class="section-head">
        <view class="section-title">关联板子</view>
        <view class="section-side">{{ selectedBoard ? '已选择' : '可选' }}</view>
      </view>
      <picker :range="boards" range-key="name" :value="boardPickerIndex" @change="handleBoardChange">
        <view class="picker-field" :class="{ 'picker-field--empty': !selectedBoard }">
          <view class="picker-copy">
            <view class="picker-kicker">BOARD</view>
            <view class="picker-value">{{ selectedBoard ? selectedBoard.name : '选择一个板子帮助分类和推荐' }}</view>
          </view>
          <view class="picker-arrow">›</view>
        </view>
      </picker>
      <view v-if="selectedBoard" class="board-preview">
        <image v-if="selectedBoard.coverImage" class="board-cover" :src="selectedBoard.coverImage" mode="aspectFill" />
        <view class="board-copy">
          <view class="board-name">{{ selectedBoard.name }}</view>
          <view class="board-meta">{{ selectedBoard.playerCount }} 人局 · {{ selectedBoard.lineupSummary || selectedBoard.summary || '已同步板型信息' }}</view>
        </view>
      </view>
    </view>

    <view class="glass-card section-card form-card">
      <view class="section-head">
        <view class="section-title">摘要</view>
        <view class="section-side">{{ summaryCount }}/200</view>
      </view>
      <textarea
        v-model="summary"
        class="field-textarea short"
        maxlength="200"
        auto-height
        cursor-spacing="140"
        placeholder="可选，写一句概括，首页会优先展示这里。"
      />
      <view class="field-tip">一句话带出核心观点，首页会优先展示这里。</view>
    </view>

    <view class="glass-card section-card form-card">
      <view class="section-head">
        <view class="section-title">正文</view>
        <view class="section-side">{{ contentCount }}/5000</view>
      </view>
      <textarea
        v-model="content"
        class="field-textarea tall"
        maxlength="5000"
        auto-height
        cursor-spacing="140"
        placeholder="写下局势转折、规则疑问或你的分析结论。"
      />
      <view class="field-tip">建议至少 30 字，别人更容易接住你的问题和观点。</view>
    </view>

    <view class="glass-card section-card form-card">
      <view class="section-head">
        <view class="section-title">标签</view>
        <view class="section-side">{{ tags.length }}/8</view>
      </view>
      <input
        v-model="tagInput"
        class="field-input"
        maxlength="80"
        cursor-spacing="140"
        placeholder="多个标签用空格或逗号分开，例如：票型 警徽流 复盘"
      />
      <view v-if="tags.length" class="tag-strip">
        <view v-for="tag in tags" :key="tag" class="tag-pill tag-outline"># {{ tag }}</view>
      </view>
      <view class="field-tip">标签越清晰，帖子越容易被同好找到。</view>
    </view>

    <view class="glass-card section-card form-card">
      <view class="section-head">
        <view class="section-title">图片</view>
        <view class="section-side">{{ images.length }}/9</view>
      </view>
      <view class="field-tip field-tip--top">支持删除和大图预览，适合放对局截图、票型图或板型配置。</view>
      <view class="image-grid">
        <view v-for="(image, index) in images" :key="image" class="image-item">
          <image class="pick-image" :src="image" mode="aspectFill" @tap="previewImage(image)" />
          <view class="remove-badge" @tap="removeImage(index)">×</view>
        </view>
        <view v-if="images.length < 9" class="pick-add" @tap="chooseImages">
          <view class="pick-add-symbol">+</view>
          <view class="pick-add-text">添加图片</view>
        </view>
      </view>
    </view>

    <view class="publish-dock glass-card">
      <view class="publish-copy">
        <view class="publish-title">准备发布</view>
        <view class="publish-meta">{{ publishMeta }}</view>
      </view>
      <button class="button-primary publish-button" :disabled="submitting" @tap="submitPost">
        {{ submitting ? '发布中...' : '确认发布' }}
      </button>
    </view>
  </view>
</template>

<style scoped lang="scss">
.post-page {
  padding-bottom: calc(env(safe-area-inset-bottom) + 220rpx);
}

.hero-block {
  display: grid;
  gap: 12rpx;
  margin-bottom: 8rpx;
}

.hero-meta,
.tag-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}

.form-card {
  position: relative;
  overflow: hidden;
}

.form-card::before {
  content: '';
  position: absolute;
  inset: 0 0 auto;
  height: 2rpx;
  background: linear-gradient(90deg, rgba(248, 211, 117, 0.28), rgba(248, 211, 117, 0));
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}

.section-side {
  color: #cba56a;
  font-size: 22rpx;
  font-weight: 700;
}

.type-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12rpx;
  margin-top: 18rpx;
}

.type-chip {
  min-height: 80rpx;
  padding: 0 10rpx;
  text-align: center;
}

.type-hint-card {
  margin-top: 18rpx;
  padding: 18rpx 20rpx;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.035);
}

.type-hint-label {
  color: #cba56a;
  font-size: 20rpx;
  font-weight: 700;
  letter-spacing: 3rpx;
}

.type-hint-text {
  margin-top: 10rpx;
  color: #d8cbb9;
  font-size: 24rpx;
  line-height: 1.7;
}

.picker-field {
  margin-top: 16rpx;
  min-height: 92rpx;
  padding: 18rpx 22rpx;
  border-radius: 22rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18rpx;
  background: rgba(255, 255, 255, 0.05);
  border: 1rpx solid rgba(255, 255, 255, 0.05);
}

.picker-field--empty {
  color: #bda88a;
}

.picker-copy {
  min-width: 0;
  flex: 1;
}

.picker-kicker {
  color: #f8d375;
  font-size: 20rpx;
  font-weight: 700;
  letter-spacing: 4rpx;
}

.picker-value {
  margin-top: 8rpx;
  font-size: 28rpx;
  line-height: 1.4;
  color: #f6ecdd;
}

.picker-arrow {
  color: #f8d375;
  font-size: 40rpx;
  line-height: 1;
}

.board-preview {
  margin-top: 16rpx;
  padding: 18rpx;
  border-radius: 20rpx;
  background: rgba(255, 255, 255, 0.035);
  display: flex;
  gap: 16rpx;
  align-items: center;
}

.board-cover {
  width: 92rpx;
  height: 92rpx;
  border-radius: 18rpx;
  flex-shrink: 0;
}

.board-copy {
  min-width: 0;
}

.board-name {
  font-size: 28rpx;
  font-weight: 700;
  color: #ffffff;
}

.board-meta,
.field-tip {
  color: #a99984;
  font-size: 22rpx;
  line-height: 1.65;
}

.board-meta {
  margin-top: 8rpx;
}

.field-tip {
  margin-top: 12rpx;
}

.field-tip--top {
  margin-top: 10rpx;
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

.image-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14rpx;
  margin-top: 18rpx;
}

.image-item {
  position: relative;
}

.pick-image,
.pick-add {
  width: 100%;
  height: 196rpx;
  border-radius: 18rpx;
}

.pick-add {
  display: grid;
  place-items: center;
  gap: 10rpx;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.05), rgba(255, 255, 255, 0.03)),
    radial-gradient(circle at top right, rgba(248, 211, 117, 0.12), transparent 45%);
  border: 1rpx dashed rgba(248, 211, 117, 0.22);
  color: #f8d375;
}

.pick-add-symbol {
  font-size: 44rpx;
  line-height: 1;
  font-weight: 700;
}

.pick-add-text {
  font-size: 24rpx;
  font-weight: 700;
}

.remove-badge {
  position: absolute;
  right: 10rpx;
  top: 10rpx;
  width: 42rpx;
  height: 42rpx;
  border-radius: 999rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.58);
  color: #fff;
  font-size: 28rpx;
}

.field-textarea.short {
  min-height: 150rpx;
}

.field-textarea.tall {
  min-height: 320rpx;
}

.publish-dock {
  position: sticky;
  bottom: calc(env(safe-area-inset-bottom) + 18rpx);
  z-index: 10;
  margin-top: 28rpx;
  padding: 18rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18rpx;
  border-radius: 24rpx;
  background:
    linear-gradient(180deg, rgba(20, 20, 20, 0.98), rgba(10, 10, 10, 0.96)),
    radial-gradient(circle at top right, rgba(248, 211, 117, 0.12), transparent 42%);
  border: 1rpx solid rgba(248, 211, 117, 0.08);
}

.publish-copy {
  min-width: 0;
  flex: 1;
}

.publish-title {
  font-size: 28rpx;
  font-weight: 700;
  color: #ffffff;
}

.publish-meta {
  margin-top: 8rpx;
  color: #bda88a;
  font-size: 22rpx;
  line-height: 1.55;
}

.publish-button {
  margin: 0;
  min-width: 220rpx;
  padding: 0 28rpx;
}
</style>
