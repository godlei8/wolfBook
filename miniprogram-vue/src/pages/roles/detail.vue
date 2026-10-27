<script setup>
// 角色详情（原型 D4）：顶部整幅立绘（底色 = 主题色），名字卡压在立绘下沿；
// 下面是技能、人物设定、出现的板子、常见问题，最底下切换上一个 / 下一个角色。
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import AssistantDock from '../../components/assistant/AssistantDock.vue'
import RoleToken from '../../components/RoleToken.vue'
import api from '../../services/api'
import { withAlpha } from '../../utils/board-rules.js'

const role = ref(null)
const allRoles = ref([])
const openFaqs = ref({ 0: true })

const position = computed(() => {
  if (!role.value) return -1
  return allRoles.value.findIndex((item) => item.id === role.value.id)
})
const prevRole = computed(() => (position.value > 0 ? allRoles.value[position.value - 1] : null))
const nextRole = computed(() =>
  position.value >= 0 && position.value < allRoles.value.length - 1 ? allRoles.value[position.value + 1] : null,
)
const storyStyle = computed(() =>
  role.value ? { backgroundColor: withAlpha(role.value.accent, 0.16), borderColor: withAlpha(role.value.accent, 0.45) } : {},
)

async function loadRole(id) {
  try {
    role.value = await api.getRoleDetail(id)
  } catch (error) {
    uni.showToast({ title: '角色加载失败', icon: 'none' })
  }
}

async function loadAllRoles() {
  try {
    allRoles.value = await api.getRoles()
  } catch (error) {
    allRoles.value = []
  }
}

function toggleFaq(index) {
  openFaqs.value = { ...openFaqs.value, [index]: !openFaqs.value[index] }
}

function openBoard(id) {
  uni.navigateTo({ url: `/pages/boards/detail?id=${id}` })
}

// 左右切换不叠页面栈
function switchRole(target) {
  if (!target) return
  uni.redirectTo({ url: `/pages/roles/detail?id=${target.id}` })
}

onLoad((options) => {
  loadRole(Number(options?.id || 0))
  loadAllRoles()
})
</script>

<template>
  <view v-if="role" class="role-page">
    <view class="role-hero" :style="{ backgroundColor: role.accent }">
      <image
        v-if="role.fullIllustration || role.portrait"
        class="role-hero-image"
        :src="role.fullIllustration || role.portrait"
        mode="aspectFill"
        webp
      />
      <text v-if="position >= 0" class="role-hero-index">{{ position + 1 }} / {{ allRoles.length }}</text>
    </view>

    <view class="page-shell role-body">
      <view class="name-card ink-card">
        <view class="name-row">
          <view class="name-text">
            <view class="role-name serif">{{ role.name }}</view>
            <view v-if="role.alias" class="role-alias">又名 {{ role.alias }}</view>
          </view>
          <view class="role-swatch" :style="{ backgroundColor: role.accent }" />
        </view>
        <view class="role-tags">
          <text class="role-tag" :style="{ color: role.campColor }">{{ role.faction }}阵营</text>
          <text v-if="role.roleType && role.roleType !== role.faction" class="role-tag">{{ role.roleType }}</text>
        </view>
      </view>

      <view class="glass-card panel">
        <text class="panel-label">技能</text>
        <text class="skill-text serif">{{ role.skill }}</text>
      </view>

      <view v-if="role.background" class="glass-card panel" :style="storyStyle">
        <text class="panel-label">人物设定</text>
        <text class="story-text serif">{{ role.background }}</text>
      </view>

      <view v-if="role.boards && role.boards.length" class="glass-card panel">
        <view class="panel-head">
          <text class="section-title">出现在这些板子</text>
          <text class="section-meta">共 {{ role.boards.length }} 个</text>
        </view>
        <view v-for="board in role.boards" :key="board.id" class="board-row" @tap="openBoard(board.id)">
          <view class="board-row-text">
            <text class="board-row-name">{{ board.name }}</text>
            <text class="board-row-meta">{{ board.playerCount }} 人</text>
          </view>
          <text class="board-row-arrow">›</text>
        </view>
      </view>

      <view v-if="role.faqs && role.faqs.length" class="glass-card panel panel--tight">
        <text class="section-title">常见问题</text>
        <view v-for="(faq, index) in role.faqs" :key="faq.question" class="fold">
          <view class="fold-head" @tap="toggleFaq(index)">
            <text class="fold-question">{{ faq.question }}</text>
            <text class="fold-arrow" :class="{ open: openFaqs[index] }">›</text>
          </view>
          <view v-if="openFaqs[index]" class="fold-answer">{{ faq.answer }}</view>
        </view>
      </view>

      <view v-if="prevRole || nextRole" class="switcher">
        <view class="switch-card ink-card" :class="{ 'switch-card--empty': !prevRole }" @tap="switchRole(prevRole)">
          <template v-if="prevRole">
            <text class="switch-arrow">‹</text>
            <RoleToken :image="prevRole.portrait" :accent="prevRole.accent" :size="64" />
            <text class="switch-name">{{ prevRole.name }}</text>
          </template>
        </view>
        <view class="switch-card switch-card--next ink-card" :class="{ 'switch-card--empty': !nextRole }" @tap="switchRole(nextRole)">
          <template v-if="nextRole">
            <text class="switch-name">{{ nextRole.name }}</text>
            <RoleToken :image="nextRole.portrait" :accent="nextRole.accent" :size="64" />
            <text class="switch-arrow">›</text>
          </template>
        </view>
      </view>
    </view>

    <AssistantDock
      scene="role_detail"
      :page-context="{ page: 'roles/detail', roleId: role.id, roleName: role.name, faction: role.faction, roleType: role.roleType }"
    />
  </view>
