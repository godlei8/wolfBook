<script setup>
import { reactive, watch } from 'vue'
import { RESULT_CAMP_OPTIONS, SESSION_PHASES, getPhaseLabel } from '../../utils/session/constants'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false,
  },
  session: {
    type: Object,
    default: null,
  },
})

const emit = defineEmits(['close', 'save'])

const form = reactive({
  boardName: '',
  playerCount: 12,
  currentDay: 1,
  currentPhase: 'day_speech',
  status: 'active',
  resultCamp: '',
})

const statusOptions = [
  { value: 'active', label: '进行中' },
  { value: 'finished', label: '已结束' },
  { value: 'archived', label: '已归档' },
]

function assignForm() {
  form.boardName = props.session?.boardName || ''
  form.playerCount = Number(props.session?.playerCount) || 12
  form.currentDay = Number(props.session?.currentDay) || 1
  form.currentPhase = props.session?.currentPhase || 'day_speech'
  form.status = props.session?.status || 'active'
  form.resultCamp = props.session?.resultCamp || ''
}

function parsePositive(value, fallback) {
  const numeric = Number(String(value || '').replace(/[^\d]/g, ''))
  return numeric > 0 ? numeric : fallback
}

watch(
  () => [props.visible, props.session?.sessionId, props.session?.updateTime],
  ([visible]) => {
    if (visible) {
      assignForm()
    }
  },
  { immediate: true },
)

function submit() {
  if (!form.boardName.trim()) {
    uni.showToast({ title: '请输入板子名称', icon: 'none' })
    return
  }
  form.playerCount = parsePositive(form.playerCount, 12)
  form.currentDay = parsePositive(form.currentDay, 1)
  if (!SESSION_PHASES.includes(form.currentPhase)) {
    form.currentPhase = 'day_speech'
  }
  emit('save', {
    boardName: form.boardName.trim(),
    playerCount: form.playerCount,
    currentDay: form.currentDay,
    currentPhase: form.currentPhase,
    status: form.status,
    resultCamp: form.resultCamp,
  })
}
</script>

<template>
  <view v-if="visible" class="sheet-mask" @tap="$emit('close')">
    <view class="sheet-panel glass-card" @tap.stop>
      <view class="sheet-head">
        <view>
          <view class="section-title">编辑基础信息</view>
          <view class="section-desc">可调整当前天数、阶段、状态与结算结果。</view>
        </view>
        <view class="sheet-close" @tap="$emit('close')">关闭</view>
      </view>

      <view class="sheet-block">
        <view class="section-meta">板子名称</view>
        <input v-model="form.boardName" class="field-input" maxlength="20" placeholder="输入板子名称" />
      </view>

      <view class="double-grid">
        <view class="sheet-block">
          <view class="section-meta">玩家人数</view>
          <input
            :value="String(form.playerCount)"
            class="field-input"
            type="number"
            maxlength="2"
            placeholder="人数"
            @input="form.playerCount = parsePositive($event.detail?.value, form.playerCount)"
          />
        </view>
        <view class="sheet-block">
          <view class="section-meta">当前天数</view>
          <input
            :value="String(form.currentDay)"
            class="field-input"
            type="number"
            maxlength="2"
            placeholder="第几天"
            @input="form.currentDay = parsePositive($event.detail?.value, form.currentDay)"
          />
        </view>
      </view>

      <view class="sheet-block">
        <view class="section-meta">当前阶段</view>
        <view class="chip-grid">
          <view
            v-for="phase in SESSION_PHASES"
            :key="phase"
            class="chip"
            :class="{ active: form.currentPhase === phase }"
            @tap="form.currentPhase = phase"
          >
            {{ getPhaseLabel(phase) }}
          </view>
        </view>
      </view>

      <view class="sheet-block">
        <view class="section-meta">对局状态</view>
        <view class="chip-grid">
          <view
            v-for="item in statusOptions"
            :key="item.value"
            class="chip"
            :class="{ active: form.status === item.value }"
            @tap="form.status = item.value"
          >
            {{ item.label }}
          </view>
        </view>
      </view>

      <view class="sheet-block">
        <view class="section-meta">胜负结果</view>
        <view class="chip-grid">
          <view
            v-for="item in RESULT_CAMP_OPTIONS"
            :key="item.value || 'empty'"
            class="chip"
            :class="{ active: form.resultCamp === item.value }"
            @tap="form.resultCamp = item.value"
          >
            {{ item.label }}
          </view>
        </view>
      </view>

      <button class="button-primary submit-btn" @tap="submit">保存基础信息</button>
    </view>
  </view>
</template>

<style scoped lang="scss">
.sheet-mask {
  position: fixed;
  inset: 0;
  z-index: 32;
  background: rgba(0, 0, 0, 0.62);
  display: flex;
  align-items: flex-end;
}

.sheet-panel {
  width: 100%;
  max-height: 82vh;
  padding: 28rpx 24rpx calc(env(safe-area-inset-bottom) + 26rpx);
  border-radius: 28rpx 28rpx 0 0;
  overflow-y: auto;
}

.sheet-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 18rpx;
}

.sheet-close {
  padding: 10rpx 18rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.06);
  color: #d8d8d8;
  font-size: 22rpx;
}

.sheet-block {
  margin-top: 20rpx;
}

.double-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14rpx;
}

.chip-grid {
  margin-top: 12rpx;
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}

.submit-btn {
  margin-top: 24rpx;
}
</style>
