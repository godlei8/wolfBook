<script setup>
import { reactive, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import AssistantDock from '../../components/assistant/AssistantDock.vue'
import api from '../../services/api'
import storage from '../../services/storage'
import userData from '../../services/user-data'

const DEFAULT_NICKNAME_PREFIX = '微信用户'

const user = ref(null)
const profileEditorVisible = ref(false)
const loginLoading = ref(false)
const profileSaving = ref(false)

const viewState = reactive({
  favoritesCount: 0,
  sessionsCount: 0,
  profileStatusText: '未登录',
  avatarPreview: '',
  profileInitial: '我',
})

const profileForm = reactive({
  nickname: '',
  avatar: '',
})

function needsProfileCompletion(profile) {
  if (!profile) return true
  return !profile.avatar || !profile.nickname || profile.nickname.startsWith(DEFAULT_NICKNAME_PREFIX)
}

function syncProfileView() {
  viewState.avatarPreview = profileForm.avatar || user.value?.avatar || ''
  viewState.profileInitial = (profileForm.nickname || user.value?.nickname || '我').trim().slice(0, 1) || '我'
  viewState.profileStatusText = !user.value
    ? '未登录，仅浏览本地内容'
    : needsProfileCompletion(user.value)
      ? '建议完善头像与昵称'
      : '资料已同步'
}

async function refreshCounts() {
  try {
    const [favoriteState, sessions] = await Promise.all([
      userData.loadFavoriteBoards(),
      userData.loadSessions(),
    ])
    viewState.favoritesCount = favoriteState?.boardIds?.length || 0
    viewState.sessionsCount = sessions?.length || 0
  } catch (error) {
    viewState.favoritesCount = storage.getFavorites().length
    viewState.sessionsCount = storage.getSessions().length
  }
}

function fillProfileForm(profile) {
  profileForm.nickname = profile?.nickname || ''
  profileForm.avatar = profile?.avatar || ''
  syncProfileView()
}

async function refreshData() {
  const token = storage.getAuthToken()
  if (!token) {
    user.value = null
    fillProfileForm(null)
    await refreshCounts()
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

  await refreshCounts()
}

function requestWxLoginCode() {
  return new Promise((resolve, reject) => {
    uni.login({
      provider: 'weixin',
      success: (res) => {
        if (res.code) {
          resolve(res.code)
          return
        }
        reject(new Error('未获取到微信登录凭证'))
      },
      fail: reject,
    })
  })
}

function showRequestError(error, fallback) {
  const title = (error?.message || fallback || '操作失败').slice(0, 30)
  uni.showToast({ title, icon: 'none' })
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
    await refreshCounts()
    uni.hideLoading()
    uni.showToast({ title: '登录成功', icon: 'success' })
    const pendingJudgeInvite = storage.getPendingJudgeInvite()
    if (pendingJudgeInvite?.roomId) {
      setTimeout(() => {
        uni.navigateTo({
          url: `/pages/judge/room?roomId=${pendingJudgeInvite.roomId}&boardName=${encodeURIComponent(pendingJudgeInvite.boardName || '')}`,
        })
      }, 260)
      return
    }
    if (needsProfileCompletion(result.user)) {
      profileEditorVisible.value = true
    }
  } catch (error) {
    uni.hideLoading()
    showRequestError(error, '微信登录失败')
  } finally {
    loginLoading.value = false
  }
}

function handleChooseAvatar(event) {
  const avatarUrl = event?.detail?.avatarUrl
  if (avatarUrl) {
    profileForm.avatar = avatarUrl
    syncProfileView()
  }
}

function handleNicknameInput(event) {
  profileForm.nickname = event.detail?.value || ''
  syncProfileView()
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
    showRequestError(error, '资料更新失败')
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
  <view class="page-shell">
    <view class="hero-title">我的</view>
    <view class="hero-subtitle">账号、收藏和对局笔记都在这里。</view>

    <view class="glass-card section-card profile-card">
      <view class="profile-header">
        <view class="profile-header-copy">
          <view class="section-title">微信资料</view>
          <view class="section-meta">
            {{ user ? '社区互动、身份展示与资料同步入口' : '登录后可参与社区互动并同步微信资料' }}
          </view>
        </view>
        <view class="profile-badge" :class="{ active: !!user }">{{ viewState.profileStatusText }}</view>
      </view>

      <template v-if="user">
        <view class="profile-body">
          <image v-if="user.avatar" class="profile-avatar" :src="user.avatar" mode="aspectFill" />
          <view v-else class="profile-avatar profile-placeholder">{{ viewState.profileInitial }}</view>

          <view class="profile-copy">
            <view class="profile-name">{{ user.nickname || '微信用户' }}</view>
            <view class="profile-openid">{{ user.openid }}</view>
            <view class="profile-helper">用于社区身份展示、发帖互动和资料同步。</view>
          </view>
        </view>

        <button class="button-ghost profile-action" @tap="openProfileEditor">
          更新微信头像和昵称
        </button>
      </template>

      <template v-else>
        <view class="login-panel">
          <view class="login-title">未登录</view>
          <view class="login-desc">登录后可发帖、评论、点赞，并同步你的微信资料。</view>
        </view>
        <button class="button-primary profile-action" :loading="loginLoading" @tap="handleLogin">
          微信登录
        </button>
      </template>
    </view>

    <view class="entry-stack">
      <view class="glass-card section-card entry-card" @tap="openFavorites">
        <view class="entry-copy">
          <view class="entry-title">我的收藏</view>
          <view class="entry-desc">查看已收藏的板子与配置。</view>
        </view>
        <view class="entry-side">
          <view class="entry-count">{{ viewState.favoritesCount }}</view>
          <view class="entry-unit">个板子</view>
          <view class="entry-arrow">›</view>
        </view>
      </view>

      <view class="glass-card section-card entry-card" @tap="openSessions">
        <view class="entry-copy">
          <view class="entry-title">我的笔记</view>
          <view class="entry-desc">继续记录发言、投票和夜间信息。</view>
        </view>
        <view class="entry-side">
          <view class="entry-count">{{ viewState.sessionsCount }}</view>
          <view class="entry-unit">场对局</view>
          <view class="entry-arrow">›</view>
        </view>
      </view>

      <view class="glass-card section-card entry-card" @tap="openSettings">
        <view class="entry-copy">
          <view class="entry-title">设置</view>
          <view class="entry-desc">清理缓存、查看版本与本地数据状态。</view>
        </view>
        <view class="entry-side entry-side--solo">
          <view class="entry-arrow">›</view>
        </view>
      </view>
    </view>

    <view v-if="profileEditorVisible" class="editor-mask" @tap="profileEditorVisible = false">
      <view class="glass-card editor-panel" @tap.stop>
        <view class="editor-handle" />

        <view class="editor-header">
          <view class="editor-header-copy">
            <view class="editor-kicker">WECHAT PROFILE</view>
            <view class="section-title">完善微信资料</view>
            <view class="section-meta">同步头像和昵称后，社区展示与互动身份会更完整。</view>
          </view>
          <view class="editor-close" @tap="profileEditorVisible = false">关闭</view>
        </view>

        <view class="editor-hero">
          <view class="editor-avatar-card">
            <image
              v-if="viewState.avatarPreview"
              class="editor-avatar"
              :src="viewState.avatarPreview"
              mode="aspectFill"
            />
            <view v-else class="editor-avatar profile-placeholder">{{ viewState.profileInitial }}</view>
            <view class="editor-avatar-meta">
              <view class="editor-avatar-label">当前头像</view>
              <view class="editor-avatar-hint">建议使用清晰的微信头像，社区展示会直接同步。</view>
            </view>
          </view>

          <view class="editor-avatar-actions">
            <button class="button-ghost avatar-button" open-type="chooseAvatar" @chooseavatar="handleChooseAvatar">
              选择微信头像
            </button>
            <view class="editor-tip-card">
              <view class="editor-tip-title">资料同步</view>
              <view class="editor-tip-desc">保存后会更新到账户资料里，后续发帖和评论会使用这份信息。</view>
            </view>
          </view>
        </view>

        <view class="editor-field">
          <view class="editor-label-row">
            <view class="editor-label">微信昵称</view>
            <view class="editor-label-hint">{{ profileForm.nickname.trim().length }}/20</view>
          </view>
          <view class="editor-input-shell">
            <input
              :value="profileForm.nickname"
              type="nickname"
              maxlength="20"
              class="field-input editor-input"
              placeholder="请输入或选择微信昵称"
              placeholder-style="color: rgba(255, 255, 255, 0.26); font-size: 30rpx;"
              @input="handleNicknameInput"
            />
          </view>
          <view class="editor-field-tip">建议填写你常用的微信昵称，保存后会同步到社区展示资料。</view>
        </view>

        <view class="editor-actions">
          <button class="button-ghost half-button" @tap="profileEditorVisible = false">稍后再说</button>
          <button class="button-primary half-button" :loading="profileSaving" @tap="saveWechatProfile">
            保存资料
          </button>
        </view>
      </view>
    </view>

    <AssistantDock
      scene="user_index"
      :page-context="{ page: 'user/index', loggedIn: !!user, favoritesCount: viewState.favoritesCount, sessionsCount: viewState.sessionsCount }"
    />
  </view>
</template>

<style scoped lang="scss">
.profile-card {
  margin-top: 20rpx;
}

.profile-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20rpx;
}

.profile-header-copy {
  min-width: 0;
  flex: 1;
}

.profile-badge {
  flex-shrink: 0;
  max-width: 240rpx;
  padding: 10rpx 18rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.06);
  color: #9f9f9f;
  font-size: 22rpx;
  line-height: 1.3;
  text-align: center;
}

