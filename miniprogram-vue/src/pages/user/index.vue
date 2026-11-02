<script setup>
import { computed, reactive, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import NavBar from '../../components/NavBar.vue'
import api from '../../services/api'
import storage from '../../services/storage'
import userData from '../../services/user-data'
import { getNavInsets } from '../../utils/nav'
import { syncTabBar } from '../../utils/tabbar'

const DEFAULT_NICKNAME_PREFIX = '微信用户'
const insets = getNavInsets()

const user = ref(null)
const profileEditorVisible = ref(false)
const loginLoading = ref(false)
const profileSaving = ref(false)

const viewState = reactive({
  favoritesCount: 0,
  sessionsCount: 0,
  hostedCount: 0,
  playedCount: 0,
  profileStatusText: '未登录',
  avatarPreview: '',
  profileInitial: '我',
})

// 原型 D11：可以挑一张角色立绘当头像
const artRoles = ref([])
const avatarRole = computed(() => artRoles.value.find((role) => role.portrait && role.portrait === user.value?.avatar) || null)

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
    if (storage.getAuthToken()) {
      const rooms = await api.getJudgeRecentRooms().catch(() => [])
      viewState.hostedCount = rooms.filter((room) => room.owned).length
      viewState.playedCount = rooms.filter((room) => !room.owned).length
    } else {
      viewState.hostedCount = 0
      viewState.playedCount = 0
    }
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
    // 4001 已由 request.js 统一清登录态；其余错误（超时、5xx、断网）保留本地缓存，不能把用户踢下线
    const cached = storage.getUserProfile()
    user.value = cached || null
    fillProfileForm(cached || null)
    if (cached) {
      uni.showToast({ title: '资料刷新失败，已显示本地缓存', icon: 'none' })
    } else if (storage.getAuthToken()) {
      showRequestError(error, '资料加载失败')
    }
  }

  await refreshCounts()
}

