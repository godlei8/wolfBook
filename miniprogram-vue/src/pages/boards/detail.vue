<script setup>
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import AssistantDock from '../../components/assistant/AssistantDock.vue'
import api from '../../services/api'
import { withAlpha } from '../../utils/board-rules.js'

const board = ref(null)
let boardId = 0

const rules = computed(() => board.value?.ruleDetail || { hasContent: false, nightOrder: [], roleRules: [], sections: [] })
const heroImage = computed(() => board.value?.backgroundImage || board.value?.coverImage || '')
const overviewText = computed(() => {
  const value = board.value
  if (!value) return ''
  return rules.value.overview || value.cardDescription || value.summary || value.lineupSummary || value.campSummary || value.briefConfig
})

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
      <image class="detail-cover" :src="heroImage" mode="aspectFill" />
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
      <view class="section-title">{{ rules.overview ? '玩法概述' : '板型概览' }}</view>
      <view class="section-desc">{{ overviewText }}</view>
    </view>

    <view class="glass-card section-card">
      <view class="section-title">角色配置</view>
      <view class="role-grid">
        <view
          v-for="item in board.roles"
          :key="item.roleId"
          class="role-cell"
          :style="{ background: withAlpha(item.accent, 0.14), borderColor: withAlpha(item.accent, 0.45) }"
          @tap="openRole(item.roleId)"
        >
          <image class="role-portrait" :src="item.portrait" mode="aspectFill" :style="{ borderColor: item.accent }" />
          <view class="role-name">{{ item.name }}</view>
          <view class="role-count" :style="{ color: item.accent }">x{{ item.count }}</view>
        </view>
      </view>
    </view>

    <view v-if="rules.nightOrder.length" class="glass-card section-card">
      <view class="section-title">夜间行动顺序</view>
      <view class="section-meta">法官每晚按这个顺序叫醒角色</view>
      <view
        v-for="step in rules.nightOrder"
        :key="`${step.order}-${step.roleId}`"
        class="night-step"
        :style="{ borderLeftColor: step.accent }"
        @tap="openRole(step.roleId)"
      >
        <view class="night-order" :style="{ background: step.accent }">{{ step.order }}</view>
        <image v-if="step.portrait" class="night-portrait" :src="step.portrait" mode="aspectFill" />
        <view class="night-content">
          <view class="night-role" :style="{ color: step.accent }">{{ step.roleName }}</view>
          <view class="night-action">{{ step.action }}</view>
        </view>
      </view>
    </view>

    <view v-if="rules.roleRules.length" class="glass-card section-card">
      <view class="section-title">角色细则</view>
      <view
        v-for="note in rules.roleRules"
        :key="note.roleId"
        class="note-block"
        :style="{ background: withAlpha(note.accent, 0.1), borderColor: withAlpha(note.accent, 0.4) }"
      >
        <view class="note-header" @tap="openRole(note.roleId)">
          <image v-if="note.portrait" class="note-portrait" :src="note.portrait" mode="aspectFill" />
          <view class="note-role" :style="{ color: note.accent }">{{ note.roleName }}</view>
        </view>
        <view v-for="item in note.items" :key="item" class="bullet-item">{{ item }}</view>
      </view>
    </view>

    <view v-for="section in rules.sections" :key="section.title" class="glass-card section-card">
      <view class="section-title">{{ section.title }}</view>
      <view v-for="item in section.items" :key="item" class="rule-item">{{ item }}</view>
    </view>

    <view v-if="board.specialRules && board.specialRules.length" class="glass-card section-card">
      <view class="section-title">{{ rules.hasContent ? '补充规则' : '规则详情' }}</view>
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
  height: 432rpx;
  display: block;
}

.detail-mask {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  padding: 30rpx;
  background: linear-gradient(180deg, rgba(0, 0, 0, 0.05) 4%, rgba(0, 0, 0, 0.88) 100%);
}

.detail-cover-top {
  display: flex;
  gap: 12rpx;
}

.detail-title {
  margin-top: 16rpx;
  font-size: 50rpx;
  font-weight: 700;
  line-height: 1.12;
}

.detail-subtitle {
  margin-top: 12rpx;
  color: #d8d8d8;
  font-size: 24rpx;
  line-height: 1.7;
}

.role-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16rpx;
  margin-top: 20rpx;
}

.role-cell {
  padding: 22rpx 14rpx 18rpx;
  border: 2rpx solid rgba(255, 255, 255, 0.06);
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.04);
  text-align: center;
}

.role-portrait {
  width: 116rpx;
  height: 116rpx;
  border: 4rpx solid transparent;
  border-radius: 999rpx;
  box-sizing: border-box;
}

.night-step {
  display: flex;
  align-items: center;
  gap: 18rpx;
  margin-top: 16rpx;
  padding: 18rpx 20rpx;
  border-left: 8rpx solid #d4a73a;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.04);
}

.night-order {
  flex: 0 0 auto;
  width: 44rpx;
  height: 44rpx;
  border-radius: 999rpx;
  color: #111111;
  font-size: 24rpx;
  font-weight: 700;
  line-height: 44rpx;
  text-align: center;
}

.night-portrait {
  flex: 0 0 auto;
  width: 72rpx;
  height: 72rpx;
  border-radius: 999rpx;
}

.night-content {
  flex: 1;
  min-width: 0;
}

.night-role {
  font-size: 26rpx;
  font-weight: 700;
}

.night-action {
  margin-top: 6rpx;
  color: #d4d4d4;
  font-size: 25rpx;
  line-height: 1.7;
}

.note-block {
  margin-top: 16rpx;
  padding: 18rpx 20rpx;
  border: 2rpx solid rgba(255, 255, 255, 0.06);
  border-radius: 18rpx;
}

.note-header {
  display: flex;
  align-items: center;
  gap: 14rpx;
  margin-bottom: 6rpx;
}

.note-portrait {
  width: 56rpx;
  height: 56rpx;
  border-radius: 999rpx;
}

.note-role {
  font-size: 27rpx;
  font-weight: 700;
}

.bullet-item {
  position: relative;
  margin-top: 10rpx;
  padding-left: 26rpx;
  color: #e2e2e2;
  font-size: 26rpx;
  line-height: 1.72;
}

.bullet-item::before {
  content: '';
  position: absolute;
  left: 4rpx;
  top: 18rpx;
  width: 10rpx;
  height: 10rpx;
  border-radius: 999rpx;
  background: currentColor;
  opacity: 0.6;
}

.role-name {
  margin-top: 12rpx;
  font-size: 24rpx;
  line-height: 1.45;
}

.role-count {
  margin-top: 8rpx;
  color: #ffc000;
  font-size: 22rpx;
}

.rule-item,
.tip-item,
.faq-item {
  margin-top: 14rpx;
  padding: 18rpx 20rpx;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.04);
  line-height: 1.76;
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
  line-height: 1.75;
}
</style>
