<script setup>
// 板子详情（原型 D2）：顶部铺板子背景图、叠阵容立绘扇形，下面是阵容、夜间顺序、角色细则和规则章节，
// 底部固定栏放收藏 / 记一局 / 用这个板子开局。
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import AssistantDock from '../../components/assistant/AssistantDock.vue'
import RoleArtCard from '../../components/RoleArtCard.vue'
import RoleToken from '../../components/RoleToken.vue'
import api from '../../services/api'
import userData from '../../services/user-data'

const JUDGE_SUPPORT_SHORT = { manual_only: '手动', partial: '半自动', full: '全自动' }

const board = ref(null)
const favorite = ref(false)
const openSections = ref({})
const openFaqs = ref({ 0: true })
let boardId = 0

const rules = computed(() => board.value?.ruleDetail || { hasContent: false, nightOrder: [], roleRules: [], sections: [] })
const overviewText = computed(() => {
  const value = board.value
  if (!value) return ''
  return rules.value.overview || value.cardDescription || value.summary || ''
})
const stats = computed(() => {
  const value = board.value
  if (!value) return []
  return [
    { label: '人数', value: value.playerCount },
    { label: '难度', value: value.difficulty || '—' },
    { label: '胜利', value: value.winCondition || '—' },
    { label: '法官', value: JUDGE_SUPPORT_SHORT[value.judgeSupportLevel] || '手动' },
  ]
})

async function loadDetail() {
  try {
    board.value = await api.getBoardDetail(boardId)
    const first = board.value.ruleDetail?.sections?.[0]
    openSections.value = first ? { [first.title]: true } : {}
  } catch (error) {
    uni.showToast({ title: '详情加载失败', icon: 'none' })
  }
}

async function syncFavorite() {
  try {
    const state = await userData.loadFavoriteBoards()
    favorite.value = (state.boardIds || []).includes(boardId)
  } catch (error) {
    favorite.value = false
  }
}

async function toggleFavorite() {
  try {
    const state = await userData.toggleFavorite(boardId)
    favorite.value = (state.boardIds || []).includes(boardId)
    uni.showToast({ title: favorite.value ? '已加入收藏' : '已取消收藏', icon: 'none' })
  } catch (error) {
    uni.showToast({ title: error?.message || '收藏更新失败', icon: 'none' })
  }
}

function toggleSection(title) {
  openSections.value = { ...openSections.value, [title]: !openSections.value[title] }
}

function toggleFaq(index) {
  openFaqs.value = { ...openFaqs.value, [index]: !openFaqs.value[index] }
}

function lineupSlot(art, index) {
  if (art.center) return 'center'
  return index === 0 ? 'left' : 'right'
}

function openRole(id) {
  uni.navigateTo({ url: `/pages/roles/detail?id=${id}` })
}

function startJudge() {
  uni.navigateTo({ url: `/pages/judge/create?boardId=${boardId}` })
}

function recordSession() {
  uni.navigateTo({ url: `/pages/sessions/edit?boardId=${boardId}` })
}

onLoad((options) => {
  boardId = Number(options?.id || 0)
  loadDetail()
  syncFavorite()
})
</script>

