<script setup>
// 角色图鉴（原型 D3）：顶部是「夜镇编年史」入口，下面按阵营分区的立绘卡牌墙，三列。没有立绘的角色显示卡背。
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import NavBar from '../../components/NavBar.vue'
import api from '../../services/api'

const FILTERS = [
  { value: 'all', label: '全部' },
  { value: 'god', label: '神职', fill: '#D4A73A' },
  { value: 'villager', label: '平民', fill: '#4FA877' },
  { value: 'wolf', label: '狼人', fill: '#E0403A' },
  { value: 'third', label: '第三方', fill: '#F28CB8' },
]

const SECTIONS = [
  { key: '好人', title: '好人阵营', color: '#E9C46A', fill: '#D4A73A' },
  { key: '狼人', title: '狼人阵营', color: '#FF7468', fill: '#E0403A' },
  { key: '第三方', title: '第三方', color: '#F7A8CB', fill: '#F28CB8' },
]

const roles = ref([])
const filter = ref('all')
const world = ref(null)
const moonScene = '/static/scenes/moon.webp'

function campKey(role) {
  if (role.faction === '狼人') return 'wolf'
  if (role.faction === '第三方') return 'third'
  return role.roleType === '平民' ? 'villager' : 'god'
}

const artCount = computed(() => roles.value.filter((role) => role.fullIllustration).length)
const sections = computed(() => {
  const visible = filter.value === 'all' ? roles.value : roles.value.filter((role) => campKey(role) === filter.value)
  return SECTIONS.map((section) => ({ ...section, items: visible.filter((role) => role.faction === section.key) })).filter(
    (section) => section.items.length,
  )
})

async function loadRoles() {
  try {
    roles.value = await api.getRoles()
  } catch (error) {
    uni.showToast({ title: '角色加载失败', icon: 'none' })
  }
}

async function loadWorld() {
  try {
    const data = await api.getWorld()
    world.value = data.townName || data.events.length ? data : null
  } catch (error) {
    world.value = null
  }
}

function openWorld() {
  uni.navigateTo({ url: '/pages/world/index' })
}

function openRole(id) {
  uni.navigateTo({ url: `/pages/roles/detail?id=${id}` })
}

onLoad(() => {
  loadRoles()
  loadWorld()
})
</script>

<template>
  <view class="nt-page">
    <NavBar back />

    <view class="nt-gutter rl-head">
      <text class="nt-title">角色图鉴</text>
      <text class="nt-meta">{{ roles.length }} 个角色 · {{ artCount }} 张已上立绘</text>
    </view>

    <view v-if="world" class="nt-gutter">
      <view class="nt-ink rl-world" @tap="openWorld">
        <image class="rl-world__bg" :src="world.coverImage || moonScene" mode="aspectFill" webp />
        <view class="rl-world__shade" />
        <view class="rl-world__text">
          <text class="rl-world__eyebrow">夜镇编年史</text>
          <text class="rl-world__title serif">{{ world.townName || '夜镇' }}</text>
          <text class="rl-world__tagline">{{ world.tagline || '银钟响过，不出家门。' }}</text>
        </view>
        <view class="rl-world__go">
          <text>翻开</text>
          <text class="rl-world__arrow">›</text>
        </view>
      </view>
    </view>

    <scroll-view scroll-x class="nt-chips rl-chips" :show-scrollbar="false">
      <view class="nt-chips__inner">
        <view
          v-for="item in FILTERS"
          :key="item.value"
          class="nt-chip"
          :class="{ 'nt-chip--on': filter === item.value }"
          @tap="filter = item.value"
        >
          <view v-if="item.fill" class="nt-dot" :style="{ backgroundColor: item.fill }" />
          <text>{{ item.label }}</text>
        </view>
      </view>
    </scroll-view>

    <view class="nt-gutter">
      <view v-for="section in sections" :key="section.key" class="rl-section">
        <view class="rl-section__title">
          <view class="nt-dot" :style="{ backgroundColor: section.fill }" />
          <text class="serif" :style="{ color: section.color }">{{ section.title }}</text>
          <text class="rl-section__count">{{ section.items.length }}</text>
        </view>
        <view class="rl-grid">
          <view v-for="item in section.items" :key="item.id" class="nt-card" @tap="openRole(item.id)">
            <view class="nt-card__frame" :style="{ backgroundColor: item.fullIllustration || item.portrait ? item.accent : '' }">
              <image
                v-if="item.fullIllustration || item.portrait"
                class="nt-card__img"
                :src="item.fullIllustration || item.portrait"
                mode="aspectFill"
                webp
              />
              <view v-else class="nt-card__back">
                <view class="nt-card__line" />
                <text class="nt-card__moon">☾</text>
              </view>
            </view>
            <view class="nt-card__plate">
              <text class="nt-card__name" :class="{ 'nt-card__name--muted': !(item.fullIllustration || item.portrait) }">{{ item.name }}</text>
              <view class="nt-dot" :style="{ backgroundColor: item.campFill }" />
            </view>
          </view>
        </view>
      </view>

      <view v-if="roles.length && !sections.length" class="nt-empty">这个阵营下还没有角色。</view>
      <text v-if="roles.length" class="rl-note">还没画好的角色显示卡背，名字照常可点，详情页用阵营色代替立绘底色。</text>
    </view>
  </view>
</template>

<style scoped lang="scss">
.rl-head {
  padding-top: 8rpx;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16rpx;
}

.rl-chips {
  margin-top: 32rpx;
}

.rl-section {
  margin-top: 44rpx;
}

.rl-section__title {
  display: flex;
  align-items: center;
  gap: 16rpx;
  font-size: 34rpx;
  font-weight: 900;
}

.rl-section__count {
  color: var(--text-3);
  font-size: 24rpx;
  font-weight: 400;
}

.rl-grid {
  margin-top: 24rpx;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 28rpx 24rpx;
}

.rl-note {
  display: block;
  margin-top: 36rpx;
  color: var(--text-3);
  font-size: 26rpx;
  line-height: 1.7;
}
.rl-world {
  position: relative;
  height: 220rpx;
  margin-bottom: 8rpx;
  overflow: hidden;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  padding: 28rpx 32rpx;
  box-sizing: border-box;
}

.rl-world__bg {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
}

.rl-world__shade {
  position: absolute;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  background: linear-gradient(90deg, rgba(14, 12, 19, 0.92) 0%, rgba(14, 12, 19, 0.55) 60%, rgba(14, 12, 19, 0.2) 100%);
}

.rl-world__text {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}

.rl-world__eyebrow {
  color: var(--gold);
  font-size: 22rpx;
  font-weight: 700;
  letter-spacing: 8rpx;
}

.rl-world__title {
  color: var(--text);
  font-size: 48rpx;
  font-weight: 900;
}

.rl-world__tagline {
  color: var(--text-soft);
  font-size: 26rpx;
}

.rl-world__go {
  position: relative;
  display: flex;
  align-items: center;
  gap: 6rpx;
  height: 64rpx;
  padding: 0 24rpx;
  border-radius: 20rpx;
  border: 4rpx solid var(--ink);
  background: var(--gold);
  color: var(--ink);
  font-size: 26rpx;
  font-weight: 900;
  box-sizing: border-box;
}

.rl-world__arrow {
  font-size: 34rpx;
  line-height: 1;
}
</style>
