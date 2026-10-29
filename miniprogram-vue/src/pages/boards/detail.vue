<script setup>
// 板子详情（原型 D2）。顶部大图从状态栏开始铺，返回键浮在图上。
import { computed, ref } from 'vue'
import { onLoad, onShareAppMessage } from '@dcloudio/uni-app'
import NavBar from '../../components/NavBar.vue'
import api from '../../services/api'
import userData from '../../services/user-data'
import { openAssistant } from '../../utils/assistant-entry'
import { getNavInsets, rpxToPx } from '../../utils/nav'
import { buildNightSteps } from '../../utils/judge/night-order.js'

const JUDGE_SUPPORT_SHORT = { manual_only: '手动', partial: '半自动', full: '全自动' }

// 头图五张立绘的扇形（全宽 750rpx，中线 375rpx）
const HERO_SLOTS = {
  l2: 'left: 36rpx; top: 208rpx; width: 208rpx; transform: rotate(-15deg); z-index: 1;',
  l1: 'left: 144rpx; top: 152rpx; width: 224rpx; transform: rotate(-7deg); z-index: 2;',
  c: 'left: 251rpx; top: 100rpx; width: 248rpx; z-index: 3;',
  r1: 'left: 382rpx; top: 152rpx; width: 224rpx; transform: rotate(7deg); z-index: 2;',
  r2: 'left: 506rpx; top: 208rpx; width: 208rpx; transform: rotate(15deg); z-index: 1;',
}

const insets = getNavInsets()
const board = ref(null)
const favorite = ref(false)
const openSections = ref({})
const openFaqs = ref({ 0: true })
let boardId = 0

const rules = computed(() => board.value?.ruleDetail || { hasContent: false, overview: '', nightOrder: [], roleRules: [], sections: [] })
// 板子没配夜间顺序时显示通用顺序（和法官主持台用的是同一份）
const nightOrderConfigured = computed(() => rules.value.nightOrder.length > 0)
const nightOrder = computed(() => (nightOrderConfigured.value ? rules.value.nightOrder : board.value ? buildNightSteps(board.value) : []))
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
// 补充规则：旧的 specialRules + 小贴士，放在规则章节面板最下面
const extraRules = computed(() => [...(board.value?.specialRules || []), ...(board.value?.tips || [])].filter(Boolean))
// 「还有疑问？」里的问题条：用角色细则里的角色拼两个常见问法
const suggestedQuestions = computed(() => {
  const value = board.value
  if (!value) return []
  const names = (rules.value.roleRules.length ? rules.value.roleRules : value.roles || []).map((item) => item.roleName || item.name)
  const list = names.slice(0, 2).map((name) => `「${value.name}」里${name}有哪些细节要注意？`)
  return list.length ? list : [`「${value.name}」新手应该怎么玩？`]
})
// 头图区域高度：原型 360px，自定义导航栏叠在上面
const heroHeight = computed(() => `${Math.round(insets.top + rpxToPx(600))}px`)

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

function openRole(id) {
  if (!id) return
  uni.navigateTo({ url: `/pages/roles/detail?id=${id}` })
}

function startJudge() {
  uni.navigateTo({ url: `/pages/judge/create?boardId=${boardId}` })
}

function recordSession() {
  uni.navigateTo({ url: `/pages/sessions/edit?boardId=${boardId}` })
}

function ask(question = '') {
  const value = board.value || {}
  openAssistant(
    'board_detail',
    { page: 'boards/detail', boardId: value.id, boardName: value.name, playerCount: value.playerCount, difficulty: value.difficulty },
    question,
  )
}

onLoad((options) => {
  boardId = Number(options?.id || 0)
  loadDetail()
  syncFavorite()
})

onShareAppMessage(() => ({
  title: board.value ? `「${board.value.name}」板子规则` : '狼人杀板子',
  path: `/pages/boards/detail?id=${boardId}`,
  imageUrl: board.value?.backgroundImage || board.value?.coverImage || '',
}))
</script>