function requestWxLoginCode() {
  // #ifdef H5
  // H5 拿不到微信登录凭证：开发模式下给一个本浏览器固定的模拟 code（本地后端 test 配置接受任意 code），方便在浏览器里联调
  if (import.meta.env.DEV) {
    let code = uni.getStorageSync('h5_dev_login_code')
    if (!code) {
      code = `h5-dev-${Date.now().toString(36)}`
      uni.setStorageSync('h5_dev_login_code', code)
    }
    return Promise.resolve(code)
  }
  // #endif
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
  if (profileSaving.value) return
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

function openRoles() {
  uni.navigateTo({ url: '/pages/roles/list' })
}

function openHosted() {
  uni.switchTab({ url: '/pages/judge/index' })
}

function openMyPosts() {
  if (!storage.getAuthToken()) {
    uni.showToast({ title: '请先登录', icon: 'none' })
    return
  }
  storage.setCommunityFilterContext({ mine: true })
  uni.switchTab({ url: '/pages/community/index' })
}

async function loadArtRoles() {
  try {
    artRoles.value = (await api.getRoles()).filter((role) => role.portrait && role.fullIllustration)
  } catch (error) {
    artRoles.value = []
  }
}

// 把一张角色立绘设成头像（直接用立绘的头像图地址）
async function chooseRoleAvatar(role) {
  if (!storage.getAuthToken() || !user.value) {
    uni.showToast({ title: '登录后才能换头像', icon: 'none' })
    return
  }
  if (profileSaving.value || role.portrait === user.value.avatar) return
  profileSaving.value = true
  try {
    const latestUser = await api.updateUserInfo({ nickname: user.value.nickname, avatar: role.portrait })
    storage.setUserProfile(latestUser)
    user.value = latestUser
    fillProfileForm(latestUser)
    uni.showToast({ title: `头像换成了${role.name}`, icon: 'none' })
  } catch (error) {
    showRequestError(error, '头像更新失败')
  } finally {
    profileSaving.value = false
  }
}

function openSettings() {
  uni.navigateTo({ url: '/pages/user/settings' })
}

onShow(() => {
  syncTabBar()
  refreshData()
  if (!artRoles.value.length) loadArtRoles()
})
</script>

<template>
  <view class="nt-page nt-page--tab">
    <NavBar overlay />

    <view class="nt-gutter me-head" :style="{ paddingTop: `${insets.statusBar + 12}px` }">
      <view class="me-avatar" :style="{ borderColor: avatarRole ? avatarRole.accent : '#3F9A6A' }" @tap="user ? openProfileEditor() : handleLogin()">
        <image v-if="user && user.avatar" class="me-avatar__img" :src="user.avatar" mode="aspectFill" />
        <text v-else class="me-avatar__text serif">{{ viewState.profileInitial }}</text>
      </view>
      <view class="me-head__text">
        <text class="me-name serif">{{ user ? user.nickname || '微信用户' : '还没登录' }}</text>
        <text class="nt-meta">{{ user ? (avatarRole ? `头像：${avatarRole.name}${avatarRole.alias ? ` · ${avatarRole.alias}` : ''}` : viewState.profileStatusText) : '登录后同步收藏、笔记和对局' }}</text>
      </view>
      <view class="nt-iconbtn me-gear" @tap="openSettings">⚙</view>
    </view>

    <view class="nt-gutter nt-stack me-body">
      <view v-if="!user" class="nt-ink me-login">
        <text class="me-login__title serif">登录 Wolfbook</text>
        <text class="nt-meta">登录后可以建房当法官、入座、发帖评论，收藏和笔记也会同步到云端。</text>
        <view class="nt-btn nt-btn--gold nt-btn--block" :class="{ 'nt-btn--disabled': loginLoading }" @tap="handleLogin">
          {{ loginLoading ? '登录中…' : '微信登录' }}
        </view>
      </view>

      <view v-else class="nt-ink me-art">
        <view class="nt-panel__head">
          <text class="me-art__title">换一张立绘当头像</text>
          <text class="nt-meta">{{ artRoles.length }} 张可选</text>
        </view>
        <scroll-view scroll-x class="me-art__scroll" :show-scrollbar="false">
          <view class="me-art__row">
            <view
              v-for="role in artRoles"
              :key="role.id"
              class="me-art__item"
              :class="{ 'me-art__item--on': avatarRole && avatarRole.id === role.id }"
              @tap="chooseRoleAvatar(role)"
            >
              <view class="nt-token me-art__token">
                <image class="nt-token__img" :src="role.portrait" mode="aspectFill" :style="{ borderColor: role.accent }" webp />
              </view>
            </view>
          </view>
        </scroll-view>
      </view>

      <view class="me-stats">
        <view class="me-stat" @tap="openHosted">
          <text class="me-stat__num serif">{{ viewState.playedCount }}</text>
          <text class="me-stat__label">参与对局</text>
        </view>
        <view class="me-stat" @tap="openHosted">
          <text class="me-stat__num serif">{{ viewState.hostedCount }}</text>
          <text class="me-stat__label">主持对局</text>
        </view>
        <view class="me-stat" @tap="openFavorites">
          <text class="me-stat__num serif">{{ viewState.favoritesCount }}</text>
          <text class="me-stat__label">收藏板子</text>
        </view>
      </view>

      <view class="nt-panel nt-panel--tight me-menu">
        <view class="me-row" @tap="openRoles">
          <view class="me-row__icon"><view class="me-ico-cards" /></view>
          <text class="me-row__label">角色图鉴</text>
          <text class="nt-arrow">›</text>
        </view>
        <view class="me-row" @tap="openHosted">
          <view class="me-row__icon"><text class="me-ico-moon">☾</text></view>
          <text class="me-row__label">我主持过的房间</text>
          <text class="nt-arrow">›</text>
        </view>
        <view class="me-row" @tap="openFavorites">
          <view class="me-row__icon"><view class="me-ico-mark" /></view>
          <text class="me-row__label">收藏的板子</text>
          <text class="nt-arrow">›</text>
        </view>
        <view class="me-row" @tap="openMyPosts">
          <view class="me-row__icon"><view class="me-ico-doc"><view class="me-ico-doc__line" /><view class="me-ico-doc__line" /><view class="me-ico-doc__line" /></view></view>
          <text class="me-row__label">我的帖子</text>
          <text class="nt-arrow">›</text>
        </view>
      </view>
    </view>

    <view v-if="profileEditorVisible" class="me-mask" @tap="profileEditorVisible = false">
      <view class="me-sheet" @tap.stop>
        <view class="me-sheet__handle" />
        <text class="nt-h2">微信头像和昵称</text>
        <text class="nt-meta">保存后，发帖、评论和入座都会用这份资料。</text>
        <view class="me-sheet__avatar-row">
          <view class="me-avatar me-avatar--sm">
            <image v-if="viewState.avatarPreview" class="me-avatar__img" :src="viewState.avatarPreview" mode="aspectFill" />
            <text v-else class="me-avatar__text serif">{{ viewState.profileInitial }}</text>
          </view>
          <button class="nt-btn nt-btn--ghost nt-btn--small me-sheet__choose" open-type="chooseAvatar" @chooseavatar="handleChooseAvatar">选择微信头像</button>
        </view>
        <view class="nt-field">
          <input
            :value="profileForm.nickname"
            type="nickname"
            maxlength="20"
            class="nt-field__input"
            placeholder="输入或选择微信昵称"
            placeholder-class="nt-placeholder"
            @input="handleNicknameInput"
          />
          <text class="nt-meta">{{ profileForm.nickname.trim().length }}/20</text>
        </view>
        <view class="me-sheet__actions">
          <view class="nt-btn nt-btn--ghost me-sheet__half" @tap="profileEditorVisible = false">稍后再说</view>
          <view class="nt-btn nt-btn--gold me-sheet__half" :class="{ 'nt-btn--disabled': profileSaving }" @tap="saveWechatProfile">
            {{ profileSaving ? '保存中…' : '保存' }}
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.me-head {
  padding-top: 16rpx;
  display: flex;
  align-items: center;
  gap: 32rpx;
}

.me-avatar {
  width: 152rpx;
  height: 152rpx;
  border-radius: 50%;
  border: 6rpx solid #3f9a6a;
  box-shadow:
    0 0 0 4rpx var(--ink),
    6rpx 8rpx 0 4rpx var(--ink);
  background: var(--panel-raised);
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  flex-shrink: 0;
}

.me-avatar--sm {
  width: 112rpx;
  height: 112rpx;
}

.me-avatar__img {
  width: 100%;
  height: 100%;
}

.me-avatar__text {
  color: var(--gold);
  font-size: 56rpx;
  font-weight: 900;
}

.me-head__text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.me-name {
  font-size: 48rpx;
  font-weight: 900;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 右上角是微信胶囊，齿轮贴着头像底边放，免得被胶囊挡住 */
.me-gear {
  align-self: flex-end;
  font-size: 36rpx;
}

.me-body {
  margin-top: 40rpx;
}

.me-login {
  padding: 36rpx;
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}

.me-login__title {
  font-size: 40rpx;
  font-weight: 900;
}

.me-art {
  padding: 32rpx;
  display: flex;
  flex-direction: column;
  gap: 24rpx;
}

.me-art__title {
  font-size: 30rpx;
  font-weight: 700;
}

.me-art__scroll {
  white-space: nowrap;
}

.me-art__row {
  display: inline-flex;
  gap: 28rpx;
  padding: 16rpx 8rpx 8rpx;
}

.me-art__item {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12rpx;
  width: 104rpx;
}

.me-art__item--on .me-art__token::after {
  content: '';
  position: absolute;
  left: -14rpx;
  top: -14rpx;
  right: -14rpx;
  bottom: -14rpx;
  border-radius: 50%;
  border: 5rpx solid var(--moon);
}

.me-art__token {
  width: 96rpx;
  height: 96rpx;
}

/* 菜单小图标：纯 CSS 画的卡牌、文档和书签 */
.me-ico-cards {
  position: relative;
  width: 34rpx;
  height: 34rpx;
}

.me-ico-cards::before,
.me-ico-cards::after {
  content: '';
  position: absolute;
  top: 2rpx;
  width: 20rpx;
  height: 28rpx;
  border: 4rpx solid var(--gold);
  border-radius: 5rpx;
  background: var(--panel-raised);
  box-sizing: border-box;
}

.me-ico-cards::before {
  left: 0;
}

.me-ico-cards::after {
  left: 12rpx;
  transform: rotate(14deg);
}

.me-ico-doc {
  width: 28rpx;
  height: 34rpx;
  border: 4rpx solid var(--gold);
  border-radius: 6rpx;
  padding: 6rpx 5rpx;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  justify-content: space-around;
}

.me-ico-doc__line {
  height: 3rpx;
  border-radius: 2rpx;
  background: var(--gold);
}

.me-ico-mark {
  position: relative;
  width: 26rpx;
  height: 34rpx;
  background: var(--gold);
  clip-path: polygon(0 0, 100% 0, 100% 100%, 50% 72%, 0 100%);
}

.me-ico-mark::after {
  content: '';
  position: absolute;
  left: 4rpx;
  top: 4rpx;
  right: 4rpx;
  bottom: 6rpx;
  background: var(--panel-raised);
  clip-path: polygon(0 0, 100% 0, 100% 100%, 50% 66%, 0 100%);
}

.me-ico-moon {
  color: var(--gold);
  font-size: 34rpx;
}

.me-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 20rpx;
}

.me-stat {
  padding: 24rpx;
  border-radius: 28rpx;
  background: var(--panel);
  border: 2rpx solid var(--panel-line);
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.me-stat__num {
  color: var(--gold);
  font-size: 48rpx;
  font-weight: 900;
}

.me-stat__label {
  color: var(--text-2);
  font-size: 24rpx;
}

.me-menu {
  padding: 12rpx 28rpx;
}

.me-row {
  display: flex;
  align-items: center;
  gap: 24rpx;
  min-height: 112rpx;
  border-top: 2rpx solid var(--panel-line);
}

.me-row:first-child {
  border-top: none;
}

.me-row__icon {
  width: 72rpx;
  height: 72rpx;
  border-radius: 20rpx;
  background: var(--panel-raised);
  color: var(--gold);
  font-size: 34rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.me-row__label {
  flex: 1;
  font-size: 30rpx;
}

.me-mask {
  position: fixed;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 60;
  background: rgba(7, 6, 10, 0.7);
  display: flex;
  align-items: flex-end;
}

.me-sheet {
  width: 100%;
  padding: 24rpx 40rpx calc(40rpx + env(safe-area-inset-bottom));
  border-radius: 40rpx 40rpx 0 0;
  background: var(--panel);
  border-top: 4rpx solid var(--ink);
  display: flex;
  flex-direction: column;
  gap: 24rpx;
  box-sizing: border-box;
}

.me-sheet__handle {
  align-self: center;
  width: 80rpx;
  height: 8rpx;
  border-radius: 4rpx;
  background: var(--line);
}

.me-sheet__avatar-row {
  display: flex;
  align-items: center;
  gap: 28rpx;
}

.me-sheet__choose {
  margin: 0;
  line-height: normal;
}

.me-sheet__choose::after {
  border: none;
}

.me-sheet__actions {
  display: flex;
  gap: 20rpx;
}

.me-sheet__half {
  flex: 1;
}
</style>
