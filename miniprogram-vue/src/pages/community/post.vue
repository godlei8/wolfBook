<script setup>
import { ref } from 'vue'
import api from '../../services/api'
import storage from '../../services/storage'

const content = ref('')
const images = ref([])
const submitting = ref(false)

function ensureLogin() {
  if (!storage.getAuthToken()) {
    uni.showToast({ title: '请先登录', icon: 'none' })
    return false
  }
  return true
}

function chooseImages() {
  uni.chooseImage({
    count: 9,
    success: (res) => {
      images.value = res.tempFilePaths || []
    },
  })
}

async function submitPost() {
  if (!ensureLogin()) return
  if (!content.value.trim()) {
    uni.showToast({ title: '请输入帖子内容', icon: 'none' })
    return
  }
  try {
    submitting.value = true
    const uploadedImages = []
    for (const image of images.value) {
      const result = await api.uploadImage(image)
      uploadedImages.push(result.url)
    }
    await api.createPost({ content: content.value, images: uploadedImages })
    uni.showToast({ title: '发布成功', icon: 'success' })
    setTimeout(() => {
      uni.navigateBack()
    }, 500)
  } catch (error) {
    uni.showToast({ title: '发布失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <view class="page-shell">
    <view class="hero-title">发布帖子</view>
    <view class="hero-subtitle">记录局内判断、复盘想法或板型心得。</view>

    <view class="glass-card section-card">
      <view class="section-title">内容</view>
      <textarea v-model="content" class="field-textarea" maxlength="500" placeholder="最多 500 字" />
    </view>

    <view class="glass-card section-card">
      <view class="section-title">图片</view>
      <view class="section-desc">最多 9 张，支持相册选择。</view>
      <view class="image-grid">
        <image v-for="image in images" :key="image" class="pick-image" :src="image" mode="aspectFill" />
        <view class="pick-add" @tap="chooseImages">添加</view>
      </view>
    </view>

    <button class="button-primary" :disabled="submitting" @tap="submitPost">{{ submitting ? '发布中...' : '确认发布' }}</button>
  </view>
</template>

<style scoped lang="scss">
.image-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14rpx;
  margin-top: 18rpx;
}

.pick-image,
.pick-add {
  width: 100%;
  height: 190rpx;
  border-radius: 16rpx;
}

.pick-add {
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.05);
  color: #ffc000;
  font-size: 26rpx;
  font-weight: 700;
}
</style>