<template>
  <view v-if="board" class="detail-page">
    <view class="detail-hero">
      <image v-if="board.backgroundImage" class="hero-image" :src="board.backgroundImage" mode="aspectFill" webp />
      <view v-if="board.lineupArt.length" class="hero-fan">
        <view
          v-for="(art, index) in board.lineupArt"
          :key="`hero-${art.roleId}`"
          class="hero-fan-card"
          :class="`hero-fan-card--${lineupSlot(art, index)}`"
          @tap="openRole(art.roleId)"
        >
          <RoleArtCard :image="art.image" :accent="art.accent" :show-plate="false" />
        </view>
      </view>
    </view>

    <view class="page-shell page-shell--with-fixed-bar detail-body">
      <view v-if="board.tags && board.tags.length" class="detail-tags">
        <text v-for="tag in board.tags" :key="tag" class="detail-tag">{{ tag }}</text>
      </view>
      <view class="detail-title serif">{{ board.name }}</view>
      <view v-if="overviewText" class="detail-overview">{{ overviewText }}</view>

      <view class="stat-grid">
        <view v-for="stat in stats" :key="stat.label" class="stat-cell">
          <text class="stat-label">{{ stat.label }}</text>
          <text class="stat-value serif">{{ stat.value }}</text>
        </view>
      </view>

      <view class="glass-card panel">
        <view class="panel-head">
          <text class="section-title">角色阵容</text>
          <text class="section-meta">点角色看技能</text>
        </view>
        <view v-if="board.campSegments.length" class="camp-track">
          <view
            v-for="segment in board.campSegments"
            :key="segment.key"
            class="camp-track-segment"
            :style="{ flex: segment.count, backgroundColor: segment.fill }"
          />
        </view>
        <view v-for="group in board.lineupGroups" :key="group.key" class="lineup-group">
          <view class="lineup-group-title" :style="{ color: group.text }">{{ group.label }} · {{ group.count }}</view>
          <view class="lineup-grid">
            <view v-for="item in group.roles" :key="item.roleId" class="lineup-cell" @tap="openRole(item.roleId)">
              <RoleToken :image="item.portrait" :accent="item.accent" :size="104" :count="item.count" />
              <text class="lineup-name">{{ item.name }}</text>
            </view>
          </view>
        </view>
      </view>

      <view v-if="rules.nightOrder.length" class="glass-card panel">
        <view class="panel-head">
          <text class="section-title">夜间行动顺序</text>
          <text class="section-meta">法官台照此引导</text>
        </view>
        <view
          v-for="(step, index) in rules.nightOrder"
          :key="`${step.order}-${step.roleId}`"
          class="night-step"
          @tap="openRole(step.roleId)"
        >
          <view v-if="index < rules.nightOrder.length - 1" class="night-line" />
          <view class="night-order">{{ step.order }}</view>
          <RoleToken :image="step.portrait" :accent="step.accent" :size="80" />
          <view class="night-content">
            <text class="night-role" :style="{ color: step.campColor }">{{ step.roleName }}</text>
            <text class="night-action">{{ step.action }}</text>
          </view>
        </view>
      </view>

      <view v-if="rules.roleRules.length" class="glass-card panel">
        <text class="section-title">角色细则</text>
        <view v-for="note in rules.roleRules" :key="note.roleId" class="note-block" @tap="openRole(note.roleId)">
          <RoleToken :image="note.portrait" :accent="note.accent" :size="72" />
          <view class="note-content">
            <text class="note-role" :style="{ color: note.campColor }">{{ note.roleName }}</text>
            <view v-for="item in note.items" :key="item" class="bullet-item">{{ item }}</view>
          </view>
        </view>
      </view>

      <view v-if="rules.sections.length" class="glass-card panel panel--tight">
        <text class="section-title">规则章节</text>
        <view v-for="section in rules.sections" :key="section.title" class="fold">
          <view class="fold-head" @tap="toggleSection(section.title)">
            <text class="fold-title serif">{{ section.title }}</text>
            <text class="fold-arrow" :class="{ open: openSections[section.title] }">›</text>
          </view>
          <view v-if="openSections[section.title]" class="fold-body">
            <view v-for="item in section.items" :key="item" class="bullet-item">{{ item }}</view>
          </view>
        </view>
      </view>

      <view v-if="board.specialRules && board.specialRules.length" class="glass-card panel">
        <text class="section-title">{{ rules.hasContent ? '补充规则' : '规则详情' }}</text>
        <view v-for="rule in board.specialRules" :key="rule" class="bullet-item">{{ rule }}</view>
      </view>

      <view v-if="board.tips && board.tips.length" class="glass-card panel">
        <text class="section-title">小贴士</text>
        <view v-for="tip in board.tips" :key="tip" class="bullet-item">{{ tip }}</view>
      </view>

      <view v-if="board.faqs && board.faqs.length" class="glass-card panel panel--tight">
        <text class="section-title">常见问题</text>
        <view v-for="(faq, index) in board.faqs" :key="faq.question" class="fold">
          <view class="fold-head" @tap="toggleFaq(index)">
            <text class="fold-question">{{ faq.question }}</text>
            <text class="fold-arrow" :class="{ open: openFaqs[index] }">›</text>
          </view>
          <view v-if="openFaqs[index]" class="fold-body fold-answer">{{ faq.answer }}</view>
        </view>
      </view>
    </view>

    <view class="action-bar">
      <view class="action-icon" :class="{ active: favorite }" @tap="toggleFavorite">{{ favorite ? '♥' : '♡' }}</view>
      <view class="action-ghost" @tap="recordSession">记一局</view>
      <view class="action-primary" @tap="startJudge">☾ 用这个板子开局</view>
    </view>

    <AssistantDock
      scene="board_detail"
      :page-context="{ page: 'boards/detail', boardId: board.id, boardName: board.name, playerCount: board.playerCount, difficulty: board.difficulty }"
    />
  </view>
</template>

<style scoped lang="scss">
.detail-hero {
  position: relative;
  height: 600rpx;
  background: #1b1724;
  border-bottom: 8rpx solid var(--ink);
  overflow: hidden;
}

.hero-image {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.hero-fan {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 36rpx;
  display: flex;
  align-items: flex-end;
  justify-content: center;
}

.hero-fan-card {
  width: 196rpx;
}

.hero-fan-card--left {
  margin-right: -40rpx;
  transform: translateY(-10rpx) rotate(-9deg);
}

.hero-fan-card--center {
  z-index: 2;
  width: 232rpx;
  transform: translateY(-36rpx);
}

.hero-fan-card--right {
  margin-left: -40rpx;
  transform: translateY(-10rpx) rotate(9deg);
}

.detail-body {
  padding-top: 32rpx;
}

.detail-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}

