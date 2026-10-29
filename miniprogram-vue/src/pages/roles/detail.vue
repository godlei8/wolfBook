<script setup>
// 角色详情（原型 D4）：整幅立绘铺在顶部，名字卡压在立绘下沿；名字卡上是称号、角色名和真名。
// 正文：技能 → 身世 → 人物关系（点头像跳到对方）→ 出现在哪些板子 → 常见问题。
import { computed, ref } from 'vue'
import { onLoad, onShareAppMessage } from '@dcloudio/uni-app'
import NavBar from '../../components/NavBar.vue'
import api from '../../services/api'
import { openAssistant } from '../../utils/assistant-entry'
import { getNavInsets, rpxToPx } from '../../utils/nav'

const insets = getNavInsets()
const role = ref(null)
const allRoles = ref([])
const openFaqs = ref({ 0: true })
const showAllBoards = ref(false)
// 原型只列 3 个板子，其余点「展开全部」
const visibleBoards = computed(() => {
  const list = role.value?.boards || []
  return showAllBoards.value ? list : list.slice(0, 3)
})

const position = computed(() => (role.value ? allRoles.value.findIndex((item) => item.id === role.value.id) : -1))
const prevRole = computed(() => (position.value > 0 ? allRoles.value[position.value - 1] : null))
const nextRole = computed(() =>
  position.value >= 0 && position.value < allRoles.value.length - 1 ? allRoles.value[position.value + 1] : null,
)
const heroHeight = computed(() => `${Math.round(insets.top + rpxToPx(880))}px`)
const heroImage = computed(() => role.value?.fullIllustration || role.value?.portrait || '')
// 在哪个板子里第几个醒来：取第一个写了夜间顺序的板子
const wakeHint = computed(() => {
  const board = (role.value?.boards || []).find((item) => item.wakeOrder)
  return board ? `第 ${board.wakeOrder} 个醒来` : ''
})
const tags = computed(() => {
  const value = role.value
  if (!value) return []
  const list = [{ text: `${value.faction}阵营`, color: value.campColor }]
  if (value.roleType && value.roleType !== value.faction) list.push({ text: value.roleType })
  if (wakeHint.value) list.push({ text: '每晚行动' }, { text: wakeHint.value })
  return list
})
const followQuestion = computed(() => (role.value ? `${role.value.name}有哪些常见打法和误区？` : ''))

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

function openRelation(item) {
  if (item?.roleId) uni.redirectTo({ url: `/pages/roles/detail?id=${item.roleId}` })
}

function openWorld() {
  uni.navigateTo({ url: '/pages/world/index' })
}

function openBoard(id) {
  uni.navigateTo({ url: `/pages/boards/detail?id=${id}` })
}

// 左右切换不叠页面栈
function switchRole(target) {
  if (!target) return
  uni.redirectTo({ url: `/pages/roles/detail?id=${target.id}` })
}

function ask(question) {
  const value = role.value || {}
  openAssistant('role_detail', { page: 'roles/detail', roleId: value.id, roleName: value.name, faction: value.faction, roleType: value.roleType }, question)
}

function boardMeta(board) {
  return [`${board.playerCount} 人`, board.wakeOrder ? `第 ${board.wakeOrder} 个醒来` : ''].filter(Boolean).join(' · ')
}

onLoad((options) => {
  loadRole(Number(options?.id || 0))
  loadAllRoles()
})

onShareAppMessage(() => ({
  title: role.value ? `狼人杀角色 · ${role.value.name}` : '狼人杀角色图鉴',
  path: `/pages/roles/detail?id=${role.value?.id || ''}`,
  imageUrl: heroImage.value,
}))
</script>