<template>
  <view class="nt-page nt-page--bar">
    <NavBar back overlay />

    <view v-if="board">
      <view class="bd-hero" :style="{ height: heroHeight }">
        <image v-if="board.backgroundImage" class="bd-hero__bg" :src="board.backgroundImage" mode="aspectFill" webp />
        <view v-else class="bd-hero__moon" />
        <view class="bd-hero__fan" :style="{ top: `${insets.top}px` }">
          <view
            v-for="art in board.heroArt"
            :key="art.roleId"
            class="nt-card bd-hero__card"
            :style="HERO_SLOTS[art.slot] || HERO_SLOTS.c"
            @tap="openRole(art.roleId)"
          >
            <view class="nt-card__frame" :style="{ backgroundColor: art.accent }">
              <image class="nt-card__img" :src="art.image" mode="aspectFill" webp />
            </view>
          </view>
        </view>
      </view>

      <view class="nt-gutter bd-head">
        <view v-if="board.tags && board.tags.length" class="bd-tags">
          <text v-for="tag in board.tags" :key="tag" class="nt-tag">{{ tag }}</text>
        </view>
        <text class="bd-title serif">{{ board.name }}</text>
        <text v-if="overviewText" class="bd-overview">{{ overviewText }}</text>
      </view>

      <view class="nt-gutter bd-stats">
        <view v-for="stat in stats" :key="stat.label" class="bd-stat">
          <text class="bd-stat__label">{{ stat.label }}</text>
          <text class="bd-stat__value serif">{{ stat.value }}</text>
        </view>
      </view>

      <view class="nt-gutter nt-stack bd-panels">
        <view class="nt-panel">
          <view class="nt-panel__head">
            <text class="nt-h2">角色阵容</text>
            <text class="nt-meta">点角色看技能</text>
          </view>
          <view v-if="board.campSegments.length" class="nt-camp">
            <view class="nt-camp__track">
              <view
                v-for="segment in board.campSegments"
                :key="segment.key"
                class="nt-camp__seg"
                :style="{ flex: segment.count, backgroundColor: segment.fill }"
              />
            </view>
            <view class="nt-camp__labels">
              <view v-for="segment in board.campSegments" :key="segment.key" class="nt-camp__label">
                <view class="nt-dot" :style="{ backgroundColor: segment.fill }" />
                <text>{{ segment.label }} {{ segment.count }}</text>
              </view>
            </view>
          </view>
          <view class="bd-lineup">
            <view v-for="item in board.roles" :key="item.roleId" class="bd-lineup__cell" @tap="openRole(item.roleId)">
              <view class="nt-token bd-lineup__token">
                <image v-if="item.portrait" class="nt-token__img" :src="item.portrait" mode="aspectFill" :style="{ borderColor: item.accent }" webp />
                <view v-else class="nt-token__text bd-text--lg" :style="{ borderColor: item.accent }">{{ item.name.slice(0, 1) }}</view>
                <text v-if="item.count > 1" class="nt-token__count">×{{ item.count }}</text>
              </view>
              <text class="bd-lineup__name">{{ item.name }}</text>
            </view>
          </view>
        </view>

        <view v-if="nightOrder.length" class="nt-panel">
          <view class="nt-panel__head">
            <text class="nt-h2">夜间行动顺序</text>
            <text class="nt-meta">{{ nightOrderConfigured ? '法官台照此引导' : '通用顺序 · 法官台照此引导' }}</text>
          </view>
          <view class="bd-night">
            <view
              v-for="(step, index) in nightOrder"
              :key="`${step.order}-${step.roleId}`"
              class="bd-step"
              @tap="openRole(step.roleId)"
            >
              <view v-if="index < nightOrder.length - 1" class="bd-step__line" />
              <text class="bd-step__no serif">{{ step.order }}</text>
              <view class="nt-token bd-step__token">
                <image v-if="step.portrait" class="nt-token__img" :src="step.portrait" mode="aspectFill" :style="{ borderColor: step.accent }" webp />
                <view v-else class="nt-token__text bd-text--md" :style="{ borderColor: step.accent }">{{ step.roleName.slice(0, 1) }}</view>
              </view>
              <view class="bd-step__text">
                <text class="bd-step__role" :style="{ color: step.campColor }">{{ step.roleName }}</text>
                <text class="bd-step__action">{{ step.action }}</text>
              </view>
            </view>
          </view>
        </view>

        <view v-if="rules.roleRules.length" class="nt-panel">
          <text class="nt-h2">角色细则</text>
          <view v-for="note in rules.roleRules" :key="note.roleId" class="bd-note" @tap="openRole(note.roleId)">
            <view class="nt-token bd-note__token">
              <image v-if="note.portrait" class="nt-token__img" :src="note.portrait" mode="aspectFill" :style="{ borderColor: note.accent }" webp />
              <view v-else class="nt-token__text bd-text--md" :style="{ borderColor: note.accent }">{{ note.roleName.slice(0, 1) }}</view>
            </view>
            <view class="bd-note__text">
              <text class="bd-note__role" :style="{ color: note.campColor }">{{ note.roleName }}</text>
              <view class="nt-bullets">
                <text v-for="item in note.items" :key="item" class="nt-bullet">{{ item }}</text>
              </view>
            </view>
          </view>
        </view>

        <view v-if="rules.sections.length || extraRules.length" class="nt-panel nt-panel--tight">
          <text class="nt-h2 bd-sections__title">{{ rules.sections.length ? '规则章节' : '规则详情' }}</text>
          <view v-for="section in rules.sections" :key="section.title" class="nt-fold">
            <view class="nt-fold__head" @tap="toggleSection(section.title)">
              <text class="nt-fold__title">{{ section.title }}</text>
              <text class="nt-fold__arrow" :class="{ 'nt-fold__arrow--open': openSections[section.title] }">›</text>
            </view>
            <view v-if="openSections[section.title]" class="nt-bullets bd-fold__body">
              <text v-for="item in section.items" :key="item" class="nt-bullet">{{ item }}</text>
            </view>
          </view>
          <view v-if="extraRules.length" class="nt-fold bd-extra">
            <text class="nt-label">补充规则</text>
            <view class="nt-bullets">
              <text v-for="item in extraRules" :key="item" class="nt-bullet">{{ item }}</text>
            </view>
          </view>
        </view>

        <view class="nt-panel">
          <text class="nt-h2">还有疑问？</text>
          <view v-if="board.faqs && board.faqs.length" class="bd-faqs">
            <view v-for="(faq, index) in board.faqs" :key="faq.question" class="nt-fold">
              <view class="nt-fold__head" @tap="toggleFaq(index)">
                <text class="nt-fold__question">{{ faq.question }}</text>
                <text class="nt-fold__arrow" :class="{ 'nt-fold__arrow--open': openFaqs[index] }">›</text>
              </view>
              <text v-if="openFaqs[index]" class="bd-faq__answer">{{ faq.answer }}</text>
            </view>
          </view>
          <view v-for="question in suggestedQuestions" :key="question" class="nt-qchip" @tap="ask(question)">
            <text>{{ question }}</text>
            <text class="nt-qchip__ask">问 AI</text>
          </view>
        </view>
      </view>
    </view>

    <view class="nt-bar">
      <view class="nt-iconbtn" :class="{ 'c-gold': favorite }" @tap="toggleFavorite">{{ favorite ? '♥' : '♡' }}</view>
      <view class="nt-btn nt-btn--gold bd-start" @tap="startJudge">☾ 用这个板子开局</view>
      <view class="nt-btn nt-btn--ghost" @tap="recordSession">记一局</view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.bd-hero {
  position: relative;
  background: #1b1724;
  border-bottom: 8rpx solid var(--ink);
  overflow: hidden;
}