.profile-badge.active {
  background: rgba(255, 192, 0, 0.14);
  color: #ffc000;
}

.profile-body {
  display: flex;
  align-items: center;
  gap: 24rpx;
  margin-top: 24rpx;
}

.profile-avatar,
.editor-avatar {
  width: 128rpx;
  height: 128rpx;
  flex-shrink: 0;
  border-radius: 24rpx;
  background: rgba(255, 255, 255, 0.06);
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
.entry-desc {
  margin-top: 6rpx;
  color: #8e8e8e;
  font-size: 20rpx;
  line-height: 1.5;
}

.profile-action {
  margin-top: 20rpx;
  width: 100%;
}

.login-panel {
  margin-top: 20rpx;
}

.login-title,
.entry-title {
  color: #ffffff;
  font-size: 30rpx;
  line-height: 1.14;
  font-weight: 700;
}

.entry-stack {
  display: grid;
  gap: 12rpx;
  margin-top: 18rpx;
}

.entry-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14rpx;
  min-height: 112rpx;
  padding-top: 20rpx;
  padding-bottom: 20rpx;
}

.entry-copy {
  min-width: 0;
  flex: 1;
}

.entry-side {
  display: grid;
  grid-template-columns: auto auto;
  grid-template-rows: auto auto;
  justify-items: end;
  column-gap: 10rpx;
  row-gap: 0;
  min-width: 106rpx;
  align-items: end;
}

