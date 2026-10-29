<script setup>
import { ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import NavBar from '../../components/NavBar.vue'
import api from '../../services/api'
import storage from '../../services/storage'
import { getJudgePhaseLabel, getJudgeRoomStatusLabel } from '../../utils/judge/meta'
import { syncTabBar } from '../../utils/tabbar'
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
  syncTabBar()
  syncPendingInvite()
  loadRooms()
})
</script>

<template>
  <view class="nt-page nt-page--tab">
    <NavBar wordmark="NIGHT" />

    <view class="nt-gutter ji-head">
      <text class="nt-title">开一局</text>
      <text class="nt-meta">你当法官，大家各拿手机翻自己的身份牌。</text>
    </view>

    <view class="nt-gutter nt-stack ji-body">
      <view class="nt-ink ji-hero" @tap="openCreate">
        <image class="ji-hero__bg" src="/static/scenes/moon.webp" mode="aspectFill" webp />
        <view class="ji-hero__fan">
          <view v-for="slot in ['l', 'c', 'r']" :key="slot" class="ji-back" :class="`ji-back--${slot}`">
            <view class="ji-back__line" />
            <text class="ji-back__moon">☾</text>
          </view>
        </view>
        <view class="ji-hero__plate">
          <view class="ji-hero__copy">
            <text class="ji-hero__eyebrow">法官不入座 · 手机发身份</text>
            <text class="ji-hero__title serif">建房当法官</text>
          </view>
          <view class="nt-btn nt-btn--gold nt-btn--small">{{ hasAuth ? '选板子建房' : '先登录' }}</view>
        </view>
      </view>

      <view class="nt-panel">
        <view class="nt-panel__head">
          <text class="nt-h2">输入房号加入</text>
          <text class="nt-meta">问法官要 4 位房号</text>
        </view>
        <view class="ji-join">
          <input
            :value="roomIdInput"
            class="ji-join__input serif"
            type="number"
            maxlength="64"
            placeholder="2847"
            placeholder-class="ji-join__placeholder"
            @input="handleInput"
            @confirm="enterRoom"
          />
          <view class="nt-btn nt-btn--gold" @tap="enterRoom">入座</view>
        </view>
      </view>

      <view v-if="sharedRoomId" class="nt-ink ji-invite">
        <text class="nt-label c-gold">微信邀请</text>
        <text class="ji-invite__text">{{ sharedInviteText() }}</text>
        <view class="nt-btn nt-btn--gold nt-btn--block" @tap="openRoom(sharedRoomId)">
          {{ hasAuth ? '进入房间' : '登录后进入房间' }}
        </view>
      </view>

      <view v-if="!hasAuth" class="nt-panel ji-login">
        <text class="nt-meta">建房和入座都需要先登录，登录后最近的房间会保留在这里。</text>
        <view class="nt-btn nt-btn--cream nt-btn--block" @tap="openUserLogin">去登录</view>
      </view>

      <view v-else class="ji-rooms">
        <view class="nt-panel__head">
          <text class="nt-h2">最近的房间</text>
          <text v-if="rooms.length" class="nt-meta">{{ rooms.length }} 间</text>
        </view>
        <view v-if="!rooms.length && !loading" class="nt-empty">还没有房间。建一间，或者输入朋友给的房号。</view>
        <view v-for="item in rooms" :key="item.roomId" class="nt-ink ji-room" @tap="openRoom(item.roomId)">
          <view class="ji-room__main">
            <view class="ji-room__tags">
              <text class="nt-tag" :class="`ji-status--${item.roomStatus}`">{{ getJudgeRoomStatusLabel(item.roomStatus) }}</text>
              <text v-if="item.owned" class="nt-tag">我主持</text>
            </view>
            <text class="ji-room__name serif">{{ item.boardName }}</text>
            <text class="nt-meta">{{ item.playerCount }} 人 · {{ phaseLabel(item) }} · 已入座 {{ item.joinedCount }}/{{ item.playerCount }}</text>
          </view>
          <view class="ji-room__code">
            <text class="ji-room__label">房号</text>
            <text class="ji-room__digits serif">{{ item.roomCode || '—' }}</text>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.ji-head {
  padding-top: 8rpx;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.ji-body {
  margin-top: 36rpx;
}

.ji-hero {
  position: relative;
  height: 460rpx;
  border-radius: 40rpx;
  overflow: hidden;
  background: #1b1724;
}

.ji-hero__bg {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
}

.ji-hero__fan {
  position: absolute;
  left: 0;
  right: 0;
  top: 56rpx;
  height: 260rpx;
}

.ji-back {
  position: absolute;
  width: 150rpx;
  height: 200rpx;
  border-radius: 20rpx;
  border: 4rpx solid var(--ink);
  background: var(--panel);
  box-shadow: 6rpx 6rpx 0 var(--ink);
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
}

.ji-back--l {
  left: 150rpx;
  top: 30rpx;
  transform: rotate(-10deg);
}

.ji-back--c {
  left: 260rpx;
  top: 0;
  z-index: 2;
}

.ji-back--r {
  left: 370rpx;
  top: 30rpx;
  transform: rotate(10deg);
}

.ji-back__line {
  position: absolute;
  left: 12rpx;
  top: 12rpx;
  right: 12rpx;
  bottom: 12rpx;
  border: 3rpx solid var(--gold);
  border-radius: 12rpx;
  opacity: 0.55;
}

.ji-back__moon {
  color: var(--gold);
  font-size: 48rpx;
}

.ji-hero__plate {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 26rpx 32rpx;
  background: var(--ink);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.ji-hero__copy {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
  min-width: 0;
}

.ji-hero__eyebrow {
  color: var(--gold);
  font-size: 24rpx;
  font-weight: 700;
}

.ji-hero__title {
  font-size: 44rpx;
  font-weight: 900;
}

.ji-join {
  display: flex;
  align-items: center;
  gap: 20rpx;
}

.ji-join__input {
  flex: 1;
  min-width: 0;
  height: 112rpx;
  padding: 0 28rpx;
  border-radius: 28rpx;
  background: var(--bg);
  border: 4rpx solid var(--ink);
  color: var(--gold);
  font-size: 56rpx;
  font-weight: 900;
  letter-spacing: 12rpx;
  box-sizing: border-box;
}

.ji-join__placeholder {
  color: #4a4060;
  letter-spacing: 12rpx;
}

.ji-invite {
  padding: 32rpx;
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}

.ji-invite__text {
  color: var(--text-soft);
  font-size: 28rpx;
  line-height: 1.7;
}

.ji-login {
  gap: 24rpx;
}

.ji-rooms {
  display: flex;
  flex-direction: column;
  gap: 24rpx;
  margin-top: 8rpx;
}

.ji-room {
  padding: 28rpx 32rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24rpx;
}

.ji-room__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.ji-room__tags {
  display: flex;
  gap: 12rpx;
}

.ji-status--playing {
  background: #3a2e14;
  color: var(--gold);
}

.ji-status--finished {
  color: var(--text-3);
}

.ji-room__name {
  font-size: 36rpx;
  font-weight: 900;
}

.ji-room__code {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4rpx;
  flex-shrink: 0;
}

.ji-room__label {
  color: var(--text-3);
  font-size: 22rpx;
}

.ji-room__digits {
  color: var(--gold);
  font-size: 44rpx;
  font-weight: 900;
  letter-spacing: 4rpx;
}
</style>
