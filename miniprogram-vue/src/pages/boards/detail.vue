<script setup>
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import AssistantDock from '../../components/assistant/AssistantDock.vue'
import api from '../../services/api'

const board = ref(null)
let boardId = 0

async function loadDetail() {
  try {
    board.value = await api.getBoardDetail(boardId)
  } catch (error) {
    uni.showToast({ title: '详情加载失败', icon: 'none' })
  }
}

function openRole(id) {
  uni.navigateTo({ url: `/pages/roles/detail?id=${id}` })
}

onLoad((options) => {
  boardId = Number(options?.id || 0)
  loadDetail()
})
</script>

<template>
  <view v-if="board" class="page-shell">
    <view class="detail-hero glass-card">
      <image class="detail-cover" :src="board.coverImage" mode="aspectFill" />
      <view class="detail-mask">
        <view class="detail-cover-top">
          <view class="pill pill-gold">{{ board.playerCount }} 人局</view>
          <view class="pill pill-white">{{ board.difficulty }}</view>
        </view>
        <view class="detail-title">{{ board.name }}</view>
        <view class="detail-subtitle">{{ board.ruleType }} · {{ board.winCondition }}</view>
      </view>
    </view>

    <view class="glass-card section-card">
      <view class="section-title">板型概览</view>
      <view class="section-desc">
        {{ board.cardDescription || board.summary || board.lineupSummary || board.campSummary || board.briefConfig }}
      </view>
    </view>

    <view class="glass-card section-card">
      <view class="section-title">角色配置</view>
      <view class="role-grid">
        <view v-for="item in board.roles" :key="item.roleId" class="role-cell" @tap="openRole(item.roleId)">
          <image class="role-portrait" :src="item.portrait" mode="aspectFill" />
          <view class="role-name">{{ item.name }}</view>
          <view class="role-count">x{{ item.count }}</view>
        </view>
      </view>
    </view>

    <view class="glass-card section-card">
      <view class="section-title">规则详情</view>
      <view v-for="rule in board.specialRules" :key="rule" class="rule-item">{{ rule }}</view>
    </view>

    <view v-if="board.tips && board.tips.length" class="glass-card section-card">
      <view class="section-title">小贴士</view>
      <view v-for="tip in board.tips" :key="tip" class="tip-item">{{ tip }}</view>
    </view>

    <view v-if="board.faqs && board.faqs.length" class="glass-card section-card">
      <view class="section-title">常见问题</view>
      <view v-for="faq in board.faqs" :key="faq.question" class="faq-item">
        <view class="faq-q">Q. {{ faq.question }}</view>
        <view class="faq-a">A. {{ faq.answer }}</view>
      </view>
    </view>

    <AssistantDock
      scene="board_detail"
      :page-context="{ page: 'boards/detail', boardId: board.id, boardName: board.name, playerCount: board.playerCount, difficulty: board.difficulty }"
    />
  </view>
</template>

<style scoped lang="scss">
.detail-hero {
  position: relative;
  overflow: hidden;
}

.detail-cover {
  width: 100%;
  height: 420rpx;
  display: block;
}

.detail-mask {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  padding: 28rpx;
  background: linear-gradient(180deg, rgba(0, 0, 0, 0.08) 6%, rgba(0, 0, 0, 0.86) 100%);
}

.detail-cover-top {
  display: flex;
  gap: 12rpx;
}

.detail-title {
  margin-top: 18rpx;
  font-size: 48rpx;
  font-weight: 700;
}

.detail-subtitle {
  margin-top: 10rpx;
  color: #d4d4d4;
  font-size: 24rpx;
}

.role-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18rpx;
  margin-top: 22rpx;
}

.role-cell {
  padding: 18rpx 12rpx;
  border-radius: 16rpx;
  background: rgba(255, 255, 255, 0.04);
  text-align: center;
}

.role-portrait {
  width: 116rpx;
  height: 116rpx;
  border-radius: 999rpx;
}

.role-name {
  margin-top: 14rpx;
  font-size: 24rpx;
}

.role-count {
  margin-top: 8rpx;
  color: #ffc000;
  font-size: 22rpx;
}

.rule-item,
.tip-item,
.faq-item {
  margin-top: 16rpx;
  padding: 18rpx 20rpx;
  border-radius: 16rpx;
  background: rgba(255, 255, 255, 0.04);
  line-height: 1.7;
  font-size: 26rpx;
}

.faq-q {
  color: #ffc000;
  font-size: 24rpx;
  font-weight: 700;
}

.faq-a {
  margin-top: 10rpx;
  color: #d4d4d4;
}
</style>