</template>

<style scoped lang="scss">
.role-hero {
  position: relative;
  height: 880rpx;
  border-bottom: 8rpx solid var(--ink);
  overflow: hidden;
}

.role-hero-image {
  width: 100%;
  height: 100%;
  display: block;
}

.role-hero-index {
  position: absolute;
  left: 28rpx;
  top: 28rpx;
  padding: 8rpx 20rpx;
  border-radius: 16rpx;
  background: var(--ink);
  color: var(--text);
  font-family: var(--serif);
  font-size: 24rpx;
  font-weight: 700;
  letter-spacing: 2rpx;
}

.role-body {
  padding-top: 0;
}

.name-card {
  position: relative;
  z-index: 2;
  margin-top: -96rpx;
  padding: 32rpx;
  display: flex;
  flex-direction: column;
  gap: 18rpx;
}

.name-row {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20rpx;
}

.name-text {
  min-width: 0;
}

.role-name {
  font-size: 72rpx;
  font-weight: 900;
  line-height: 1.1;
}

.role-alias {
  margin-top: 6rpx;
  color: var(--text-2);
  font-size: 26rpx;
}

.role-swatch {
  width: 80rpx;
  height: 80rpx;
  border-radius: 24rpx;
  border: 4rpx solid var(--ink);
  flex-shrink: 0;
  box-sizing: border-box;
}

.role-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}

.role-tag {
  padding: 6rpx 16rpx;
  border-radius: 12rpx;
  background: var(--panel-raised);
  color: #d9cfe6;
  font-size: 24rpx;
}

.panel {
  margin-top: 32rpx;
  padding: 34rpx 32rpx;
  border-radius: 36rpx;
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}

.panel--tight {
  gap: 8rpx;
}

.panel-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.panel-label {
  color: var(--text-2);
  font-size: 26rpx;
  font-weight: 700;
}

.skill-text {
  font-size: 40rpx;
  font-weight: 900;
  line-height: 1.6;
}

.story-text {
  font-size: 32rpx;
  line-height: 1.85;
}

.board-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
  min-height: 104rpx;
  padding: 16rpx 24rpx;
  border-radius: 28rpx;
  background: var(--panel-raised);
  box-sizing: border-box;
}

.board-row-text {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.board-row-name {
  font-size: 30rpx;
  font-weight: 700;
}

.board-row-meta {
  color: var(--text-2);
  font-size: 24rpx;
}

.board-row-arrow,
.switch-arrow {
  color: var(--text-2);
  font-size: 40rpx;
}

.fold {
  border-top: 2rpx solid var(--panel-line);
  padding-top: 16rpx;
  margin-top: 16rpx;
}

.fold-head {
  min-height: 72rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.fold-question {
  font-size: 30rpx;
  font-weight: 700;
  line-height: 1.5;
}

.fold-arrow {
  color: var(--text-2);
  font-size: 40rpx;
  transform: rotate(90deg);
  transition: transform 0.2s;
}

.fold-arrow.open {
  transform: rotate(-90deg);
}

.fold-answer {
  padding-bottom: 8rpx;
  color: #d9cfe6;
  font-size: 28rpx;
  line-height: 1.75;
}

.switcher {
  margin-top: 36rpx;
  display: flex;
  gap: 20rpx;
}

.switch-card {
  flex: 1;
  min-height: 112rpx;
  padding: 20rpx 24rpx;
  border-radius: 28rpx;
  display: flex;
  align-items: center;
  gap: 18rpx;
  box-sizing: border-box;
}

.switch-card--next {
  justify-content: flex-end;
}

.switch-card--empty {
  visibility: hidden;
}

.switch-name {
  font-size: 28rpx;
  font-weight: 700;
}
</style>
