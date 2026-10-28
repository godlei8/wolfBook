<script setup>
// 板子库首页（原型 D1）。
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onReachBottom, onShow } from '@dcloudio/uni-app'
import BoardCard from '../../components/BoardCard.vue'
import NavBar from '../../components/NavBar.vue'
import api from '../../services/api'
import storage from '../../services/storage'
import { openAssistant } from '../../utils/assistant-entry'
import { syncTabBar } from '../../utils/tabbar'
import { hasAuthToken } from '../../utils/auth'
import { getJudgePhaseLabel } from '../../utils/judge/meta'

const PAGE_SIZE = 20
const FEATURED_LIMIT = 5

// 原型的快捷筛选：一行，单选
const QUICK_FILTERS = [
  { key: 'all', label: '全部', params: {} },
  { key: 'p9', label: '9 人', params: { playerCount: '9' } },
  { key: 'p12', label: '12 人', params: { playerCount: '12' } },
  { key: 'newbie', label: '新手友好', params: { difficulty: '入门' } },
  { key: 'advanced', label: '进阶', params: { difficulty: '进阶' } },
  { key: 'brain', label: '烧脑', params: { difficulty: '烧脑' } },
]

// 推荐卡里五张立绘的扇形位置（卡片内宽约 662rpx，中线 331rpx）
const FAN_SLOTS = {
  l2: 'left: 30rpx; top: 140rpx; width: 168rpx; transform: rotate(-15deg); z-index: 1;',
  l1: 'left: 120rpx; top: 96rpx; width: 180rpx; transform: rotate(-7deg); z-index: 2;',
  c: 'left: 231rpx; top: 60rpx; width: 200rpx; z-index: 3;',
  r1: 'left: 362rpx; top: 96rpx; width: 180rpx; transform: rotate(7deg); z-index: 2;',
  r2: 'left: 464rpx; top: 140rpx; width: 168rpx; transform: rotate(15deg); z-index: 1;',
}

const boards = ref([])
const total = ref(0)
const page = ref(1)
const hasMore = ref(false)
const loadingMore = ref(false)
const keyword = ref('')
const quickKey = ref('all')
const featuredList = ref([])
const featuredIndex = ref(0)
const activeRoom = ref(null)
const avatar = ref('')

const quickParams = computed(() => (QUICK_FILTERS.find((item) => item.key === quickKey.value) || QUICK_FILTERS[0]).params)
const activeRoomText = computed(() => {
  const room = activeRoom.value
  if (!room) return ''
  const day = room.currentPhase === 'night_action' ? `第 ${room.currentDay} 夜` : `第 ${room.currentDay} 天`
  return `${room.boardName} · ${day} · ${getJudgePhaseLabel(room.currentPhase)}`
})

// 模板里 @confirm 会把事件对象传进来，所以只认显式的 { append: true }
async function loadBoards(options = {}) {
  const append = options.append === true
  if (append && (loadingMore.value || !hasMore.value)) return
  const nextPage = append ? page.value + 1 : 1
  if (append) loadingMore.value = true
  try {
    const data = await api.getBoards({ page: nextPage, size: PAGE_SIZE, keyword: keyword.value.trim(), ...quickParams.value })
    const list = data.list || []
    boards.value = append ? boards.value.concat(list) : list
    total.value = data.total || 0
    page.value = nextPage
    hasMore.value = list.length > 0 && boards.value.length < total.value
    // 推荐走马灯：每次不带筛选重新加载时更新（筛选时保持不变），配了背景图的板子排前面
    if (!append && quickKey.value === 'all' && !keyword.value.trim() && list.length) {
      const withBackground = list.filter((item) => item.backgroundImage)
      const rest = list.filter((item) => !item.backgroundImage)
      featuredList.value = withBackground.concat(rest).slice(0, FEATURED_LIMIT)
      featuredIndex.value = 0
    }
  } catch (error) {
    uni.showToast({ title: error?.message || '板子加载失败', icon: 'none' })
  } finally {
    loadingMore.value = false
  }
}

async function loadActiveRoom() {
  if (!hasAuthToken()) {
    activeRoom.value = null
    return
  }
  try {
    const rooms = await api.getJudgeRecentRooms()
    activeRoom.value = rooms.find((room) => room.roomStatus === 'playing' && (room.owned || room.joined)) || null
  } catch (error) {
    activeRoom.value = null
  }
}

function applyQuick(key) {
  if (quickKey.value === key) return
  quickKey.value = key
  loadBoards()
}

function search() {
  loadBoards()
}

function openDetail(id) {
  uni.navigateTo({ url: `/pages/boards/detail?id=${id}` })
}

function onFeaturedChange(event) {
  featuredIndex.value = event.detail.current
}

function openRoles() {
  uni.navigateTo({ url: '/pages/roles/list' })
}

