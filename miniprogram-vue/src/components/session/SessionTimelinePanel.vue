<script setup>
import { ref, watch } from 'vue'
import { getPhaseLabel } from '../../utils/session/constants'
import TimelineRecordCard from './TimelineRecordCard.vue'

const props = defineProps({
  groups: {
    type: Array,
    default: () => [],
  },
})

defineEmits(['edit-record', 'delete-record'])

const expandedMap = ref({})

watch(
  () => props.groups,
  (groups) => {
    const next = { ...expandedMap.value }
    groups.forEach((group, index) => {
      if (next[group.day] === undefined) {
        next[group.day] = index === 0
      }
    })
    expandedMap.value = next
  },
  { immediate: true, deep: true },
)

function toggleDay(day) {
  expandedMap.value = {
    ...expandedMap.value,
    [day]: !expandedMap.value[day],
  }
}
</script>

<template>
  <view class="glass-card section-card">
    <view class="section-title">阶段时间线</view>
    <view class="section-desc">按“第几天 + 阶段”折叠查看记录，支持直接编辑或删除。</view>

    <view v-if="!groups.length" class="empty-inner">还没有结构化记录，先从底部入口条补第一条。</view>

    <view v-for="group in groups" :key="group.day" class="day-group">
      <view class="day-head" @tap="toggleDay(group.day)">
        <view>
          <view class="day-title">{{ group.label }}</view>
          <view class="day-meta">{{ group.recordCount }} 条记录</view>
        </view>
        <view class="day-toggle">{{ expandedMap[group.day] ? '收起' : '展开' }}</view>
      </view>

      <view v-if="expandedMap[group.day]" class="phase-list">
        <view v-for="phaseGroup in group.phases" :key="`${group.day}_${phaseGroup.phase}`" class="phase-group">
          <view class="phase-title">{{ getPhaseLabel(phaseGroup.phase) }}</view>
          <view class="phase-records">
            <TimelineRecordCard
              v-for="record in phaseGroup.records"
              :key="record.id"
              :record="record"
              @edit="$emit('edit-record', $event)"
              @delete="$emit('delete-record', $event)"
            />
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.empty-inner {
  margin-top: 18rpx;
  padding: 28rpx 20rpx;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.04);
  color: #979797;
  font-size: 24rpx;
  line-height: 1.7;
}

.day-group {
  margin-top: 20rpx;
  border-radius: 20rpx;
  background: rgba(255, 255, 255, 0.03);
  overflow: hidden;
}

.day-head {
  padding: 22rpx 20rpx;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16rpx;
}

.day-title {
  font-size: 28rpx;
  font-weight: 700;
}

.day-meta,
.day-toggle {
  margin-top: 8rpx;
  color: #9d9d9d;
  font-size: 22rpx;
}

.day-toggle {
  margin-top: 0;
  flex-shrink: 0;
}

.phase-list {
  padding: 0 20rpx 20rpx;
  display: grid;
  gap: 18rpx;
}

.phase-group {
  padding-top: 8rpx;
  border-top: 1rpx solid rgba(255, 255, 255, 0.05);
}

.phase-group:first-child {
  border-top: none;
  padding-top: 0;
}

.phase-title {
  color: #ffc000;
  font-size: 24rpx;
  font-weight: 700;
}

.phase-records {
  margin-top: 12rpx;
  display: grid;
  gap: 12rpx;
}
</style>