.entry-side--solo {
  min-width: auto;
  align-self: stretch;
  display: flex;
  align-items: center;
}

.entry-count {
  grid-column: 1;
  grid-row: 1 / span 2;
  align-self: center;
  color: #ffc000;
  font-size: 42rpx;
  line-height: 0.94;
  font-weight: 700;
}

.entry-unit {
  grid-column: 2;
  grid-row: 1;
  align-self: end;
  color: #969696;
  font-size: 18rpx;
  line-height: 1;
}

.entry-arrow {
  grid-column: 2;
  grid-row: 2;
  color: #ffffff;
  font-size: 30rpx;
  line-height: 1;
}

.profile-action,
.avatar-button,
.half-button {
  height: 72rpx;
  line-height: 72rpx;
  padding: 0 24rpx;
  border-radius: 16rpx;
  font-size: 24rpx;
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
  padding: 18rpx 24rpx calc(env(safe-area-inset-bottom) + 28rpx);
  border-top-left-radius: 28rpx;
  border-top-right-radius: 28rpx;
}

.editor-handle {
  width: 88rpx;
  height: 8rpx;
  border-radius: 999rpx;
  margin: 0 auto 22rpx;
  background: rgba(255, 255, 255, 0.18);
}

.editor-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20rpx;
}

.editor-header-copy {
  min-width: 0;
  flex: 1;
}

