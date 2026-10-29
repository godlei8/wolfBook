<script setup>
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import NavBar from '../../components/NavBar.vue'
import userData from '../../services/user-data'
import { formatDateTime } from '../../utils/format'
import { syncTabBar } from '../../utils/tabbar'

const sessions = ref([])

const recordTypeMap = {
  seer: '查验记录',
  vote: '投票记录',
  speech: '发言记录',
  wolfPack: '狼坑分析',
}

function buildSessionView(session) {
  const records = session.records || []
  const latestRecord = records.length ? records[records.length - 1] : null
  return {
    ...session,
    createLabel: formatDateTime(session.createTime),
    updateLabel: formatDateTime(session.updateTime),
    latestTypeLabel: latestRecord ? recordTypeMap[latestRecord.type] || '最新记录' : '准备开始',
    latestPreview: latestRecord ? latestRecord.content : '还没有记录，点击进入后就可以补充发言、投票和夜间信息。',
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
  syncTabBar()
  refreshSessions()
})
</script>

<template>
  <view class="nt-page nt-page--tab">
    <NavBar wordmark="NOTES" />

    <view class="nt-gutter sx-head">
      <text class="nt-title">对局笔记</text>
      <view class="nt-btn nt-btn--gold sx-new" @tap="createSession">✎ 记一局</view>
    </view>

    <view class="nt-gutter nt-stack sx-body">
      <text class="nt-meta">把发言、票型和夜间信息收进一条时间线，局后复盘更清楚。</text>

      <view v-if="!sessions.length" class="nt-empty">还没有对局笔记。点右上方的「记一局」开始。</view>

      <view v-for="item in sessions" :key="item.sessionId" class="nt-ink sx-card" @tap="openSession(item.sessionId)">
        <view class="sx-card__top">
          <view class="sx-card__tags">
            <text class="nt-tag">{{ item.playerCount }} 人局</text>
            <text class="nt-tag">{{ (item.records || []).length }} 条记录</text>
          </view>
          <text class="sx-card__remove" @tap.stop="removeSession(item.sessionId)">删除</text>
        </view>
        <text class="sx-card__name serif">{{ item.boardName || '未命名对局' }}</text>
        <view class="sx-card__preview">
          <text class="sx-card__label">{{ item.latestTypeLabel }}</text>
          <text class="sx-card__text">{{ item.latestPreview }}</text>
        </view>
        <text class="nt-meta">最近更新 {{ item.updateLabel }}</text>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.sx-head {
  padding-top: 8rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24rpx;
}

.sx-body {
  margin-top: 12rpx;
}

.sx-card {
  padding: 32rpx;
  display: flex;
  flex-direction: column;
  gap: 18rpx;
}

.sx-card__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.sx-card__tags {
  display: flex;
  gap: 12rpx;
}

.sx-card__remove {
  color: var(--text-3);
  font-size: 26rpx;
  padding: 8rpx 0 8rpx 20rpx;
}

.sx-card__name {
  font-size: 40rpx;
  font-weight: 900;
}

.sx-card__preview {
  padding: 22rpx 24rpx;
  border-radius: 24rpx;
  background: var(--panel-raised);
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.sx-card__label {
  color: var(--gold);
  font-size: 24rpx;
  font-weight: 700;
}

.sx-card__text {
  color: var(--text-soft);
  font-size: 28rpx;
  line-height: 1.7;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  overflow: hidden;
}

.sx-new {
  height: 76rpx;
  padding: 0 28rpx;
  border-radius: 24rpx;
  font-size: 28rpx;
  box-shadow: 4rpx 4rpx 0 var(--ink);
  flex-shrink: 0;
}
</style>