<template>
  <view class="nt-page rd-page">
    <NavBar back overlay>
      <text v-if="position >= 0" class="rd-index serif">{{ position + 1 }} / {{ allRoles.length }}</text>
    </NavBar>

    <view v-if="role">
      <view class="rd-hero" :style="{ height: heroHeight, backgroundColor: role.accent }">
        <image v-if="heroImage" class="rd-hero__img" :src="heroImage" mode="aspectFill" webp />
        <view v-else class="rd-hero__back">
          <view class="rd-hero__line" />
          <text class="rd-hero__moon">☾</text>
          <text class="rd-hero__hint">立绘绘制中</text>
        </view>
      </view>

      <view class="nt-gutter">
        <view class="nt-ink rd-name">
          <view class="rd-name__text">
            <text v-if="role.title" class="rd-name__epithet">{{ role.title }}</text>
            <text class="rd-name__title serif">{{ role.name }}</text>
            <text v-if="role.alias" class="rd-name__real serif">{{ role.alias }}</text>
          </view>
          <view class="rd-tags">
            <text v-for="tag in tags" :key="tag.text" class="nt-tag" :style="tag.color ? { color: tag.color } : {}">{{ tag.text }}</text>
          </view>
        </view>

        <view class="nt-stack rd-panels">
          <view class="nt-panel">
            <text class="nt-label">技能</text>
            <text class="rd-skill serif">{{ role.skill }}</text>
          </view>

          <view v-if="role.background" class="nt-panel">
            <view class="nt-panel__head">
              <text class="nt-label">身世</text>
              <text class="rd-link" @tap="openWorld">夜镇编年史 ›</text>
            </view>
            <text class="rd-story serif">{{ role.background }}</text>
          </view>

          <view v-if="role.relations && role.relations.length" class="nt-panel">
            <text class="nt-h2">人物关系</text>
            <view class="rd-relations">
              <view v-for="item in role.relations" :key="item.roleId" class="rd-relation" @tap="openRelation(item)">
                <view class="nt-token rd-relation__token">
                  <image v-if="item.portrait || item.fullIllustration" class="nt-token__img" :src="item.portrait || item.fullIllustration" mode="aspectFill" :style="{ borderColor: item.accent }" webp />
                  <view v-else class="nt-token__text" :style="{ borderColor: item.accent }">{{ item.name.slice(0, 1) }}</view>
                </view>
                <view class="rd-relation__text">
                  <text class="rd-relation__label">{{ item.label }}</text>
                  <text class="rd-relation__name">{{ item.name }}<text v-if="item.alias" class="rd-relation__real"> · {{ item.alias }}</text></text>
                </view>
                <text class="nt-arrow">›</text>
              </view>
            </view>
          </view>

          <view v-if="role.boards && role.boards.length" class="nt-panel">
            <view class="nt-panel__head">
              <text class="nt-h2">出现在这些板子</text>
              <text class="nt-meta">共 {{ role.boards.length }} 个</text>
            </view>
            <view v-for="board in visibleBoards" :key="board.id" class="nt-row" @tap="openBoard(board.id)">
              <view class="rd-board__text">
                <text class="rd-board__name">{{ board.name }}</text>
                <text class="nt-meta">{{ boardMeta(board) }}</text>
              </view>
              <view class="rd-stack">
                <view
                  v-for="(item, index) in board.lineup"
                  :key="item.roleId"
                  class="nt-token rd-stack__token"
                  :style="{ marginLeft: index ? '-16rpx' : '0', zIndex: 3 - index }"
                >
                  <image v-if="item.portrait" class="nt-token__img" :src="item.portrait" mode="aspectFill" :style="{ borderColor: item.accent }" webp />
                  <view v-else class="nt-token__text rd-text--sm" :style="{ borderColor: item.accent }">{{ item.name.slice(0, 1) }}</view>
                </view>
              </view>
            </view>
            <text v-if="role.boards.length > 3" class="rd-more" @tap="showAllBoards = !showAllBoards">
              {{ showAllBoards ? '收起' : `展开全部 ${role.boards.length} 个` }}
            </text>
          </view>

          <view class="nt-panel">
            <text class="nt-h2">常见问题</text>
            <view v-for="(faq, index) in role.faqs || []" :key="faq.question" class="nt-fold">
              <view class="nt-fold__head" @tap="toggleFaq(index)">
                <text class="nt-fold__question">{{ faq.question }}</text>
                <text class="nt-fold__arrow" :class="{ 'nt-fold__arrow--open': openFaqs[index] }">›</text>
              </view>
              <text v-if="openFaqs[index]" class="rd-answer">{{ faq.answer }}</text>
            </view>
            <view class="nt-qchip" @tap="ask(followQuestion)">
              <text>继续问：{{ followQuestion }}</text>
              <text class="nt-qchip__ask">问 AI</text>
            </view>
          </view>
        </view>

        <view v-if="prevRole || nextRole" class="rd-switch">
          <view class="nt-ink rd-switch__card" :class="{ 'rd-switch__card--empty': !prevRole }" @tap="switchRole(prevRole)">
            <block v-if="prevRole">
              <text class="nt-arrow">‹</text>
              <view class="nt-token rd-switch__token">
                <image v-if="prevRole.portrait" class="nt-token__img" :src="prevRole.portrait" mode="aspectFill" :style="{ borderColor: prevRole.accent }" webp />
                <view v-else class="nt-token__text rd-text--sm" :style="{ borderColor: prevRole.accent }">{{ prevRole.name.slice(0, 1) }}</view>
              </view>
              <text class="rd-switch__name">{{ prevRole.name }}</text>
            </block>
          </view>
          <view class="nt-ink rd-switch__card rd-switch__card--next" :class="{ 'rd-switch__card--empty': !nextRole }" @tap="switchRole(nextRole)">
            <block v-if="nextRole">
              <text class="rd-switch__name">{{ nextRole.name }}</text>
              <view class="nt-token rd-switch__token">
                <image v-if="nextRole.portrait" class="nt-token__img" :src="nextRole.portrait" mode="aspectFill" :style="{ borderColor: nextRole.accent }" webp />
                <view v-else class="nt-token__text rd-text--sm" :style="{ borderColor: nextRole.accent }">{{ nextRole.name.slice(0, 1) }}</view>
              </view>
              <text class="nt-arrow">›</text>
            </block>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.rd-page {
  padding-bottom: calc(env(safe-area-inset-bottom) + 60rpx);
}