.detail-tag {
  padding: 6rpx 16rpx;
  border-radius: 12rpx;
  background: var(--panel-raised);
  color: #d9cfe6;
  font-size: 22rpx;
}

.detail-title {
  margin-top: 16rpx;
  font-size: 72rpx;
  font-weight: 900;
  line-height: 1.15;
}

.detail-overview {
  margin-top: 16rpx;
  color: #d9cfe6;
  font-size: 28rpx;
  line-height: 1.75;
}

.stat-grid {
  margin-top: 32rpx;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16rpx;
}

.stat-cell {
  display: flex;
  flex-direction: column;
  gap: 6rpx;
  padding: 20rpx 18rpx;
  border-radius: 28rpx;
  background: var(--panel);
  border: 2rpx solid var(--panel-line);
  min-width: 0;
}

.stat-label {
  color: var(--text-2);
  font-size: 22rpx;
}

.stat-value {
  font-size: 34rpx;
  font-weight: 900;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.panel {
  margin-top: 32rpx;
  padding: 34rpx 32rpx;
  border-radius: 36rpx;
  display: flex;
  flex-direction: column;
  gap: 26rpx;
}

.panel--tight {
  gap: 8rpx;
}

.panel-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.camp-track {
  display: flex;
  gap: 4rpx;
  height: 12rpx;
  border-radius: 6rpx;
  overflow: hidden;
}

.camp-track > view {
  height: 100%;
}

.lineup-group-title {
  font-size: 26rpx;
  font-weight: 700;
}

.lineup-grid {
  margin-top: 18rpx;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 30rpx 12rpx;
}

.lineup-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16rpx;
}

.lineup-name {
  font-size: 26rpx;
  font-weight: 700;
}

.night-step {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 22rpx;
}

/* 步骤之间的竖线，从序号下沿连到下一步 */
.night-line {
  position: absolute;
  left: 30rpx;
  top: 68rpx;
  bottom: -30rpx;
  width: 4rpx;
  background: #3a3149;
}

.night-order {
  position: relative;
  z-index: 1;
  flex-shrink: 0;
  width: 64rpx;
  height: 64rpx;
  border-radius: 20rpx;
  background: var(--gold);
  color: var(--ink);
  border: 4rpx solid var(--ink);
  font-family: var(--serif);
  font-size: 30rpx;
  font-weight: 900;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
}

.night-content {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.night-role {
  font-size: 30rpx;
  font-weight: 700;
}

.night-action {
  color: #d9cfe6;
  font-size: 28rpx;
  line-height: 1.6;
}

.note-block {
  display: flex;
  align-items: flex-start;
  gap: 24rpx;
  padding: 28rpx;
  border-radius: 28rpx;
  background: var(--panel-raised);
}

.note-content {
  flex: 1;
  min-width: 0;
}

.note-role {
  font-size: 30rpx;
  font-weight: 700;
}

.bullet-item {
  position: relative;
  margin-top: 8rpx;
  padding-left: 28rpx;
  color: #e8dff0;
  font-size: 28rpx;
  line-height: 1.7;
}

.bullet-item::before {
  content: '';
  position: absolute;
  left: 4rpx;
  top: 20rpx;
  width: 10rpx;
  height: 10rpx;
  border-radius: 50%;
  background: var(--text-2);
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

.fold-title {
  font-size: 34rpx;
  font-weight: 900;
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

.fold-body {
  padding-bottom: 8rpx;
}

.fold-answer {
  color: #d9cfe6;
  font-size: 28rpx;
  line-height: 1.75;
}

.action-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 24rpx 32rpx calc(24rpx + env(safe-area-inset-bottom));
  background: #0e0c13;
  border-top: 4rpx solid var(--ink);
}

.action-icon,
.action-ghost,
.action-primary {
  height: 96rpx;
  border-radius: 28rpx;
  border: 4rpx solid var(--ink);
  box-shadow: 6rpx 6rpx 0 var(--ink);
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  font-weight: 700;
}

.action-icon {
  width: 96rpx;
  flex-shrink: 0;
  background: var(--panel);
  color: var(--text);
  font-size: 40rpx;
}

.action-icon.active {
  color: var(--gold);
}

.action-ghost {
  flex-shrink: 0;
  padding: 0 32rpx;
  background: var(--panel-raised);
  color: var(--text);
  font-size: 30rpx;
}

.action-primary {
  flex: 1;
  background: var(--gold);
  color: var(--ink);
  font-size: 32rpx;
}

.action-icon:active,
.action-ghost:active,
.action-primary:active {
  transform: translate(6rpx, 6rpx);
  box-shadow: none;
}
</style>
