<script setup>
import { ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import AssistantDock from '../../components/assistant/AssistantDock.vue'
import api from '../../services/api'
import storage from '../../services/storage'
import { getJudgePhaseLabel, getJudgeRoomStatusLabel } from '../../utils/judge/meta'
import { goToUserTab, hasAuthToken, requireAuth } from '../../utils/auth'

const loading = ref(false)
const rooms = ref([])
const roomIdInput = ref('')
const hasAuth = ref(false)
const sharedRoomId = ref('')
const sharedBoardName = ref('')
const sharedInviteEntered = ref(false)

function safeDecode(value) {
  if (!value) return ''
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

function applySharedInvite(invite, persist = false) {
  const nextRoomId = typeof invite?.roomId === 'string' ? invite.roomId.trim() : ''
  if (!nextRoomId) {
    return
  }
  sharedRoomId.value = nextRoomId
  sharedBoardName.value = safeDecode(invite?.boardName || '')
  sharedInviteEntered.value = false
  roomIdInput.value = nextRoomId
  if (persist) {
    storage.setPendingJudgeInvite({
      roomId: nextRoomId,
      boardName: sharedBoardName.value,
    })
  }
}

function syncPendingInvite() {
  const pendingInvite = storage.getPendingJudgeInvite()
  if (!pendingInvite?.roomId) {
    return
  }
  applySharedInvite(pendingInvite)
}

async function loadRooms() {
  hasAuth.value = hasAuthToken()
  if (!hasAuth.value) {
    rooms.value = []
    return
  }
  loading.value = true
  try {
    rooms.value = await api.getJudgeRecentRooms()
  } catch (error) {
    uni.showToast({ title: error?.message || '法官房间加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
  if (hasAuth.value && sharedRoomId.value && !sharedInviteEntered.value) {
    sharedInviteEntered.value = true
    openRoom(sharedRoomId.value)
  }
}

function handleInput(event) {
  roomIdInput.value = event.detail?.value || ''
}

function openCreate() {
  if (!requireAuth({ redirect: true })) {
    return
  }
  uni.navigateTo({ url: '/pages/judge/create' })
}

function openRoom(roomId) {
  const nextRoomId = String(roomId || '').trim()
  if (!nextRoomId) {
    return
  }
  if (!hasAuth.value) {
    storage.setPendingJudgeInvite({
      roomId: nextRoomId,
      boardName: sharedBoardName.value,
    })
    openUserLogin()
    return
  }
  storage.clearPendingJudgeInvite()
  uni.navigateTo({ url: `/pages/judge/room?roomId=${nextRoomId}` })
}

function enterRoom() {
  const roomId = roomIdInput.value.trim()
  if (!roomId) {
    uni.showToast({ title: '请输入房间号', icon: 'none' })
    return
  }
  openRoom(roomId)
}

function openUserLogin() {
  if (sharedRoomId.value) {
    storage.setPendingJudgeInvite({
      roomId: sharedRoomId.value,
      boardName: sharedBoardName.value,
    })
  }
  goToUserTab()
}

function sharedInviteText() {
  if (sharedBoardName.value) {
    return '好友邀请你进入「' + sharedBoardName.value + '」法官房间。'
  }
  return '好友邀请你通过微信分享进入一个法官房间。'
}

function phaseLabel(item) {
  return `${getJudgeRoomStatusLabel(item.roomStatus)} · ${getJudgePhaseLabel(item.currentPhase)}`
}

onLoad((options) => {
  if (options?.roomId) {
    applySharedInvite(
      {
        roomId: options.roomId,
        boardName: options?.boardName || '',
      },
      true,
    )
  } else {
    syncPendingInvite()
  }
})

onShow(() => {
  syncPendingInvite()
  loadRooms()
})
</script>

<template>
  <view class="page-shell">
    <view class="hero-title">电子法官</view>
    <view class="hero-subtitle">从创建房间、发身份、推进流程到结算结果，都能在这里完成。</view>

    <view class="glass-card section-card launch-card">
      <view class="launch-copy">
        <view class="section-title">快速开局</view>
        <view class="section-desc">优先上线主持人模式，法官不入局，适合线下和复盘局直接开房。</view>
      </view>
      <button class="button-primary launch-button" @tap="openCreate">
        {{ hasAuth ? '创建房间' : '先去登录' }}
      </button>
    </view>

    <view class="glass-card section-card">
      <view class="section-title">进入房间</view>
      <view class="section-desc">输入朋友发来的房间号，或者继续你最近使用过的房间。</view>
      <view class="join-row">
        <input
          :value="roomIdInput"
          class="field-input join-input"
          placeholder="请输入 room_xxx 房间号"
          @input="handleInput"
        />
        <button class="button-primary join-button" @tap="enterRoom">进入</button>
      </view>
    </view>

    <view v-if="sharedRoomId" class="glass-card section-card invite-card">
      <view class="section-title">微信邀请</view>
      <view class="section-desc">{{ sharedInviteText() }}</view>
      <view class="invite-room-id">房间号：{{ sharedRoomId }}</view>
      <button class="button-primary launch-button" @tap="enterRoom">
        {{ hasAuth ? '通过邀请进入房间' : '登录后进入房间' }}
      </button>
    </view>

    <view v-if="!hasAuth" class="glass-card section-card empty-state">
      电子法官需要登录后使用房间能力。
      <button class="button-primary auth-button" @tap="openUserLogin">去登录</button>
    </view>

    <view v-else-if="!rooms.length && !loading" class="glass-card section-card empty-state">
      还没有最近房间，创建一局之后这里会自动保留你的入口。
    </view>

    <view
      v-for="item in rooms"
      :key="item.roomId"
      class="glass-card section-card room-card"
      @tap="openRoom(item.roomId)"
    >
      <view class="room-top">
        <view class="pill pill-gold">{{ getJudgeRoomStatusLabel(item.roomStatus) }}</view>
        <view class="pill pill-white">{{ item.roomId }}</view>
      </view>
      <view class="room-title">{{ item.boardName }}</view>
      <view class="room-meta">{{ item.playerCount }} 人局 · {{ phaseLabel(item) }}</view>
      <view class="room-progress">
        <view class="progress-item">已入座 {{ item.joinedCount }}/{{ item.playerCount }}</view>
        <view class="progress-item">已准备 {{ item.readyCount }}/{{ item.playerCount }}</view>
      </view>
      <view v-if="item.latestAnnouncement" class="room-desc">{{ item.latestAnnouncement }}</view>
    </view>

    <AssistantDock
      scene="judge_index"
      :page-context="{ page: 'judge/index', roomCount: rooms.length, hasAuth }"
    />
  </view>
</template>

<style scoped lang="scss">
.launch-card {
  margin-top: 24rpx;
}

.launch-button,
.auth-button {
  margin-top: 22rpx;
  width: 100%;
}

.join-row {
  display: flex;
  gap: 16rpx;
  margin-top: 18rpx;
  align-items: center;
}

.join-input {
  flex: 1;
  min-width: 0;
}

.join-button {
  width: 144rpx;
  flex-shrink: 0;
}

.room-card {
  margin-top: 22rpx;
}

.invite-card {
  margin-top: 22rpx;
}

.invite-room-id {
  margin-top: 14rpx;
  color: #f4d16c;
  font-size: 26rpx;
  font-weight: 700;
}

.room-top,
.room-progress {
  display: flex;
  justify-content: space-between;
  gap: 16rpx;
  flex-wrap: wrap;
}

.room-title {
  margin-top: 16rpx;
  font-size: 38rpx;
  font-weight: 700;
}

.room-meta {
  margin-top: 8rpx;
  color: #b5b5b5;
  font-size: 24rpx;
}

.room-progress {
  margin-top: 16rpx;
  color: #f1ddb1;
  font-size: 22rpx;
}

.room-desc {
  margin-top: 14rpx;
  color: #8f8f8f;
  font-size: 24rpx;
  line-height: 1.7;
}
</style>
