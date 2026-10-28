<script setup>
// 发帖：分类（战报 / 教学 / 讨论）+ 可选标题 + 正文 + 关联板子 + 图片。
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import NavBar from '../../components/NavBar.vue'
import api from '../../services/api'
import storage from '../../services/storage'

const CATEGORIES = ['讨论', '战报', '教学']

const category = ref('讨论')
const title = ref('')
const content = ref('')
const images = ref([])
const boards = ref([])
const boardId = ref(0)
const submitting = ref(false)

const selectedBoard = computed(() => boards.value.find((item) => item.id === boardId.value) || null)
const canSubmit = computed(() => !!content.value.trim() && !submitting.value)

function ensureLogin() {
  if (!storage.getAuthToken()) {
    uni.showToast({ title: '请先登录', icon: 'none' })
    return false
  }
  return true
}

async function loadBoards() {
  try {
    const data = await api.getBoards({ page: 1, size: 50 })
    boards.value = data.list || []
  } catch (error) {
    boards.value = []
  }
}

// 选择器第一项是「不关联」，之后是全部板子
const boardNames = computed(() => ['不关联', ...boards.value.map((item) => item.name)])
const boardPickerIndex = computed(() => {
  const index = boards.value.findIndex((item) => item.id === boardId.value)
  return index >= 0 ? index + 1 : 0
})

function chooseBoard(event) {
  const index = Number(event.detail?.value || 0)
  boardId.value = index === 0 ? 0 : boards.value[index - 1]?.id || 0
}

function chooseImages() {
  uni.chooseImage({
    count: Math.max(1, 9 - images.value.length),
    success: (res) => {
      images.value = images.value.concat(res.tempFilePaths || []).slice(0, 9)
    },
  })
}

function removeImage(index) {
  images.value = images.value.filter((_, i) => i !== index)
}

async function submitPost() {
  if (submitting.value) return
  if (!ensureLogin()) return
  if (!content.value.trim()) {
    uni.showToast({ title: '请输入帖子内容', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    const uploadedImages = []
    for (const image of images.value) {
      const result = await api.uploadImage(image)
      uploadedImages.push(result.url)
    }
    await api.createPost({
      content: content.value,
      images: uploadedImages,
      title: title.value.trim(),
      category: category.value,
      boardId: boardId.value || null,
    })
    uni.showToast({ title: '发布成功', icon: 'success' })
    // 成功后保持 submitting，等待返回期间不能再次点击发布
    setTimeout(() => {
      uni.navigateBack()
    }, 500)
  } catch (error) {
    submitting.value = false
    uni.showToast({ title: error?.message || '发布失败', icon: 'none' })
  }
}

onLoad((options) => {
  boardId.value = Number(options?.boardId || 0)
  if (boardId.value) category.value = '战报'
  loadBoards()
})
</script>

<template>
  <view class="nt-page nt-page--bar">
    <NavBar back title="发帖" />

    <view class="nt-gutter nt-stack cp-body">
      <view class="cp-cats">
        <text v-for="item in CATEGORIES" :key="item" class="nt-chip" :class="{ 'nt-chip--on': category === item }" @tap="category = item">{{ item }}</text>
      </view>

      <view class="nt-field">
        <input v-model="title" class="nt-field__input cp-title serif" maxlength="40" placeholder="标题（可选），比如：女巫第二夜不救人" placeholder-class="nt-placeholder" />
      </view>

      <view class="cp-content">
        <textarea v-model="content" class="nt-textarea cp-textarea" maxlength="500" placeholder="说说这局：关键判断、复盘、想问大家的问题" placeholder-class="nt-placeholder" />
        <text class="cp-count">{{ content.length }}/500</text>
      </view>

      <picker mode="selector" :range="boardNames" :value="boardPickerIndex" @change="chooseBoard">
        <view class="nt-row">
          <view class="cp-board">
            <text class="nt-label">关联板子</text>
            <text class="cp-board__name" :class="{ 'c-moon': selectedBoard }">{{ selectedBoard ? selectedBoard.name : '不关联' }}</text>
          </view>
          <text class="nt-arrow">›</text>
        </view>
      </picker>

      <view class="cp-images">
        <view v-for="(image, index) in images" :key="image" class="cp-image">
          <image class="cp-image__img" :src="image" mode="aspectFill" />
          <text class="cp-image__x" @tap="removeImage(index)">✕</text>
        </view>
        <view v-if="images.length < 9" class="cp-image cp-image--add" @tap="chooseImages">
          <text class="cp-image__plus">＋</text>
          <text class="cp-image__hint">{{ images.length }}/9</text>
        </view>
      </view>
    </view>

    <view class="nt-bar">
      <view class="nt-btn nt-btn--gold cp-submit" :class="{ 'nt-btn--disabled': !canSubmit }" @tap="submitPost">
        {{ submitting ? '发布中…' : '发布到酒馆' }}
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.cp-body {
  margin-top: 16rpx;
}

.cp-cats {
  display: flex;
  gap: 16rpx;
}

.cp-title {
  font-size: 32rpx;
  font-weight: 900;
}

.cp-content {
  position: relative;
}

.cp-textarea {
  min-height: 360rpx;
}

.cp-count {
  position: absolute;
  right: 24rpx;
  bottom: 16rpx;
  color: var(--text-3);
  font-size: 22rpx;
}

.cp-board {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.cp-board__name {
  font-size: 30rpx;
  font-weight: 700;
}

.cp-images {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 20rpx;
}

.cp-image {
  position: relative;
  padding-top: 100%;
  border-radius: 24rpx;
  border: 4rpx solid var(--ink);
  box-shadow: 4rpx 4rpx 0 var(--ink);
  overflow: hidden;
  background: var(--panel);
}

.cp-image__img {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
}

.cp-image__x {
  position: absolute;
  right: 8rpx;
  top: 8rpx;
  width: 44rpx;
  height: 44rpx;
  border-radius: 50%;
  background: var(--ink);
  color: var(--text);
  font-size: 22rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}

.cp-image--add {
  border-style: dashed;
  border-color: var(--line);
  box-shadow: none;
}

.cp-image__plus,
.cp-image__hint {
  position: absolute;
  left: 0;
  right: 0;
  text-align: center;
}

.cp-image__plus {
  top: 30%;
  color: var(--gold);
  font-size: 56rpx;
}

.cp-image__hint {
  bottom: 20%;
  color: var(--text-3);
  font-size: 22rpx;
}

.cp-submit {
  flex: 1;
}
</style>
