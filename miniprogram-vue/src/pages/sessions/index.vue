<script setup>
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import AssistantDock from '../../components/assistant/AssistantDock.vue'
import userData from '../../services/user-data'
import { formatDateTime } from '../../utils/format'
import { getPhaseLabel, getRecordTypeLabel, getStatusLabel } from '../../utils/session/constants'
import { localizeRecordContent } from '../../utils/session/display'

const sessions = ref([])

function buildSessionView(session) {
  const records = session.records || []
  const latestRecord = records.length ? records[records.length - 1] : null
  const latestPreview = session.summary?.latestRecordPreview
    ? localizeRecordContent({
        type: session.summary?.latestRecordType,
        content: session.summary.latestRecordPreview,
        payload: latestRecord?.payload || {},
        actorSeats: latestRecord?.actorSeats || [],
        targetSeats: latestRecord?.targetSeats || [],
      })
    : localizeRecordContent(latestRecord)

  return {
    ...session,
    createLabel: formatDateTime(session.createTime),
    updateLabel: formatDateTime(session.updateTime),
    latestTypeLabel: session.summary?.latestRecordType
      ? getRecordTypeLabel(session.summary.latestRecordType)
      : latestRecord
        ? getRecordTypeLabel(latestRecord.type)
        : '准备开始',
    latestPreview: latestPreview || '还没有记录，点击进入后就可以补充发言、投票和夜间信息。',
    statusLabel: getStatusLabel(session.status),
    phaseLabel: getPhaseLabel(session.currentPhase),
    aliveCount: session.summary?.aliveCount ?? session.playerCount,
    deadCount: session.summary?.deadCount ?? 0,
  }
}

async function refreshSessions() {
  try {
    const list = await userData.loadSessions()
    sessions.value = (list || []).map(buildSessionView)
  } catch (error) {
    uni.showToast({ title: error?.message || '笔记加载失败', icon: 'none' })
  }
}

function createSession() {
  uni.navigateTo({ url: '/pages/sessions/edit' })
}

function openSession(id) {
  uni.navigateTo({ url: `/pages/sessions/detail?id=${id}` })
}

function removeSession(id) {
  uni.showModal({
    title: '删除对局',
    content: '确认删除这条笔记吗？删除后不能恢复。',
    success: async (res) => {
      if (!res.confirm) return
      try {
        await userData.deleteSession(id)
        await refreshSessions()
        uni.showToast({ title: '已删除', icon: 'success' })
      } catch (error) {
        uni.showToast({ title: error?.message || '删除失败', icon: 'none' })
      }
    },
  })
}

onShow(() => {
  refreshSessions()
})
</script>

<template>
  <view class="page-shell">
    <view class="hero-title">我的笔记</view>
    <view class="hero-subtitle">把发言、票型和夜间信息收进一条时间线，局后复盘会更清楚。</view>

    <view v-if="!sessions.length" class="empty-state glass-card section-card">还没有对局笔记，点右下角加号先创建一局。</view>

    <view v-for="item in sessions" :key="item.sessionId" class="glass-card section-card session-card" @tap="openSession(item.sessionId)">
      <view class="session-top">
        <view class="pill pill-gold">{{ item.statusLabel }}</view>
        <view class="pill pill-white">{{ item.currentDay }} 天 · {{ item.phaseLabel }}</view>
      </view>
      <view class="session-name">{{ item.boardName || '未命名对局' }}</view>
      <view class="session-meta">最近更新 {{ item.updateLabel }}</view>
      <view class="session-chips">
        <view class="pill pill-white">{{ item.playerCount }} 人局</view>
        <view class="pill pill-white">存活 {{ item.aliveCount }}</view>
        <view class="pill pill-white">出局 {{ item.deadCount }}</view>
      </view>
      <view class="session-preview">
        <view class="session-preview-label">{{ item.latestTypeLabel }}</view>
        <view class="session-preview-text">{{ item.latestPreview }}</view>
      </view>
      <view class="session-footer">
        <view class="section-meta">创建于 {{ item.createLabel }}</view>
        <button class="button-danger session-remove" @tap.stop="removeSession(item.sessionId)">删除</button>
      </view>
    </view>

    <view class="floating-plus" @tap="createSession">+</view>
    <AssistantDock scene="sessions_index" :page-context="{ page: 'sessions/index', sessionCount: sessions.length }" />
  </view>
</template>

<style scoped lang="scss">
.session-card {
  margin-top: 24rpx;
}

.session-top,
.session-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.session-name {
  margin-top: 18rpx;
  font-size: 38rpx;
  font-weight: 700;
}

.session-meta {
  margin-top: 10rpx;
  color: #b3b3b3;
  font-size: 24rpx;
}

.session-chips {
  margin-top: 16rpx;
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}

.session-preview {
  margin-top: 18rpx;
  padding: 18rpx 20rpx;
  border-radius: 16rpx;
  background: rgba(255, 255, 255, 0.04);
}

.session-preview-label {
  color: #ffc000;
  font-size: 22rpx;
  font-weight: 700;
}

.session-preview-text {
  margin-top: 10rpx;
  font-size: 26rpx;
  line-height: 1.7;
}

.session-footer {
  margin-top: 20rpx;
}

.session-remove {
  margin: 0;
  height: 64rpx;
  line-height: 64rpx;
  padding: 0 24rpx;
  border-radius: 14rpx;
  font-size: 24rpx;
}
</style>
