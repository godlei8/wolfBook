<script setup>
// 角色图鉴（原型 D3）：按阵营分区的立绘卡牌墙，三列；顶部按阵营筛选。没有立绘的角色显示卡背。
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import AssistantDock from '../../components/assistant/AssistantDock.vue'
import RoleArtCard from '../../components/RoleArtCard.vue'
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
  <view class="page-shell">
    <view class="hero-wordmark">CODEX</view>
    <view class="roles-heading">
      <view class="hero-title">角色图鉴</view>
      <text class="roles-count">共 {{ roles.length }} 个角色</text>
    </view>

    <scroll-view scroll-x class="chip-row roles-filters">
      <view
        v-for="item in FILTERS"
        :key="item.value"
        class="chip"
        :class="{ active: filter === item.value }"
        @tap="filter = item.value"
      >
        <view v-if="item.fill" class="filter-dot" :style="{ backgroundColor: item.fill }" />
        {{ item.label }}
      </view>
    </scroll-view>

    <view v-for="section in sections" :key="section.key" class="roles-section">
      <view class="roles-section-title">
        <view class="filter-dot" :style="{ backgroundColor: section.fill }" />
        <text class="serif" :style="{ color: section.color }">{{ section.title }}</text>
        <text class="roles-section-count">{{ section.items.length }}</text>
      </view>
      <view class="roles-grid">
        <view v-for="item in section.items" :key="item.id" @tap="openRole(item.id)">
          <RoleArtCard
            :image="item.fullIllustration || item.portrait"
            :accent="item.accent"
            :name="item.name"
            :camp-fill="item.campFill"
          />
        </view>
      </view>
    </view>

    <view v-if="roles.length && !sections.length" class="empty-state">这个阵营下还没有角色。</view>

    <AssistantDock scene="roles_list" :page-context="{ page: 'roles/list', filter }" />
  </view>
</template>

<style scoped lang="scss">
.hero-wordmark {
  color: var(--gold);
  font-family: var(--serif);
  font-size: 24rpx;
  font-weight: 700;
  letter-spacing: 8rpx;
  margin-bottom: 8rpx;
}

.roles-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16rpx;
}

.roles-count {
  color: var(--text-2);
  font-size: 24rpx;
  flex-shrink: 0;
}

.roles-filters {
  margin-top: 28rpx;
  padding: 4rpx 4rpx 14rpx;
  box-sizing: border-box;
}

.chip {
  gap: 10rpx;
}

.filter-dot {
  width: 16rpx;
  height: 16rpx;
  border-radius: 50%;
  flex-shrink: 0;
}

.roles-section {
  margin-top: 40rpx;
}

.roles-section-title {
  display: flex;
  align-items: center;
  gap: 14rpx;
  font-size: 34rpx;
  font-weight: 900;
}

.roles-section-count {
  color: var(--text-3);
  font-size: 24rpx;
  font-weight: 400;
}

.roles-grid {
  margin-top: 24rpx;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 28rpx 24rpx;
}
</style>
