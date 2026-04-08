<script setup>
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import storage from '../../services/storage'
import { formatDateTime } from '../../utils/format'

const sessions = ref([])

const recordTypeMap = {
  seer: '查验记录',
  vote: '投票记录',
  speech: '发言记录',
  wolfPack: '狼坑分析',
}

function refreshSessions() {
  sessions.value = storage
    .getSessions()
    .slice()
    .sort((left, right) => new Date(right.updateTime).getTime() - new Date(left.updateTime).getTime())
    .map((session) => {
      const records = session.records || []
      const latestRecord = records.length ? records[records.length - 1] : null
      return {
        ...session,
        createLabel: formatDateTime(session.createTime),
        updateLabel: formatDateTime(session.updateTime),
        latestTypeLabel: latestRecord ? recordTypeMap[latestRecord.type] || '最新记录' : '准备开始',
        latestPreview: latestRecord ? latestRecord.content : '还没有记录，点击进入后就可以补充发言、投票和夜间信息。',
      }
    })
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
    success: (res) => {
      if (res.confirm) {
        storage.deleteSession(id)
        refreshSessions()
      }
    },
  })
}

onShow(refreshSessions)
</script>

<template>
  <view class="page-shell">
    <view class="hero-title">笔记</view>
    <view class="hero-subtitle">把发言、票型和夜间信息收进一条时间线，局后复盘会更清楚。</view>

    <view v-if="!sessions.length" class="empty-state glass-card section-card">还没有对局笔记，点右下角加号先创建一局。</view>

    <view v-for="item in sessions" :key="item.sessionId" class="glass-card section-card session-card" @tap="openSession(item.sessionId)">
      <view class="session-top">
        <view class="pill pill-gold">{{ item.playerCount }} 人局</view>
        <view class="pill pill-white">{{ (item.records || []).length }} 条</view>
      </view>
      <view class="session-name">{{ item.boardName || '未命名对局' }}</view>
      <view class="session-meta">最近更新 {{ item.updateLabel }}</view>
      <view class="session-preview">
        <view class="session-preview-label">{{ item.latestTypeLabel }}</view>
        <view class="session-preview-text">{{ item.latestPreview }}</view>
      </view>
      <view class="session-footer">
        <view class="section-meta">创建于 {{ item.createLabel }}</view>
        <button class="session-remove" @tap.stop="removeSession(item.sessionId)">删除</button>
      </view>
    </view>

    <view class="floating-plus" @tap="createSession">+</view>
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
  border-radius: 12rpx;
  background: rgba(255, 255, 255, 0.06);
  color: #ffffff;
  font-size: 24rpx;
}

.session-remove::after {
  border: none;
}
</style>
