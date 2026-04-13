<script setup>
import { computed } from 'vue'
import { localizeDeathReason, localizeRecordContent } from '../../utils/session/display'
import { getPhaseLabel } from '../../utils/session/constants'

const props = defineProps({
  session: {
    type: Object,
    default: null,
  },
})

defineEmits(['edit-base', 'add-turning-point'])

const resultCampLabelMap = {
  '': '未填写',
  good: '好人胜',
  wolf: '狼人胜',
  third: '第三方胜',
}

const deadPlayers = computed(() => (props.session?.players || []).filter((item) => item.alive === false))
const turningPoints = computed(() =>
  (props.session?.records || [])
    .filter((record) => {
      const subtype = record?.payload?.subtype
      return subtype === 'turning_point' || (record?.tags || []).includes('关键转折')
    })
    .slice()
    .reverse()
    .slice(0, 4),
)
</script>

<template>
  <view class="review-shell">
    <view class="glass-card section-card">
      <view class="section-title">复盘结论</view>
      <view class="review-grid">
        <view class="review-card">
          <view class="review-label">对局状态</view>
          <view class="review-value">{{ session?.status === 'finished' ? '已结束' : '进行中' }}</view>
        </view>
        <view class="review-card">
          <view class="review-label">胜负结果</view>
          <view class="review-value">{{ resultCampLabelMap[session?.resultCamp || ''] }}</view>
        </view>
        <view class="review-card">
          <view class="review-label">出局人数</view>
          <view class="review-value">{{ deadPlayers.length }}</view>
        </view>
        <view class="review-card">
          <view class="review-label">最新狼坑</view>
          <view class="review-value">{{ session?.summary?.latestWolfPackText || '暂无' }}</view>
        </view>
      </view>
      <view class="review-actions">
        <view class="review-action" @tap="$emit('edit-base')">补充结算</view>
        <view class="review-action review-action--gold" @tap="$emit('add-turning-point')">记录转折</view>
      </view>
    </view>

    <view class="glass-card section-card">
      <view class="section-title">关键转折</view>
      <view class="section-desc">第一版先用结构化备注承接，后续可以继续接 AI 总结。</view>
      <view v-if="!turningPoints.length" class="empty-inner">还没有关键转折记录，可以从这里补一条。</view>
      <view v-for="record in turningPoints" :key="record.id" class="turning-card">
        <view class="turning-title">{{ localizeRecordContent(record) }}</view>
        <view class="turning-meta">第 {{ record.day }} 天 · {{ getPhaseLabel(record.phase) }}</view>
      </view>
    </view>

    <view class="glass-card section-card">
      <view class="section-title">出局名单</view>
      <view class="section-desc">用于快速回看本局的死亡顺序与处理结果。</view>
      <view v-if="!deadPlayers.length" class="empty-inner">目前还没有出局玩家。</view>
      <view class="dead-grid">
        <view v-for="player in deadPlayers" :key="player.seatNo" class="dead-card">
          <view class="dead-seat">{{ player.seatNo }}号</view>
          <view class="dead-reason">{{ localizeDeathReason(player.deathReason) || '未标记原因' }}</view>
          <view class="dead-meta">第 {{ player.deathDay || '-' }} 天 · {{ getPhaseLabel(player.deathPhase) }}</view>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.review-shell {
  display: grid;
  gap: 24rpx;
}

.review-grid,
.dead-grid {
  margin-top: 18rpx;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14rpx;
}

.review-card,
.turning-card,
.dead-card,
.empty-inner {
  padding: 20rpx;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.04);
}

.review-label {
  color: #8f8f8f;
  font-size: 22rpx;
}

.review-value {
  margin-top: 10rpx;
  font-size: 30rpx;
  font-weight: 700;
  line-height: 1.3;
}

.review-actions {
  margin-top: 20rpx;
  display: flex;
  gap: 12rpx;
}

.review-action {
  flex: 1;
  height: 76rpx;
  border-radius: 16rpx;
  background: rgba(255, 255, 255, 0.06);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #f3f3f3;
  font-size: 24rpx;
  font-weight: 700;
}

.review-action--gold {
  background: rgba(255, 192, 0, 0.14);
  color: #ffc000;
}

.turning-card {
  margin-top: 18rpx;
}

.turning-title {
  font-size: 26rpx;
  line-height: 1.7;
}

.turning-meta,
.dead-meta,
.dead-reason,
.empty-inner {
  margin-top: 10rpx;
  color: #9f9f9f;
  font-size: 22rpx;
  line-height: 1.6;
}

.dead-seat {
  font-size: 28rpx;
  font-weight: 700;
}

.empty-inner {
  margin-top: 18rpx;
}
</style>