.bd-hero__bg {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
}

.bd-hero__moon {
  position: absolute;
  right: -120rpx;
  top: -80rpx;
  width: 460rpx;
  height: 460rpx;
  border-radius: 50%;
  background: #2a2338;
}

.bd-hero__fan {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
}

.bd-hero__card {
  position: absolute;
}

.bd-head {
  padding-top: 36rpx;
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.bd-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}

.bd-title {
  font-size: 68rpx;
  font-weight: 900;
  line-height: 1.2;
}

.bd-overview {
  color: var(--text-soft);
  font-size: 30rpx;
  line-height: 1.75;
}

.bd-stats {
  margin-top: 36rpx;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16rpx;
}

.bd-stat {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
  padding: 22rpx 20rpx;
  border-radius: 28rpx;
  background: var(--panel);
  border: 2rpx solid var(--panel-line);
  min-width: 0;
}

.bd-stat__label {
  color: var(--text-2);
  font-size: 24rpx;
}

.bd-stat__value {
  font-size: 36rpx;
  font-weight: 900;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.bd-panels {
  margin-top: 32rpx;
}

.bd-lineup {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 32rpx 16rpx;
  padding-top: 8rpx;
}

.bd-lineup__cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16rpx;
}

.bd-lineup__token {
  width: 108rpx;
  height: 108rpx;
}

.bd-text--lg {
  font-size: 44rpx;
}

.bd-text--md {
  font-size: 32rpx;
}

.bd-lineup__name {
  font-size: 26rpx;
  font-weight: 700;
}

.bd-night {
  display: flex;
  flex-direction: column;
  gap: 36rpx;
}

.bd-step {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 24rpx;
}

.bd-step__line {
  position: absolute;
  left: 30rpx;
  top: 72rpx;
  bottom: -32rpx;
  width: 4rpx;
  background: var(--line);
}

.bd-step__no {
  position: relative;
  z-index: 1;
  width: 64rpx;
  height: 64rpx;
  border-radius: 20rpx;
  background: var(--gold);
  color: var(--ink);
  border: 4rpx solid var(--ink);
  font-size: 30rpx;
  font-weight: 900;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  flex-shrink: 0;
}

.bd-step__token {
  width: 80rpx;
  height: 80rpx;
}

.bd-step__text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.bd-step__role {
  font-size: 30rpx;
  font-weight: 700;
}

.bd-step__action {
  color: var(--text-soft);
  font-size: 28rpx;
  line-height: 1.6;
}

.bd-note {
  display: flex;
  align-items: flex-start;
  gap: 24rpx;
  padding: 28rpx;
  border-radius: 28rpx;
  background: var(--panel-raised);
}

.bd-note__token {
  width: 72rpx;
  height: 72rpx;
}

.bd-note__text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.bd-note__role {
  font-size: 30rpx;
  font-weight: 700;
}

.bd-sections__title {
  padding-bottom: 16rpx;
}

.bd-fold__body {
  padding-bottom: 12rpx;
}

.bd-extra {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
}

.bd-faqs {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.bd-faq__answer {
  display: block;
  padding-bottom: 12rpx;
  color: var(--text-soft);
  font-size: 28rpx;
  line-height: 1.75;
}

.bd-start {
  flex: 1;
}
</style>
