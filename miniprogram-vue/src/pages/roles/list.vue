<script setup>
// 角色图鉴（原型 D3，tab 页）：按阵营分区的立绘卡牌墙，三列。没有立绘的角色显示卡背。
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

function openRole(id) {
  uni.navigateTo({ url: `/pages/roles/detail?id=${id}` })
}

onLoad(loadRoles)
</script>

<template>
  <view class="nt-page">
    <NavBar back />

    <view class="nt-gutter rl-head">
      <text class="nt-title">角色图鉴</text>
      <text class="nt-meta">{{ roles.length }} 个角色 · {{ artCount }} 张已上立绘</text>
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
</style>