.rd-index {
  padding: 8rpx 22rpx;
  border-radius: 20rpx;
  background: var(--ink);
  color: var(--text);
  font-size: 26rpx;
  font-weight: 700;
  letter-spacing: 2rpx;
}

.rd-hero {
  position: relative;
  border-bottom: 8rpx solid var(--ink);
  overflow: hidden;
}

.rd-hero__img {
  width: 100%;
  height: 100%;
  display: block;
}

/* 没有立绘：阵营色底 + 卡背（金色内框 + 月亮） */
.rd-hero__back {
  position: absolute;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 20rpx;
}

.rd-hero__line {
  position: absolute;
  left: 120rpx;
  right: 120rpx;
  top: 220rpx;
  bottom: 140rpx;
  border: 4rpx solid var(--ink);
  border-radius: 32rpx;
  opacity: 0.35;
}

.rd-hero__moon {
  color: var(--ink);
  font-size: 160rpx;
  opacity: 0.55;
}

.rd-hero__hint {
  color: var(--ink);
  font-size: 28rpx;
  font-weight: 700;
  opacity: 0.6;
}

.rd-text--sm {
  font-size: 26rpx;
}

.rd-name {
  position: relative;
  z-index: 2;
  margin-top: -68rpx;
  padding: 36rpx;
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}

.rd-name__text {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}

.rd-name__epithet {
  color: var(--gold);
  font-size: 26rpx;
  font-weight: 700;
  letter-spacing: 6rpx;
}

.rd-name__title {
  font-size: 72rpx;
  font-weight: 900;
  line-height: 1.1;
}

.rd-name__real {
  color: var(--text-2);
  font-size: 34rpx;
  font-weight: 700;
}

.rd-link {
  color: var(--moon);
  font-size: 26rpx;
  font-weight: 700;
}

.rd-relations {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.rd-relation {
  display: flex;
  align-items: center;
  gap: 22rpx;
  padding: 18rpx 20rpx;
  border-radius: 28rpx;
  background: var(--panel-raised);
}

.rd-relation__token {
  width: 84rpx;
  height: 84rpx;
  flex-shrink: 0;
}

.rd-relation__text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.rd-relation__label {
  font-size: 30rpx;
  font-weight: 700;
}

.rd-relation__name {
  color: var(--text-3);
  font-size: 24rpx;
}

.rd-relation__real {
  color: var(--text-3);
}

.rd-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}

.rd-panels {
  margin-top: 32rpx;
}

.rd-skill {
  font-size: 42rpx;
  font-weight: 900;
  line-height: 1.6;
}

.rd-story {
  font-size: 32rpx;
  line-height: 1.85;
}

.rd-board__text {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.rd-board__name {
  font-size: 30rpx;
  font-weight: 700;
}

.rd-stack {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  padding-right: 8rpx;
}

.rd-stack__token {
  position: relative;
  width: 60rpx;
  height: 60rpx;
}

.rd-more {
  align-self: center;
  padding: 8rpx 24rpx;
  color: var(--moon);
  font-size: 28rpx;
  font-weight: 700;
}

.rd-answer {
  display: block;
  padding-bottom: 12rpx;
  color: var(--text-soft);
  font-size: 28rpx;
  line-height: 1.75;
}

.rd-switch {
  margin-top: 36rpx;
  display: flex;
  gap: 20rpx;
}

.rd-switch__card {
  flex: 1;
  min-height: 112rpx;
  padding: 20rpx 24rpx;
  border-radius: 28rpx;
  display: flex;
  align-items: center;
  gap: 18rpx;
}

.rd-switch__card--next {
  justify-content: flex-end;
}

.rd-switch__card--empty {
  visibility: hidden;
}

.rd-switch__token {
  width: 68rpx;
  height: 68rpx;
}

.rd-switch__name {
  font-size: 28rpx;
  font-weight: 700;
}
</style>