function openMe() {
  uni.switchTab({ url: '/pages/user/index' })
}

function openRoom() {
  if (!activeRoom.value) return
  uni.navigateTo({ url: `/pages/judge/room?roomId=${activeRoom.value.roomId}` })
}

function askAi() {
  openAssistant('boards_list', { page: 'boards/list', keyword: keyword.value }, keyword.value.trim())
}

onLoad(() => {
  loadBoards()
})

onShow(() => {
  syncTabBar()
  avatar.value = storage.getUserProfile()?.avatar || ''
  loadActiveRoom()
})

onPullDownRefresh(async () => {
  await Promise.all([loadBoards(), loadActiveRoom()])
  uni.stopPullDownRefresh()
})

onReachBottom(() => loadBoards({ append: true }))
</script>

<template>
  <view class="nt-page nt-page--tab">
    <NavBar wordmark="WOLFBOOK" />

    <view class="nt-gutter home-head">
      <text class="nt-title">今晚开哪一局？</text>
      <view class="home-avatar" @tap="openMe">
        <image v-if="avatar" class="home-avatar__img" :src="avatar" mode="aspectFill" />
        <text v-else class="home-avatar__moon">☾</text>
      </view>
    </view>

    <view class="nt-gutter">
      <view class="home-search">
        <view class="home-search__icon" />
        <input
          v-model="keyword"
          class="home-search__input"
          placeholder="搜板子、角色，或直接问规则"
          placeholder-class="nt-placeholder"
          confirm-type="search"
          @confirm="search"
        />
        <view class="nt-btn nt-btn--gold home-search__ask" @tap="askAi">问 AI</view>
      </view>

      <view v-if="activeRoom" class="home-live" @tap="openRoom">
        <view class="home-live__badge">☾</view>
        <view class="home-live__text">
          <text class="home-live__title">{{ activeRoom.owned ? '你主持的对局进行中' : '你参与的对局进行中' }}</text>
          <text class="home-live__meta">{{ activeRoomText }}</text>
        </view>
        <text class="home-live__go">回房间 ›</text>
      </view>

    </view>

    <view v-if="featuredList.length" class="home-feature-wrap">
      <swiper
        class="home-swiper"
        circular
        :autoplay="featuredList.length > 1"
        :interval="5000"
        :duration="400"
        :current="featuredIndex"
        @change="onFeaturedChange"
      >
        <swiper-item v-for="item in featuredList" :key="item.id" class="home-swiper__item">
          <view class="nt-ink home-feature" @tap="openDetail(item.id)">
            <image v-if="item.backgroundImage" class="home-feature__bg" :src="item.backgroundImage" mode="aspectFill" webp />
            <view v-else class="home-feature__moon" />
            <view
              v-for="art in item.heroArt || []"
              :key="art.roleId"
              class="nt-card home-feature__card"
              :style="FAN_SLOTS[art.slot] || FAN_SLOTS.c"
            >
              <view class="nt-card__frame" :style="{ backgroundColor: art.accent }">
                <image class="nt-card__img" :src="art.image" mode="aspectFill" webp />
              </view>
            </view>
            <view class="home-feature__plate">
              <view class="home-feature__left">
                <text class="home-feature__eyebrow">今晚推荐</text>
                <text class="home-feature__name serif">{{ item.name }}</text>
              </view>
              <view class="home-feature__right">
                <text>{{ item.playerCount }} 人 · {{ item.difficulty }}</text>
                <text>{{ item.winCondition || item.tags?.[0] || '' }}</text>
              </view>
            </view>
          </view>
        </swiper-item>
      </swiper>
      <view v-if="featuredList.length > 1" class="home-dots">
        <view
          v-for="(item, index) in featuredList"
          :key="item.id"
          class="home-dots__dot"
          :class="{ 'home-dots__dot--on': index === featuredIndex }"
        />
      </view>
    </view>

    <scroll-view scroll-x class="nt-chips home-chips" :show-scrollbar="false">
      <view class="nt-chips__inner">
        <view
          v-for="item in QUICK_FILTERS"
          :key="item.key"
          class="nt-chip"
          :class="{ 'nt-chip--on': quickKey === item.key }"
          @tap="applyQuick(item.key)"
        >
          {{ item.label }}
        </view>
      </view>
    </scroll-view>

    <view class="nt-gutter">
      <view class="home-section">
        <text class="nt-h2">全部板子</text>
        <text class="home-section__link" @tap="openRoles">角色图鉴</text>
      </view>

      <view v-if="boards.length === 0" class="nt-empty">当前条件下没有找到板子，换一个试试。</view>

      <view class="home-list">
        <BoardCard v-for="board in boards" :key="board.id" :board="board" @select="openDetail" />
      </view>

      <view v-if="boards.length" class="nt-footer">
        {{ loadingMore ? '加载中…' : hasMore ? '上拉加载更多' : `共 ${total} 个板子` }}
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.home-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24rpx;
  padding-top: 8rpx;
}

