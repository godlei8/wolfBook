<script setup>
// 夜镇编年史：世界观长页。顶部月夜大图 + 小镇名，下面依次是小镇简介、三方势力、编年史时间线；
// 时间线每段下面是出场角色的头像，点了进角色详情。内容在后台「世界观」里编辑。
import { computed, ref } from 'vue'
import { onLoad, onShareAppMessage } from '@dcloudio/uni-app'
import NavBar from '../../components/NavBar.vue'
import api from '../../services/api'
import { openAssistant } from '../../utils/assistant-entry'
import { getNavInsets, rpxToPx } from '../../utils/nav'

const insets = getNavInsets()
const world = ref(null)
const failed = ref(false)
const moonScene = '/static/scenes/moon.webp'
const heroHeight = computed(() => `${Math.round(insets.top + rpxToPx(520))}px`)
const askQuestion = '夜镇这几百年里发生过什么？'

async function load() {
  failed.value = false
  try {
    world.value = await api.getWorld()
  } catch (error) {
    failed.value = true
  }
}

// 时间线头像下只放得下名，「巴纳比·斯陶特」显示「巴纳比」，「本·贝克 & 梅·索恩」显示「本 & 梅」
function shortName(role) {
  if (!role.alias) return role.name
  return role.alias
    .split('&')
    .map((part) => part.split('·')[0].trim())
    .filter(Boolean)
    .join(' & ')
}

function openRole(role) {
  if (role?.roleId) uni.navigateTo({ url: `/pages/roles/detail?id=${role.roleId}` })
}

function openRoles() {
  const pages = getCurrentPages()
  const previous = pages.length > 1 ? pages[pages.length - 2] : null
  if (previous && previous.route === 'pages/roles/list') uni.navigateBack()
  else uni.navigateTo({ url: '/pages/roles/list' })
}

function ask() {
  openAssistant('world', { page: 'world/index' }, askQuestion)
}

onLoad(load)

onShareAppMessage(() => ({
  title: world.value?.tagline ? `夜镇编年史 · ${world.value.tagline}` : '夜镇编年史',
  path: '/pages/world/index',
}))
</script>

<template>
  <view class="nt-page wd-page">
    <NavBar back overlay />

    <view class="wd-hero" :style="{ height: heroHeight }">
      <image class="wd-hero__img" :src="(world && world.coverImage) || moonScene" mode="aspectFill" webp />
      <view class="wd-hero__shade" />
      <view class="wd-hero__text nt-gutter">
        <text class="wd-eyebrow">夜镇编年史</text>
        <text class="wd-hero__title serif">{{ (world && world.townName) || '夜镇' }}</text>
        <text v-if="world && world.tagline" class="wd-hero__tagline serif">{{ world.tagline }}</text>
      </view>
    </view>

    <view v-if="failed" class="nt-gutter wd-empty">
      <text>编年史没翻开，检查一下网络</text>
      <view class="nt-btn nt-btn--ghost nt-btn--small" @tap="load">再试一次</view>
    </view>

    <view v-else-if="world" class="nt-gutter nt-stack wd-body">
      <view v-if="world.introParagraphs.length" class="nt-panel">
        <text class="nt-label">小镇</text>
        <text v-for="(line, index) in world.introParagraphs" :key="index" class="wd-intro serif">{{ line }}</text>
      </view>

      <view v-if="world.factions.length" class="wd-section">
        <text class="nt-h2">三方势力</text>
        <view v-for="faction in world.factions" :key="faction.name" class="nt-ink wd-faction" :style="{ borderLeftColor: faction.color }">
          <view class="wd-faction__head">
            <text class="wd-faction__name serif">{{ faction.name }}</text>
            <text class="nt-tag" :style="{ color: faction.color }">{{ faction.campLabel }}</text>
          </view>
          <text v-if="faction.description" class="wd-faction__desc">{{ faction.description }}</text>
        </view>
      </view>

      <view v-if="world.events.length" class="wd-section">
        <text class="nt-h2">编年史</text>
        <view class="wd-timeline">
          <view v-for="(event, index) in world.events" :key="index" class="wd-event">
            <view class="wd-event__dot" :class="{ 'wd-event__dot--now': index === world.events.length - 1 }" />
            <text v-if="event.era" class="wd-event__era">{{ event.era }}</text>
            <text class="wd-event__title serif">{{ event.title }}</text>
            <text v-if="event.content" class="wd-event__content">{{ event.content }}</text>
            <view v-if="event.roles.length" class="wd-cast">
              <view v-for="role in event.roles" :key="role.roleId" class="wd-cast__item" @tap="openRole(role)">
                <view class="nt-token wd-cast__token">
                  <image v-if="role.portrait || role.fullIllustration" class="nt-token__img" :src="role.portrait || role.fullIllustration" mode="aspectFill" :style="{ borderColor: role.accent }" webp />
                  <view v-else class="nt-token__text" :style="{ borderColor: role.accent }">{{ role.name.slice(0, 1) }}</view>
                </view>
                <text class="wd-cast__name">{{ shortName(role) }}</text>
                <text v-if="role.alias" class="wd-cast__role">{{ role.name }}</text>
              </view>
            </view>
          </view>
        </view>
      </view>

      <view class="nt-qchip" @tap="ask">
        <text>{{ askQuestion }}</text>
        <text class="nt-qchip__ask">问 AI</text>
      </view>

      <view class="nt-btn nt-btn--gold nt-btn--block" @tap="openRoles">去图鉴认识他们</view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.wd-page {
  padding-bottom: calc(env(safe-area-inset-bottom) + 60rpx);
}