.editor-kicker {
  color: #ffc000;
  font-size: 20rpx;
  font-weight: 700;
  letter-spacing: 4rpx;
}

.editor-close {
  padding: 10rpx 18rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.04);
  color: #9c9c9c;
  font-size: 24rpx;
  flex-shrink: 0;
}

.editor-hero {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
  gap: 18rpx;
  margin-top: 28rpx;
}

.editor-avatar-card,
.editor-tip-card {
  padding: 22rpx;
  border-radius: 22rpx;
  background: rgba(255, 255, 255, 0.035);
  border: 1px solid rgba(255, 192, 0, 0.08);
}

.editor-avatar-card {
  display: flex;
  align-items: center;
  gap: 18rpx;
}

.editor-avatar-meta {
  min-width: 0;
  flex: 1;
}

.editor-avatar-label,
.editor-tip-title {
  color: #ffffff;
  font-size: 24rpx;
  font-weight: 700;
}

.editor-avatar-hint,
.editor-tip-desc {
  margin-top: 8rpx;
  color: #9c9c9c;
  font-size: 22rpx;
  line-height: 1.6;
}

.editor-avatar-actions {
  display: grid;
  gap: 14rpx;
}

.avatar-button {
  width: 100%;
  margin-top: 0;
}

.editor-field {
  margin-top: 24rpx;
  padding: 20rpx 22rpx 18rpx;
  border-radius: 22rpx;
  background: rgba(255, 255, 255, 0.028);
  border: 1px solid rgba(255, 255, 255, 0.04);
}

.editor-label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  margin-bottom: 14rpx;
}

.editor-label {
  color: #ffffff;
  font-size: 24rpx;
  font-weight: 700;
  letter-spacing: 1rpx;
}

.editor-label-hint {
  padding: 6rpx 14rpx;
  border-radius: 999rpx;
  color: #b5b5b5;
  font-size: 20rpx;
  line-height: 1;
  background: rgba(255, 255, 255, 0.05);
}

.editor-input-shell {
  border-radius: 18rpx;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.032) 0%, rgba(255, 255, 255, 0.02) 100%);
  border: 1px solid rgba(255, 192, 0, 0.08);
  box-shadow: inset 0 1rpx 0 rgba(255, 255, 255, 0.04);
}

.editor-input {
  height: 82rpx;
  line-height: 82rpx;
  padding: 0 22rpx;
  margin-top: 0;
  color: #ffffff;
  font-size: 30rpx;
  border: none;
  background: transparent;
}

.editor-field-tip {
  margin-top: 12rpx;
  color: #8f8f8f;
  font-size: 22rpx;
  line-height: 1.55;
}

.editor-actions {
  margin-top: 28rpx;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16rpx;
}

.half-button {
  width: 100%;
}

@media (max-width: 640rpx) {
  .editor-hero {
    grid-template-columns: 1fr;
  }

  .editor-avatar-card {
    align-items: flex-start;
  }
}
</style>