.home-avatar {
  width: 88rpx;
  height: 88rpx;
  border-radius: 50%;
  border: 5rpx solid var(--camp-villager);
  box-shadow:
    0 0 0 4rpx var(--ink),
    4rpx 6rpx 0 4rpx var(--ink);
  background: var(--panel-raised);
  overflow: hidden;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
}

.home-avatar__img {
  width: 100%;
  height: 100%;
}

.home-avatar__moon {
  color: var(--gold);
  font-size: 40rpx;
}

.home-search {
  margin-top: 32rpx;
  height: 100rpx;
  padding: 0 10rpx 0 28rpx;
  border-radius: 32rpx;
  background: var(--panel);
  border: 4rpx solid var(--ink);
  box-shadow: 6rpx 6rpx 0 var(--ink);
  display: flex;
  align-items: center;
  gap: 20rpx;
  box-sizing: border-box;
}

/* 放大镜：一个圈 + 一根柄 */
.home-search__icon {
  position: relative;
  width: 26rpx;
  height: 26rpx;
  border: 4rpx solid var(--text-2);
  border-radius: 50%;
  flex-shrink: 0;
  margin-right: 6rpx;
}

.home-search__icon::after {
  content: '';
  position: absolute;
  right: -10rpx;
  bottom: -8rpx;
  width: 12rpx;
  height: 4rpx;
  background: var(--text-2);
  border-radius: 2rpx;
  transform: rotate(45deg);
}

.home-search__input {
  flex: 1;
  min-width: 0;
  height: 80rpx;
  color: var(--text);
  font-size: 30rpx;
}

.home-search__ask {
  height: 76rpx;
  padding: 0 28rpx;
  border-radius: 24rpx;
  font-size: 28rpx;
  box-shadow: 4rpx 4rpx 0 var(--ink);
}

.home-live {
  margin-top: 32rpx;
  padding: 24rpx 28rpx;
  border-radius: 32rpx;
  background: var(--panel-raised);
  border: 4rpx solid var(--ink);
  box-shadow: 6rpx 6rpx 0 var(--ink);
  display: flex;
  align-items: center;
  gap: 24rpx;
}

.home-live__badge {
  width: 72rpx;
  height: 72rpx;
  border-radius: 50%;
  background: var(--gold);
  border: 4rpx solid var(--ink);
  color: var(--ink);
  font-size: 36rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  flex-shrink: 0;
}

.home-live__text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.home-live__title {
  font-size: 30rpx;
  font-weight: 700;
}

.home-live__meta {
  color: var(--text-2);
  font-size: 26rpx;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.home-live__go {
  color: var(--moon);
  font-size: 28rpx;
  font-weight: 700;
  flex-shrink: 0;
}

.home-feature-wrap {
  margin-top: 40rpx;
}

/* 走马灯铺满屏宽，每一页自己留出页边距；底部和右边多留一点，卡片的硬投影才不会被 swiper 裁掉 */
.home-swiper {
  height: 612rpx;
}

.home-swiper__item {
  padding: 0 40rpx 12rpx;
  box-sizing: border-box;
}

.home-feature {
  position: relative;
  height: 600rpx;
  border-radius: 40rpx;
  overflow: hidden;
}

.home-dots {
  margin-top: 16rpx;
  display: flex;
  justify-content: center;
  gap: 12rpx;
}

.home-dots__dot {
  width: 14rpx;
  height: 14rpx;
  border-radius: 7rpx;
  background: var(--line);
  transition: width 0.3s;
}

.home-dots__dot--on {
  width: 40rpx;
  background: var(--gold);
}

.home-feature__bg {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
}

.home-feature__moon {
  position: absolute;
  right: -80rpx;
  top: -100rpx;
  width: 380rpx;
  height: 380rpx;
  border-radius: 50%;
  background: #2e2740;
}

.home-feature__card {
  position: absolute;
}

.home-feature__plate {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 5;
  padding: 26rpx 36rpx;
  background: var(--ink);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24rpx;
}

.home-feature__left {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
  min-width: 0;
}

.home-feature__eyebrow {
  color: var(--gold);
  font-size: 24rpx;
  font-weight: 700;
  letter-spacing: 2rpx;
}

.home-feature__name {
  font-size: 44rpx;
  font-weight: 900;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.home-feature__right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  color: var(--text-2);
  font-size: 26rpx;
  line-height: 1.5;
  flex-shrink: 0;
}

.home-chips {
  margin-top: 36rpx;
}

.home-section {
  margin-top: 36rpx;
  margin-bottom: 24rpx;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.home-section__link {
  color: var(--moon);
  font-size: 28rpx;
  font-weight: 700;
}

.home-list {
  display: flex;
  flex-direction: column;
  gap: 32rpx;
}
</style>