.wd-hero {
  position: relative;
  overflow: hidden;
  border-bottom: 8rpx solid var(--ink);
}

.wd-hero__img {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
}

.wd-hero__shade {
  position: absolute;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  background: linear-gradient(180deg, rgba(21, 18, 28, 0.1) 30%, rgba(21, 18, 28, 0.94) 100%);
}

.wd-hero__text {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 40rpx;
  display: flex;
  flex-direction: column;
  gap: 10rpx;
}

.wd-eyebrow {
  color: var(--gold);
  font-size: 24rpx;
  font-weight: 700;
  letter-spacing: 10rpx;
}

.wd-hero__title {
  color: var(--text);
  font-size: 84rpx;
  font-weight: 900;
  line-height: 1.1;
}

.wd-hero__tagline {
  color: var(--text-soft);
  font-size: 32rpx;
}

.wd-empty {
  margin-top: 80rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 24rpx;
  color: var(--text-3);
  font-size: 28rpx;
}

.wd-body {
  margin-top: 36rpx;
}

.wd-intro {
  display: block;
  color: var(--text-soft);
  font-size: 32rpx;
  line-height: 1.9;
}

.wd-section {
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}

.wd-faction {
  padding: 28rpx 32rpx;
  border-left-width: 12rpx;
  display: flex;
  flex-direction: column;
  gap: 14rpx;
}

.wd-faction__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}

.wd-faction__name {
  font-size: 40rpx;
  font-weight: 900;
}

.wd-faction__desc {
  color: var(--text-soft);
  font-size: 28rpx;
  line-height: 1.8;
}

.wd-timeline {
  margin-left: 14rpx;
  padding-left: 40rpx;
  border-left: 4rpx solid var(--line);
  display: flex;
  flex-direction: column;
  gap: 44rpx;
}

.wd-event {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 10rpx;
}

.wd-event__dot {
  position: absolute;
  left: -58rpx;
  top: 6rpx;
  width: 28rpx;
  height: 28rpx;
  border-radius: 50%;
  border: 6rpx solid var(--ink);
  background: var(--gold);
  box-sizing: border-box;
}

.wd-event__dot--now {
  background: var(--camp-wolf);
}

.wd-event__era {
  color: var(--gold);
  font-size: 24rpx;
  font-weight: 700;
  letter-spacing: 4rpx;
}

.wd-event__title {
  font-size: 40rpx;
  font-weight: 900;
}

.wd-event__content {
  color: var(--text-soft);
  font-size: 28rpx;
  line-height: 1.85;
}

.wd-cast {
  margin-top: 10rpx;
  display: flex;
  flex-wrap: wrap;
  gap: 24rpx;
}

.wd-cast__item {
  width: 120rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6rpx;
}

.wd-cast__token {
  width: 88rpx;
  height: 88rpx;
}

.wd-cast__name {
  max-width: 120rpx;
  color: var(--text);
  font-size: 22rpx;
  font-weight: 700;
  text-align: center;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.wd-cast__role {
  color: var(--text-3);
  font-size: 20rpx;
}
</style>
