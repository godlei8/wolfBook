<script setup>
import { computed } from 'vue'
import { formatDateTime } from '../../utils/format'
import { getDeathReasonLabel, getPhaseLabel, getRecordTypeLabel, getSceneLabel } from '../../utils/session/constants'
import { seatNosToText } from '../../utils/session/normalizer'
import { localizeRecordContent } from '../../utils/session/display'

const props = defineProps({
  record: {
    type: Object,
    default: null,
  },
})

defineEmits(['edit', 'delete'])

function formatSeat(value) {
  const numeric = Number(value) || 0
  return numeric > 0 ? `${numeric}号` : ''
}

function formatNightResult(result) {
  const map = {
    gold: '金水',
    black: '查杀',
    dead: '死亡',
    poisoned: '中毒',
    protected: '被守护',
    saved: '被救起',
  }
  return map[result] || result || ''
}

function buildSummary(record) {
  const payload = record?.payload || {}
  const actorText = seatNosToText(record?.actorSeats || [])
  const targetText = seatNosToText(record?.targetSeats || [])
  const detail = localizeRecordContent(record)
  const deathReason = getDeathReasonLabel(payload.deathReason) || payload.deathReason || ''

  if (record?.type === 'vote') {
    if (payload.subtype === 'tie') {
      return {
        title: '形成平票',
        subtitle: targetText || '待补充对象',
        detail,
      }
    }
    if (payload.subtype === 'abstain') {
      return {
        title: '弃票',
        subtitle: actorText || '待补充玩家',
        detail,
      }
    }
    if (payload.subtype === 'sheriff') {
      return {
        title: `警长 ${formatSeat(payload.sheriffSeat) || actorText || '未指定'}`,
        subtitle: `归票 ${formatSeat(payload.sheriffTargetSeat) || targetText || '待补充'}`,
        detail,
      }
    }
    return {
      title: actorText || '投票玩家',
      subtitle: `投给 ${targetText || '待补充'}`,
      detail,
    }
  }

  if (record?.type === 'seer' || record?.type === 'nightAction') {
    if (payload.subtype === 'seer_check') {
      return {
        title: `${actorText || '夜间'} 查验 ${targetText || '待补充'}`,
        subtitle: formatNightResult(payload.result) || '未填写结果',
        detail,
      }
    }
    if (payload.subtype === 'wolf_kill') {
      return {
        title: '夜间刀口',
        subtitle: targetText || '待补充目标',
        detail,
      }
    }
    if (payload.subtype === 'witch_poison') {
      return {
        title: `${actorText || '女巫'} 毒杀`,
        subtitle: targetText || '待补充目标',
        detail,
      }
    }
    if (payload.subtype === 'guard_protect') {
      return {
        title: `${actorText || '守卫'} 守护`,
        subtitle: targetText || '待补充目标',
        detail,
      }
    }
  }

  if (record?.type === 'speech') {
    const speechTags = Array.isArray(payload.speechTags) ? payload.speechTags.filter(Boolean).join('、') : ''
    return {
      title: `${actorText || '玩家'} 发言`,
      subtitle: speechTags || payload.claimedRole || targetText || '结构化发言记录',
      detail,
    }
  }

  if (record?.type === 'identity') {
    const actionMap = {
      claim_role: '跳身份',
      set_sheriff: '设为警长',
      remove_sheriff: '取消警长',
      mark_dead: '标记出局',
      revive: '恢复存活',
      set_real_role: '确认真实身份',
    }
    return {
      title: `${targetText || actorText || '玩家'} ${actionMap[payload.action] || '身份变更'}`,
      subtitle: payload.roleName || deathReason || '状态更新',
      detail,
    }
  }

  if (record?.type === 'wolfPack' || payload.subtype === 'wolf_pack') {
    return {
      title: '狼坑更新',
      subtitle: targetText || '待补充座位',
      detail,
    }
  }

  if (payload.subtype === 'turning_point') {
    return {
      title: '关键转折',
      subtitle: targetText || '复盘节点',
      detail,
    }
  }

  if (payload.subtype === 'player_note') {
    return {
      title: `${targetText || '玩家'} 备注`,
      subtitle: '玩家视角补充',
      detail,
    }
  }

  return {
    title: getRecordTypeLabel(record?.type),
    subtitle: getSceneLabel(record?.scene),
    detail: detail || '局内记录',
  }
}

const actorText = computed(() => seatNosToText(props.record?.actorSeats || []))
const targetText = computed(() => seatNosToText(props.record?.targetSeats || []))
const summary = computed(() => buildSummary(props.record))
const phaseLabel = computed(() => getPhaseLabel(props.record?.phase))
const sceneLabel = computed(() => getSceneLabel(props.record?.scene))
const timeLabel = computed(() => formatDateTime(props.record?.timestamp || props.record?.updateTime))
</script>

<template>
  <view class="record-card">
    <view class="record-top">
      <view class="record-copy">
        <view class="record-title">{{ summary.title }}</view>
        <view class="record-subtitle">{{ summary.subtitle }}</view>
      </view>
      <view class="record-time">{{ timeLabel }}</view>
    </view>

    <view class="record-pills">
      <view class="pill pill-gold">{{ phaseLabel }}</view>
      <view class="pill pill-white">{{ sceneLabel }}</view>
      <view v-for="tag in record?.tags?.slice(0, 3) || []" :key="tag" class="pill pill-white">{{ tag }}</view>
    </view>

    <view class="record-lines">
      <view v-if="actorText" class="record-line">
        <view class="record-line-label">发起</view>
        <view class="record-line-value">{{ actorText }}</view>
      </view>
      <view v-if="targetText" class="record-line">
        <view class="record-line-label">目标</view>
        <view class="record-line-value">{{ targetText }}</view>
      </view>
    </view>

    <view class="record-detail">{{ summary.detail }}</view>

    <view class="record-actions">
      <view v-if="record?.editable !== false" class="record-action" @tap.stop="$emit('edit', record)">编辑</view>
      <view class="record-action record-action--danger" @tap.stop="$emit('delete', record)">删除</view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.record-card {
  padding: 22rpx 20rpx;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.04);
  border: 1rpx solid rgba(255, 255, 255, 0.04);
}

.record-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16rpx;
}

.record-copy {
  min-width: 0;
  flex: 1;
}

.record-title {
  font-size: 28rpx;
  font-weight: 700;
  line-height: 1.35;
}

.record-subtitle {
  margin-top: 8rpx;
  color: #b0b0b0;
  font-size: 22rpx;
  line-height: 1.6;
}

.record-time {
  color: #8f8f8f;
  font-size: 22rpx;
  white-space: nowrap;
}

.record-pills {
  margin-top: 16rpx;
  display: flex;
  flex-wrap: wrap;
  gap: 10rpx;
}

.record-lines {
  margin-top: 14rpx;
  display: grid;
  gap: 10rpx;
}

.record-line {
  display: flex;
  gap: 12rpx;
  font-size: 24rpx;
  line-height: 1.5;
}

.record-line-label {
  width: 64rpx;
  color: #8f8f8f;
  flex-shrink: 0;
}

.record-line-value {
  color: #f2f2f2;
}

.record-detail {
  margin-top: 16rpx;
  color: #d5d5d5;
  font-size: 24rpx;
  line-height: 1.7;
}

.record-actions {
  margin-top: 18rpx;
  display: flex;
  justify-content: flex-end;
  gap: 12rpx;
}

.record-action {
  padding: 10rpx 18rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.06);
  color: #f4f4f4;
  font-size: 22rpx;
}

.record-action--danger {
  color: #ff9e8d;
}
</style>
