<script setup>
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import api from '../../services/api'

const role = ref(null)

async function loadRole(id) {
  try {
    role.value = await api.getRoleDetail(id)
  } catch (error) {
    uni.showToast({ title: '角色加载失败', icon: 'none' })
  }
}

function openBoard(id) {
  uni.navigateTo({ url: `/pages/boards/detail?id=${id}` })
}

onLoad((options) => loadRole(Number(options?.id || 0)))
</script>

<template>
  <view v-if="role" class="page-shell">
    <view class="role-hero glass-card">
      <image class="role-banner" :src="role.fullIllustration || role.portrait" mode="aspectFill" />
      <view class="role-mask">
        <view class="role-chip-row">
          <view class="pill pill-gold">{{ role.faction }}</view>
          <view class="pill pill-white">{{ role.roleType }}</view>
        </view>
        <view class="role-title">{{ role.name }}</view>
        <view v-if="role.alias" class="role-alias">{{ role.alias }}</view>
        <view class="role-camp">{{ role.camp }}</view>
      </view>
    </view>

    <view class="glass-card section-card">
      <view class="section-title">技能说明</view>
      <view class="section-desc">{{ role.skill }}</view>
    </view>

    <view class="glass-card section-card">
      <view class="section-title">角色背景</view>
      <view class="section-desc">{{ role.background || '暂无背景说明。' }}</view>
    </view>

    <view v-if="role.faqs && role.faqs.length" class="glass-card section-card">
      <view class="section-title">常见问答</view>
      <view v-for="faq in role.faqs" :key="faq.question" class="faq-item">
        <view class="faq-q">Q. {{ faq.question }}</view>
        <view class="faq-a">A. {{ faq.answer }}</view>
      </view>
    </view>

    <view v-if="role.boards && role.boards.length" class="glass-card section-card">
      <view class="section-title">出现过的板子</view>
      <scroll-view scroll-x class="related-row">
        <view v-for="board in role.boards" :key="board.id" class="related-board" @tap="openBoard(board.id)">
          <image class="related-cover" :src="board.coverImage" mode="aspectFill" />
          <view class="related-name">{{ board.name }}</view>
          <view class="section-meta">{{ board.playerCount }} 人</view>
        </view>
      </scroll-view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.role-hero {
  position: relative;
  overflow: hidden;
}

.role-banner {
  width: 100%;
  height: 500rpx;
  display: block;
}

.role-mask {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  padding: 28rpx;
  background: linear-gradient(180deg, rgba(0, 0, 0, 0.12) 6%, rgba(0, 0, 0, 0.88) 100%);
}

.role-chip-row {
  display: flex;
  gap: 12rpx;
}

.role-title {
  margin-top: 18rpx;
  font-size: 50rpx;
  font-weight: 700;
}

.role-alias,
.role-camp {
  margin-top: 10rpx;
  color: #d0d0d0;
  font-size: 24rpx;
}

.faq-item {
  margin-top: 16rpx;
  padding: 18rpx 20rpx;
  border-radius: 16rpx;
  background: rgba(255, 255, 255, 0.04);
}

.faq-q {
  color: #ffc000;
  font-size: 24rpx;
  font-weight: 700;
}

.faq-a {
  margin-top: 10rpx;
  color: #d0d0d0;
  line-height: 1.7;
}

.related-row {
  margin-top: 18rpx;
  white-space: nowrap;
}

.related-board {
  display: inline-block;
  width: 260rpx;
  margin-right: 18rpx;
}

.related-cover {
  width: 260rpx;
  height: 160rpx;
  border-radius: 16rpx;
}

.related-name {
  margin-top: 12rpx;
  font-size: 26rpx;
  font-weight: 700;
}
</style>
