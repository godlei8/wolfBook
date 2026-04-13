<script setup>
import { computed } from 'vue'
import { formatDateTime } from '../../utils/format'
import { getPhaseLabel, getRecordTypeLabel, getStatusLabel } from '../../utils/session/constants'
import { localizeRecordContent } from '../../utils/session/display'

const props = defineProps({
  session: {
    type: Object,
    default: null,
  },
  recentRecords: {
    type: Array,
    default: () => [],
  },
  keyPlayers: {
    type: Array,
    default: () => [],
  },
  summary: {
    type: Object,
    default: null,
  },
})

defineEmits(['jump-seat', 'open-record'])

const stageLabel = computed(() => getPhaseLabel(props.session?.currentPhase))
const statusLabel = computed(() => getStatusLabel(props.session?.status))

function formatRecordPreview(record) {
  return localizeRecordContent(record) || '暂无补充内容'
}
</script>

<template>
  <view class="overview-shell">
    <view class="glass-card section-card">
      <view class="section-title">局势总览</view>
      <view class="overview-grid">
        <view class="overview-card">
          <view class="overview-label">当前进度</view>
          <view class="overview-value">第 {{ session?.currentDay || 1 }} 天</view>
          <view class="overview-desc">{{ stageLabel }}</view>
        </view>
        <view class="overview-card">
          <view class="overview-label">对局状态</view>
          <view class="overview-value">{{ statusLabel }}</view>
          <view class="overview-desc">更新时间 {{ formatDateTime(session?.updateTime) }}</view>
        </view>
        <view class="overview-card">
          <view class="overview-label">关键事件</view>
          <view class="overview-value">{{ summary?.keyEventCount ?? 0 }}</view>
          <view class="overview-desc">已打标签的记录数</view>
        </view>
        <view class="overview-card">
          <view class="overview-label">最新狼坑</view>
          <view class="overview-value">{{ summary?.latestWolfPackText || '暂无' }}</view>
          <view class="overview-desc">可在备注或座位卡继续补充</view>
        </view>
      </view>
    </view>

    <view class="glass-card section-card">
      <view class="section-title">最近关键记录</view>
      <view class="section-desc">优先展示最新 3 条记录，方便局中快速回看。</view>
      <view v-if="!recentRecords.length" class="empty-inner">还没有最近记录。</view>
      <view v-for="record in recentRecords" :key="record.id" class="recent-item" @tap="$emit('open-record', record)">
        <view class="recent-head">
          <view class="pill pill-gold">{{ getRecordTypeLabel(record.type) }}</view>
          <view class="section-meta">{{ formatDateTime(record.timestamp) }}</view>
        </view>
        <view class="recent-content">{{ formatRecordPreview(record) }}</view>
      </view>
    </view>

    <view class="glass-card section-card">
      <view class="section-title">重点玩家</view>
      <view class="section-desc">优先关注警长、可疑度较高或当前备注较多的玩家。</view>
      <view v-if="!keyPlayers.length" class="empty-inner">暂时还没有重点玩家。</view>
      <view class="focus-grid">
        <view v-for="player in keyPlayers" :key="player.seatNo" class="focus-card" @tap="$emit('jump-seat', player.seatNo)">
          <view class="focus-top">
            <view class="focus-seat">{{ player.seatNo }}号</view>
            <view v-if="player.isSheriff" class="pill pill-gold">警长</view>
          </view>
          <view class="focus-role">{{ player.claimedRole || '未跳身份' }}</view>
          <view class="focus-note">{{ player.note || '点击查看座位详情与快捷操作。' }}</view>
          <view class="focus-dots">
            <view
              v-for="index in 3"
              :key="index"
              class="focus-dot"
              :class="{ active: index <= (player.suspicionLevel || 0) }"
            />
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.overview-shell {
  display: grid;
  gap: 24rpx;
}

.overview-grid {
  margin-top: 18rpx;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14rpx;
}

.overview-card,
.recent-item,
.focus-card,
.empty-inner {
  padding: 20rpx;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.04);
}

.overview-label {
  color: #8f8f8f;
  font-size: 22rpx;
}

.overview-value {
  margin-top: 10rpx;
  font-size: 30rpx;
  font-weight: 700;
  line-height: 1.3;
}

.overview-desc {
  margin-top: 10rpx;
  color: #b0b0b0;
  font-size: 22rpx;
  line-height: 1.6;
}

.empty-inner {
  margin-top: 18rpx;
  color: #969696;
  font-size: 24rpx;
  line-height: 1.7;
}

.recent-item {
  margin-top: 18rpx;
}

.recent-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12rpx;
}

.recent-content {
  margin-top: 14rpx;
  font-size: 24rpx;
  line-height: 1.7;
  color: #d7d7d7;
}

.focus-grid {
  margin-top: 18rpx;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14rpx;
}

.focus-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12rpx;
}

.focus-seat {
  font-size: 28rpx;
  font-weight: 700;
}

.focus-role {
  margin-top: 12rpx;
  color: #ffffff;
  font-size: 26rpx;
}

.focus-note {
  margin-top: 12rpx;
  color: #b0b0b0;
  font-size: 22rpx;
  line-height: 1.6;
  min-height: 70rpx;
}

.focus-dots {
  margin-top: 12rpx;
  display: flex;
  gap: 8rpx;
}

.focus-dot {
  width: 12rpx;
  height: 12rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.14);
}

.focus-dot.active {
  background: #ffc000;
}
</style>
