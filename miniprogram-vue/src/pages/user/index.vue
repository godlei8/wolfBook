<script setup>
import { computed, reactive, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import api from '../../services/api'
import storage from '../../services/storage'

const user = ref(null)
const favoritesCount = ref(0)
const sessionsCount = ref(0)
const profileEditorVisible = ref(false)
const loginLoading = ref(false)
const profileSaving = ref(false)

const profileForm = reactive({
  nickname: '',
  avatar: '',
})

const avatarPreview = computed(() => profileForm.avatar || user.value?.avatar || '')
const profileInitial = computed(() => (profileForm.nickname || user.value?.nickname || '我').trim().slice(0, 1))
const profileStatusText = computed(() => {
  if (!user.value) {
    return '未登录'
  }
  return looksLikeMockProfile(user.value) ? '建议完善头像与昵称' : '资料已同步'
})

function looksLikeMockProfile(profile) {
  if (!profile) return true
  return !profile.avatar || profile.avatar.includes('picsum.photos') || /^夜行者/i.test(profile.nickname || '')
}

function syncCounters() {
  favoritesCount.value = storage.getFavorites().length
  sessionsCount.value = storage.getSessions().length
}

function fillProfileForm(profile) {
  profileForm.nickname = profile?.nickname || ''
  profileForm.avatar = profile?.avatar || ''
}

async function refreshData() {
  syncCounters()
  const token = storage.getAuthToken()
  if (!token) {
    user.value = null
    fillProfileForm(null)
    return
  }

  try {
    const latestUser = await api.getUserInfo()
    storage.setUserProfile(latestUser)
    user.value = latestUser
    fillProfileForm(latestUser)
  } catch (error) {
    storage.clearAuthToken()
    storage.setUserProfile(null)
    user.value = null
    fillProfileForm(null)
  }
}

function requestWxLoginCode() {
  return new Promise((resolve, reject) => {
    uni.login({
      provider: 'weixin',
      success: (res) => resolve(res.code || 'mock-code'),
      fail: reject,
    })
  })
}

async function handleLogin() {
  if (loginLoading.value) return
  loginLoading.value = true
  try {
    uni.showLoading({ title: '登录中...' })
    const code = await requestWxLoginCode()
    const result = await api.login(code)
    storage.setAuthToken(result.token)
    storage.setUserProfile(result.user)
    user.value = result.user
    fillProfileForm(result.user)
    uni.hideLoading()
    uni.showToast({ title: '登录成功', icon: 'success' })
    if (looksLikeMockProfile(result.user)) {
      profileEditorVisible.value = true
    }
  } catch (error) {
    uni.hideLoading()
    uni.showToast({ title: '登录失败', icon: 'none' })
  } finally {
    loginLoading.value = false
  }
}

function handleChooseAvatar(event) {
  const avatarUrl = event?.detail?.avatarUrl
  if (avatarUrl) {
    profileForm.avatar = avatarUrl
  }
}

function handleNicknameInput(event) {
  profileForm.nickname = event.detail?.value || ''
}

async function saveWechatProfile() {
  if (!storage.getAuthToken()) {
    uni.showToast({ title: '请先登录', icon: 'none' })
    return
  }
  if (!profileForm.nickname.trim()) {
    uni.showToast({ title: '请输入微信昵称', icon: 'none' })
    return
  }

  profileSaving.value = true
  try {
    let avatar = profileForm.avatar || user.value?.avatar || ''
    if (avatar && !avatar.startsWith('http://') && !avatar.startsWith('https://')) {
      const uploadResult = await api.uploadImage(avatar)
      avatar = uploadResult.url
    }

    const latestUser = await api.updateUserInfo({
      nickname: profileForm.nickname.trim(),
      avatar,
    })
    storage.setUserProfile(latestUser)
    user.value = latestUser
    fillProfileForm(latestUser)
    profileEditorVisible.value = false
    uni.showToast({ title: '资料已更新', icon: 'success' })
  } catch (error) {
    uni.showToast({ title: '资料更新失败', icon: 'none' })
  } finally {
    profileSaving.value = false
  }
}

function openProfileEditor() {
  fillProfileForm(user.value)
  profileEditorVisible.value = true
}

function openFavorites() {
  uni.navigateTo({ url: '/pages/user/favorites' })
}

function openSessions() {
  uni.switchTab({ url: '/pages/sessions/index' })
}

function openSettings() {
  uni.navigateTo({ url: '/pages/user/settings' })
}

onShow(() => {
  refreshData()
})
</script>

<template>
  <view class="page-shell user-page-shell">
    <view class="page-kicker">PRIVATE GARAGE</view>
    <view class="hero-title">我的</view>
    <view class="hero-subtitle">账号、收藏和本地对局记录都在这里。</view>

    <view class="profile-stage">
      <view class="stage-topline">
        <view class="stage-kicker">WECHAT PROFILE</view>
        <view class="stage-status">{{ profileStatusText }}</view>
      </view>

      <template v-if="user">
        <view class="profile-main">
          <image v-if="user.avatar" class="profile-avatar" :src="user.avatar" mode="aspectFill" />
          <view v-else class="profile-avatar profile-placeholder">{{ profileInitial }}</view>

          <view class="profile-copy">
            <view class="profile-name">{{ user.nickname || '微信用户' }}</view>
            <view class="profile-openid">{{ user.openid }}</view>
            <view class="profile-helper">用于社区互动、身份展示与资料同步。</view>
          </view>
        </view>

        <button class="ghost-cta" @tap="openProfileEditor">更新微信头像和昵称</button>
      </template>

      <template v-else>
        <view class="login-copy">
          <view class="login-title">未登录</view>
          <view class="login-desc">登录后可发帖、评论、点赞，并同步你的微信资料。</view>
        </view>
        <button class="gold-cta" :loading="loginLoading" @tap="handleLogin">微信登录</button>
      </template>
    </view>

    <view class="nav-stack">
      <view class="nav-panel" @tap="openFavorites">
        <view class="nav-copy">
          <view class="nav-kicker">FAVORITES</view>
          <view class="nav-title">我的收藏</view>
          <view class="nav-desc">查看已收藏的板子与配置。</view>
        </view>
        <view class="nav-meta">
          <view class="nav-value">{{ favoritesCount }}</view>
          <view class="nav-unit">个板子</view>
          <view class="nav-arrow">›</view>
        </view>
      </view>

      <view class="nav-panel" @tap="openSessions">
        <view class="nav-copy">
          <view class="nav-kicker">LOCAL NOTES</view>
          <view class="nav-title">本地笔记</view>
          <view class="nav-desc">继续记录发言、投票和夜间信息。</view>
        </view>
        <view class="nav-meta">
          <view class="nav-value">{{ sessionsCount }}</view>
          <view class="nav-unit">条记录</view>
          <view class="nav-arrow">›</view>
        </view>
      </view>

      <view class="nav-panel" @tap="openSettings">
        <view class="nav-copy">
          <view class="nav-kicker">SYSTEM</view>
          <view class="nav-title">设置</view>
          <view class="nav-desc">清理缓存、查看版本与本地数据状态。</view>
        </view>
        <view class="nav-meta nav-meta-light">
          <view class="nav-arrow">›</view>
        </view>
      </view>
    </view>

    <view v-if="profileEditorVisible" class="editor-mask" @tap="profileEditorVisible = false">
      <view class="editor-panel" @tap.stop>
        <view class="sheet-topline">
          <view>
            <view class="sheet-kicker">WECHAT PROFILE</view>
            <view class="sheet-title">完善微信资料</view>
          </view>
          <view class="sheet-close" @tap="profileEditorVisible = false">关闭</view>
        </view>

        <view class="sheet-desc">选择微信头像并确认昵称，资料会同步保存到后端。</view>

        <view class="sheet-avatar-stage">
          <image v-if="avatarPreview" class="sheet-avatar" :src="avatarPreview" mode="aspectFill" />
          <view v-else class="sheet-avatar profile-placeholder">{{ profileInitial }}</view>
          <button class="ghost-cta avatar-cta" open-type="chooseAvatar" @chooseavatar="handleChooseAvatar">
            选择微信头像
          </button>
        </view>

        <view class="sheet-field">
          <view class="sheet-label">微信昵称</view>
          <input
            :value="profileForm.nickname"
            type="nickname"
            class="sheet-input"
            placeholder="请输入或选择微信昵称"
            @input="handleNicknameInput"
          />
        </view>

        <view class="sheet-actions">
          <button class="ghost-cta half-button" @tap="profileEditorVisible = false">取消</button>
          <button class="gold-cta half-button" :loading="profileSaving" @tap="saveWechatProfile">保存资料</button>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.user-page-shell {
  background: #000000;
}

.page-kicker {
  color: #ffc000;
  font-size: 20rpx;
  letter-spacing: 6rpx;
  margin-bottom: 16rpx;
}

.hero-title {
  font-size: 68rpx;
  font-weight: 700;
  line-height: 1;
}

.hero-subtitle {
  max-width: 560rpx;
  color: #7d7d7d;
}

.profile-stage,
.nav-panel,
.editor-panel {
  background: #181818;
}

.profile-stage {
  margin-top: 32rpx;
  padding: 28rpx;
}

.stage-topline,
.sheet-topline {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.stage-kicker,
.sheet-kicker,
.nav-kicker {
  color: #ffc000;
  font-size: 18rpx;
  letter-spacing: 4rpx;
}

.stage-status {
  color: #7d7d7d;
  font-size: 22rpx;
}

.profile-main {
  display: flex;
  align-items: center;
  gap: 24rpx;
  margin-top: 24rpx;
}

.profile-avatar,
.sheet-avatar {
  width: 128rpx;
  height: 128rpx;
  flex-shrink: 0;
  background: #202020;
}

.profile-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #ffc000;
  font-size: 44rpx;
  font-weight: 700;
}

.profile-copy {
  min-width: 0;
  flex: 1;
}

.profile-name {
  font-size: 48rpx;
  line-height: 1.04;
  font-weight: 700;
  color: #ffffff;
}

.profile-openid {
  margin-top: 10rpx;
  color: #969696;
  font-size: 24rpx;
  word-break: break-all;
}

.profile-helper,
.login-desc,
.sheet-desc,
.nav-desc {
  margin-top: 12rpx;
  color: #7d7d7d;
  font-size: 24rpx;
  line-height: 1.7;
}

.login-copy {
  margin-top: 28rpx;
}

.login-title,
.sheet-title,
.nav-title {
  color: #ffffff;
  font-size: 38rpx;
  line-height: 1.12;
  font-weight: 700;
}

.ghost-cta,
.gold-cta {
  height: 88rpx;
  line-height: 88rpx;
  padding: 0 28rpx;
  border-radius: 0;
  font-size: 28rpx;
  font-weight: 700;
}

.ghost-cta::after,
.gold-cta::after {
  border: none;
}

.ghost-cta {
  margin-top: 28rpx;
  background: transparent;
  color: #ffffff;
  border: 1px solid rgba(255, 255, 255, 0.48);
}

.gold-cta {
  margin-top: 28rpx;
  background: #ffc000;
  color: #000000;
}

.nav-stack {
  margin-top: 28rpx;
  display: grid;
  gap: 2rpx;
}

.nav-panel {
  min-height: 170rpx;
  padding: 28rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.nav-copy {
  min-width: 0;
  flex: 1;
}

.nav-title {
  margin-top: 12rpx;
}

.nav-meta {
  min-width: 124rpx;
  text-align: right;
}

.nav-meta-light {
  min-width: 48rpx;
}

.nav-value {
  color: #ffc000;
  font-size: 54rpx;
  line-height: 0.92;
  font-weight: 700;
}

.nav-unit {
  margin-top: 8rpx;
  color: #969696;
  font-size: 22rpx;
}

.nav-arrow {
  margin-top: 6rpx;
  color: #ffffff;
  font-size: 38rpx;
  line-height: 1;
}

.editor-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.78);
  display: flex;
  align-items: flex-end;
  z-index: 30;
}

.editor-panel {
  width: 100%;
  padding: 28rpx 28rpx calc(env(safe-area-inset-bottom) + 28rpx);
}

.sheet-close {
  color: #969696;
  font-size: 22rpx;
}

.sheet-desc {
  margin-top: 16rpx;
}

.sheet-avatar-stage {
  margin-top: 28rpx;
  display: grid;
  justify-items: start;
  gap: 20rpx;
}

.avatar-cta {
  width: 280rpx;
  margin-top: 0;
}

.sheet-field {
  margin-top: 28rpx;
}

.sheet-label {
  color: #ffffff;
  font-size: 24rpx;
  margin-bottom: 12rpx;
}

.sheet-input {
  height: 88rpx;
  padding: 0 22rpx;
  box-sizing: border-box;
  background: #202020;
  color: #ffffff;
  border-radius: 0;
}

.sheet-actions {
  margin-top: 28rpx;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16rpx;
}

.half-button {
  width: 100%;
  margin-top: 0;
}
</style>
