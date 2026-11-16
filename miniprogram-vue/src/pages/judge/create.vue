<script setup>
// 建房分两步：① 选板子（列表，点一下就进下一步）→ ② 配置房间：基本信息、板子规则（跟板子走，只读：玩法概述、
// 胜负和专属配置、夜间顺序、每个角色的规则；通用规则是一行入口，点开弹窗看）、有法官 / 无法官（一行两个小按钮）、
// 房间规则（节奏，大厅里还能改）→ 创建并进入大厅。从板子详情 / 再来一局带 boardId 进来直接到第二步。
// 新建的房间都走新流程（autoJudge）。
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import NavBar from '../../components/NavBar.vue'
import RuleSheet from '../../components/RuleSheet.vue'
import api from '../../services/api'
import { getJudgeSupportMeta } from '../../utils/judge/meta'
import {
  ROOM_RULE_STEPS,
  boardRuleIntro,
  buildCreatePayload,
  canChooseJoined,
  defaultRoomRules,
  judgeModeRow,
  stepRule,
} from '../../utils/judge/create-room.js'
import { commonRules, commonRulesSummary } from '../../utils/judge/common-rules.js'

const boards = ref([])
const selectedBoardIndex = ref(-1)
const step = ref('board')
const loading = ref(false)
const creating = ref(false)
const judgeMode = ref('observer')
// 选中板子的详情（带 judgeRules、阵容数量和支持级别），按板子 id 缓存
const details = ref({})
const rules = ref(defaultRoomRules(null))

const selectedBoard = computed(() => boards.value[selectedBoardIndex.value] || null)
const selectedDetail = computed(() => (selectedBoard.value ? details.value[selectedBoard.value.id] || null : null))
const supportMeta = computed(() => getJudgeSupportMeta(selectedDetail.value?.judgeSupportLevel))
const joinedAllowed = computed(() => canChooseJoined(selectedDetail.value))
const lineup = computed(() => (selectedDetail.value?.roles || []).filter((role) => role?.name))
const ruleIntro = computed(() => boardRuleIntro(selectedDetail.value))
const modeRow = computed(() => judgeModeRow(selectedDetail.value, judgeMode.value))
// 板子规则里点开了哪些角色、哪些章节
const openRoles = ref({})
const openSections = ref({})
// 通用规则（所有板子一样）不占页面：一行入口，点开弹窗看
const commonSections = commonRules()
const commonSummary = commonRulesSummary()
const commonOpen = ref(false)
const ruleSteps = Object.entries(ROOM_RULE_STEPS).map(([key, range]) => ({ key, label: range.label }))
const navTitle = computed(() => (step.value === 'board' ? '选板子' : '配置房间'))

function toggleRole(roleId) {
  openRoles.value = { ...openRoles.value, [roleId]: !openRoles.value[roleId] }
}

function toggleSection(title) {
  openSections.value = { ...openSections.value, [title]: !openSections.value[title] }
}

function openBoardDetail() {
  if (selectedBoard.value) uni.navigateTo({ url: `/pages/boards/detail?id=${selectedBoard.value.id}` })
}

// 没配横版背景的板子：缩略图用阵容立绘（中间那张，一般是特殊狼）
function pickArt(board) {
  const arts = board?.lineupArt || []
  return arts.find((art) => art.slot === 'c') || arts[0] || null
}

function boardMeta(board) {
  return [`${board.playerCount} 人`, board.difficulty, board.winCondition].filter(Boolean).join(' · ')
}

async function loadDetail(board) {
  if (!board) return
  let detail = details.value[board.id]
  if (!detail) {
    try {
      detail = await api.getBoardDetail(board.id)
      details.value = { ...details.value, [board.id]: detail }
    } catch (error) {
      uni.showToast({ title: error?.message || '板子规则加载失败', icon: 'none' })
      return
    }
  }
  if (selectedBoard.value?.id !== board.id) return
  rules.value = defaultRoomRules(detail)
  if (judgeMode.value === 'joined' && !canChooseJoined(detail)) judgeMode.value = 'observer'
}

async function loadBoards(preferredBoardId = 0) {
  loading.value = true
  try {
    const data = await api.getBoards({ page: 1, size: 50 })
    boards.value = data.list || []
    if (preferredBoardId) {
      const index = boards.value.findIndex((item) => item.id === preferredBoardId)
      if (index >= 0) chooseBoard(index)
    }
  } catch (error) {
    uni.showToast({ title: error?.message || '板子加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

// 第一步点一个板子：选中并直接进第二步
function chooseBoard(index) {
  selectedBoardIndex.value = index
  step.value = 'config'
  loadDetail(selectedBoard.value)
  uni.pageScrollTo({ scrollTop: 0, duration: 0 })
}

function backToBoards() {
  step.value = 'board'
  uni.pageScrollTo({ scrollTop: 0, duration: 0 })
}

function chooseMode(mode) {
  if (mode === 'joined' && !joinedAllowed.value) {
    uni.showToast({ title: '这个板子只能开有法官', icon: 'none' })
    return
  }
  judgeMode.value = mode
}

function toggleSheriff() {
  rules.value = { ...rules.value, sheriffEnabled: !rules.value.sheriffEnabled }
}

function stepValue(key, direction) {
  rules.value = { ...rules.value, [key]: stepRule(rules.value[key], key, direction) }
}

async function createRoom() {
  if (creating.value) return
  if (!selectedBoard.value || !selectedDetail.value) {
    uni.showToast({ title: '板子还在加载，稍等一下', icon: 'none' })
    return
  }
  creating.value = true
  try {
    const snapshot = await api.createJudgeRoom(buildCreatePayload(selectedDetail.value, judgeMode.value, rules.value))
    uni.redirectTo({ url: `/pages/judge/room?roomId=${snapshot.roomId}` })
  } catch (error) {
    uni.showToast({ title: error?.message || '创建房间失败', icon: 'none' })
  } finally {
    creating.value = false
  }
}

onLoad((options) => {
  loadBoards(Number(options?.boardId || 0))
})
</script>

<template>
  <view class="nt-page" :class="{ 'nt-page--bar': step === 'config' }">
    <NavBar back :title="navTitle" />

    <view class="nt-gutter jc-steps">
      <view class="jc-steps__item" :class="{ 'jc-steps__item--on': step === 'board', 'jc-steps__item--done': step === 'config' }" @tap="step === 'config' && backToBoards()">
        <text class="jc-steps__no">1</text>
        <text class="jc-steps__label">选板子</text>
      </view>
      <view class="jc-steps__line" />
      <view class="jc-steps__item" :class="{ 'jc-steps__item--on': step === 'config' }">
        <text class="jc-steps__no">2</text>
        <text class="jc-steps__label">配置房间</text>
      </view>
    </view>

    <!-- 第一步：选板子 -->
    <view v-if="step === 'board'" class="nt-gutter nt-stack jc-body">
      <view v-if="loading" class="nt-empty">正在加载板子…</view>
      <view v-else-if="!boards.length" class="nt-empty">还没有可以开局的板子</view>
      <view
        v-for="(board, index) in boards"
        :key="board.id"
        class="nt-ink jc-pick"
        :class="{ 'jc-pick--on': index === selectedBoardIndex }"
        @tap="chooseBoard(index)"
      >
        <view class="jc-pick__art">
          <image v-if="board.backgroundImage" class="jc-pick__bg" :src="board.backgroundImage" mode="aspectFill" webp />
          <view v-else-if="pickArt(board)" class="jc-pick__card" :style="{ backgroundColor: pickArt(board).accent }">
            <image class="jc-pick__bg jc-pick__bg--top" :src="pickArt(board).image" mode="aspectFill" webp />
          </view>
          <text class="jc-pick__count serif">{{ board.playerCount }}<text class="jc-pick__unit">人</text></text>
        </view>
        <view class="jc-pick__main">
          <text class="jc-pick__name serif">{{ board.name }}</text>
          <text class="nt-meta">{{ boardMeta(board) }}</text>
          <view class="jc-board__stack jc-pick__stack">
            <view
              v-for="(role, roleIndex) in (board.cardRoles || []).slice(0, 5)"
              :key="role.roleId"
              class="nt-token jc-board__token"
              :style="{ marginLeft: roleIndex ? '-14rpx' : '0', zIndex: 6 - roleIndex }"
            >
              <image v-if="role.portrait" class="nt-token__img" :src="role.portrait" mode="aspectFill" :style="{ borderColor: role.accent }" webp />
              <view v-else class="nt-token__text jc-board__text" :style="{ borderColor: role.accent }">{{ role.name.slice(0, 1) }}</view>
            </view>
          </view>
        </view>
        <text class="jc-pick__go">›</text>
      </view>
    </view>

    <!-- 第二步：配置房间 -->
    <block v-else-if="selectedBoard">
      <view class="nt-gutter">
        <view class="nt-ink jc-preview">
          <image v-if="selectedBoard.backgroundImage" class="jc-preview__bg" :src="selectedBoard.backgroundImage" mode="aspectFill" webp />
          <view
            v-for="art in selectedBoard.lineupArt"
            :key="art.roleId"
            class="nt-card jc-preview__card"
            :class="`jc-preview__card--${art.slot}`"
          >
            <view class="nt-card__frame" :style="{ backgroundColor: art.accent }">
              <image class="nt-card__img" :src="art.image" mode="aspectFill" webp />
            </view>
          </view>
          <view class="jc-preview__plate">
            <view class="jc-preview__copy">
              <text class="jc-preview__eyebrow">今晚开这一局</text>
              <text class="jc-preview__name serif">{{ selectedBoard.name }}</text>
            </view>
          </view>
        </view>
      </view>

      <view class="nt-gutter nt-stack jc-body">
        <view class="nt-panel">
          <view class="nt-panel__head">
            <text class="nt-h2">基本信息</text>
            <text class="jc-change" @tap="backToBoards">换板子</text>
          </view>
          <view class="jc-info">
            <view class="jc-info__item">
              <text class="jc-info__value serif">{{ selectedBoard.playerCount }}</text>
              <text class="jc-info__label">人数</text>
            </view>
            <view class="jc-info__item">
              <text class="jc-info__value serif">{{ selectedBoard.difficulty || '—' }}</text>
              <text class="jc-info__label">难度</text>
            </view>
            <view class="jc-info__item">
              <text class="jc-info__value serif">{{ selectedDetail ? supportMeta.label : '…' }}</text>
              <text class="jc-info__label">电子法官</text>
            </view>
          </view>
          <view v-if="lineup.length" class="jc-lineup">
            <view v-for="role in lineup" :key="role.roleId" class="jc-lineup__item">
              <view class="nt-token jc-lineup__token">
                <image v-if="role.portrait" class="nt-token__img" :src="role.portrait" mode="aspectFill" :style="{ borderColor: role.accent }" webp />
                <view v-else class="nt-token__text jc-board__text" :style="{ borderColor: role.accent }">{{ role.name.slice(0, 1) }}</view>
              </view>
              <text class="jc-lineup__name">{{ role.name }}<text v-if="role.count > 1" class="jc-lineup__count"> ×{{ role.count }}</text></text>
            </view>
          </view>
        </view>

        <view class="nt-panel">
          <view class="nt-panel__head">
            <text class="nt-h2">板子规则</text>
            <text class="jc-change" @tap="openBoardDetail">完整介绍</text>
          </view>
          <view v-if="!selectedDetail" class="nt-empty">正在加载板子规则…</view>
          <text v-if="ruleIntro.overview" class="jc-overview">{{ ruleIntro.overview }}</text>
          <view v-for="row in ruleIntro.rows" :key="row.label" class="jc-rule" :class="{ 'jc-rule--column': row.label === '夜间顺序' }">
            <text class="jc-rule__label">{{ row.label }}</text>
            <view v-if="row.label === '夜间顺序'" class="jc-order">
              <block v-for="(name, index) in row.value.split(' → ')" :key="name">
                <text v-if="index" class="jc-order__arrow">›</text>
                <text class="jc-order__step">{{ name }}</text>
              </block>
            </view>
            <text v-else class="jc-config__value">{{ row.value }}</text>
          </view>
          <view v-if="ruleIntro.roles.length" class="jc-rule jc-rule--column">
            <text class="jc-rule__label">角色规则</text>
            <view class="jc-roles">
              <view v-for="role in ruleIntro.roles" :key="role.roleId" class="jc-role" @tap="toggleRole(role.roleId)">
                <view class="nt-token jc-role__token">
                  <image v-if="role.portrait" class="nt-token__img" :src="role.portrait" mode="aspectFill" :style="{ borderColor: role.accent }" webp />
                  <view v-else class="nt-token__text jc-board__text" :style="{ borderColor: role.accent }">{{ role.name.slice(0, 1) }}</view>
                </view>
                <view class="jc-role__body">
                  <view class="jc-role__head">
                    <text class="jc-role__name" :style="{ color: role.campColor }">{{ role.name }}</text>
                    <text v-if="role.moreLabel" class="jc-role__more">{{ openRoles[role.roleId] ? '收起' : role.moreLabel }}</text>
                  </view>
                  <text class="jc-role__line">{{ role.lead }}</text>
                  <block v-if="openRoles[role.roleId]">
                    <text v-for="item in role.more" :key="item" class="jc-role__line">{{ item }}</text>
                  </block>
                </view>
              </view>
            </view>
          </view>
          <view class="jc-common" @tap="commonOpen = true">
            <view class="jc-common__text">
              <text class="jc-common__label">通用规则</text>
              <text class="jc-common__sub">{{ commonSummary }}</text>
            </view>
            <text class="jc-common__go">查看 ›</text>
          </view>
          <view v-for="section in ruleIntro.sections" :key="section.title" class="jc-fold">
            <view class="jc-fold__head" @tap="toggleSection(section.title)">
              <text class="jc-rule__label">{{ section.title }}</text>
              <text v-if="section.value" class="jc-fold__value">{{ section.value }}</text>
              <text class="nt-fold__arrow" :class="{ 'nt-fold__arrow--open': openSections[section.title] }">›</text>
            </view>
            <view v-if="openSections[section.title]" class="nt-bullets jc-fold__body">
              <text v-for="item in section.items" :key="item" class="nt-bullet">{{ item }}</text>
            </view>
          </view>
        </view>

        <view class="nt-panel">
          <view class="jc-moderow">
            <text class="nt-h2">法官模式</text>
            <view class="jc-seg">
              <view
                v-for="item in modeRow.options"
                :key="item.value"
                class="jc-seg__item"
                :class="{ 'jc-seg__item--on': item.on, 'jc-seg__item--off': item.disabled }"
                @tap="chooseMode(item.value)"
              >{{ item.label }}</view>
            </view>
          </view>
          <text class="jc-hint">{{ modeRow.hint }}</text>
        </view>

        <view class="nt-panel">
          <view class="nt-panel__head">
            <text class="nt-h2">房间规则</text>
            <text class="nt-meta">大厅里还能改</text>
          </view>
          <view class="jc-rule">
            <text class="jc-rule__label">上警竞选</text>
            <view class="jc-switch" :class="{ 'jc-switch--on': rules.sheriffEnabled }" @tap="toggleSheriff">
              <view class="jc-switch__dot" :class="{ 'jc-switch__dot--on': rules.sheriffEnabled }" />
            </view>
          </view>
          <view v-for="item in ruleSteps" :key="item.key" class="jc-rule">
            <text class="jc-rule__label">{{ item.label }}</text>
            <view class="jc-stepper">
              <view class="jc-stepper__btn" @tap="stepValue(item.key, -1)">-</view>
              <text class="jc-stepper__value">{{ rules[item.key] }} 秒</text>
              <view class="jc-stepper__btn" @tap="stepValue(item.key, 1)">+</view>
            </view>
          </view>
        </view>
      </view>

      <view class="nt-bar">
        <view class="nt-btn nt-btn--gold jc-create" :class="{ 'nt-btn--disabled': creating || !selectedDetail }" @tap="createRoom">
          {{ creating ? '正在建房…' : '☾ 创建并进入房间' }}
        </view>
      </view>
    </block>
    <RuleSheet :visible="commonOpen" title="通用规则" hint="所有板子都一样" :sections="commonSections" @close="commonOpen = false" />
  </view>
</template>

<style scoped lang="scss">
.jc-hint {
  display: block;
  margin-top: 16rpx;
  font-size: 24rpx;
  line-height: 1.6;
  color: var(--text-2);
}

.jc-rule {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 88rpx;
  border-top: 2rpx solid var(--panel-line);
}

.jc-rule__label {
  font-size: 28rpx;
  color: var(--text);
}

.jc-switch {
  position: relative;
  width: 88rpx;
  height: 48rpx;
  border-radius: 24rpx;
  background: var(--line);
}

.jc-switch--on {
  background: var(--moon);
}

.jc-switch__dot {
  position: absolute;
  top: 6rpx;
  left: 6rpx;
  width: 36rpx;
  height: 36rpx;
  border-radius: 18rpx;
  background: var(--text);
  transition: left 0.15s;
}

.jc-switch__dot--on {
  left: 46rpx;
}

.jc-stepper {
  display: flex;
  align-items: center;
  gap: 12rpx;
}

.jc-stepper__btn {
  width: 56rpx;
  height: 56rpx;
  border-radius: 16rpx;
  background: var(--panel-raised);
  color: var(--text);
  font-size: 32rpx;
  line-height: 56rpx;
  text-align: center;
}

.jc-stepper__value {
  min-width: 112rpx;
  text-align: center;
  font-size: 28rpx;
  color: var(--text);
}

.jc-preview {
  position: relative;
  margin-top: 16rpx;
  height: 400rpx;
  border-radius: 40rpx;
  overflow: hidden;
  background: var(--panel-sunk);
}

.jc-preview__bg {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
}

.jc-preview__card {
  position: absolute;
  width: 136rpx;
  border-radius: 18rpx;
  box-shadow: 4rpx 4rpx 0 var(--ink);
}

.jc-preview__card--l1 {
  left: 170rpx;
  top: 60rpx;
  transform: rotate(-9deg);
}

.jc-preview__card--c {
  left: 263rpx;
  top: 32rpx;
  z-index: 2;
}

.jc-preview__card--r1 {
  left: 356rpx;
  top: 60rpx;
  transform: rotate(9deg);
}

.jc-preview__plate {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 3;
  padding: 22rpx 32rpx;
  background: var(--ink);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.jc-preview__copy {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
  min-width: 0;
}

.jc-preview__eyebrow {
  color: var(--gold);
  font-size: 24rpx;
  font-weight: 700;
}

.jc-preview__name {
  font-size: 40rpx;
  font-weight: 900;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.jc-preview__meta {
  flex-shrink: 0;
}

.jc-body {
  margin-top: 32rpx;
}

.jc-moderow {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.jc-seg {
  display: flex;
  border-radius: 28rpx;
  background: var(--panel-sunk);
  border: 3rpx solid var(--line);
  overflow: hidden;
}

.jc-seg__item {
  height: 88rpx;
  padding: 0 32rpx;
  border-radius: 26rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28rpx;
  color: var(--text-2);
}

.jc-seg__item--on {
  background: var(--text);
  color: var(--ink);
  font-weight: 700;
}

.jc-seg__item--off {
  opacity: 0.45;
}

.jc-board {
  display: flex;
  align-items: center;
  gap: 20rpx;
  min-height: 112rpx;
  padding: 18rpx 24rpx;
  border-radius: 28rpx;
  background: var(--panel-raised);
  border: 4rpx solid transparent;
  box-sizing: border-box;
}

.jc-board--on {
  border-color: var(--moon);
}

.jc-board__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.jc-board__name {
  font-size: 30rpx;
  font-weight: 700;
}

.jc-board__stack {
  display: flex;
  flex-shrink: 0;
}

.jc-board__token {
  position: relative;
  width: 56rpx;
  height: 56rpx;
}

.jc-board__text {
  font-size: 24rpx;
}

.jc-board__check {
  width: 40rpx;
  color: var(--moon);
  font-size: 36rpx;
  font-weight: 900;
  text-align: center;
  flex-shrink: 0;
}

.jc-create {
  flex: 1;
}

.jc-steps {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding-top: 8rpx;
  padding-bottom: 24rpx;
}

.jc-steps__item {
  display: flex;
  align-items: center;
  gap: 12rpx;
  color: var(--text-3);
}

.jc-steps__no {
  width: 44rpx;
  height: 44rpx;
  border-radius: 50%;
  border: 2rpx solid var(--panel-line);
  font-size: 24rpx;
  font-weight: 700;
  line-height: 40rpx;
  text-align: center;
  box-sizing: border-box;
}

.jc-steps__label {
  font-size: 26rpx;
  font-weight: 700;
}

.jc-steps__item--on {
  color: var(--text);
}

.jc-steps__item--on .jc-steps__no {
  background: var(--gold);
  border-color: var(--gold);
  color: var(--ink);
}

.jc-steps__item--done .jc-steps__no {
  border-color: var(--moon);
  color: var(--moon);
}

.jc-steps__line {
  flex: 1;
  height: 2rpx;
  background: var(--panel-line);
}

.jc-pick {
  display: flex;
  align-items: center;
  gap: 24rpx;
  padding: 20rpx;
  border-radius: 32rpx;
  background: var(--panel);
  box-sizing: border-box;
}

.jc-pick--on {
  border-color: var(--moon);
}

.jc-pick__art {
  position: relative;
  flex-shrink: 0;
  width: 168rpx;
  height: 168rpx;
  border-radius: 24rpx;
  overflow: hidden;
  background: var(--panel-sunk);
}

.jc-pick__card {
  position: absolute;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
}

.jc-pick__bg {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
}

.jc-pick__bg--top {
  object-position: top;
}

.jc-pick__count {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 24rpx 12rpx 6rpx;
  font-size: 40rpx;
  font-weight: 900;
  color: var(--text);
  background: linear-gradient(to bottom, rgba(10, 9, 16, 0), rgba(10, 9, 16, 0.92));
}

.jc-pick__unit {
  margin-left: 4rpx;
  font-size: 22rpx;
}

.jc-pick__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.jc-pick__name {
  font-size: 34rpx;
  font-weight: 700;
}

.jc-pick__stack {
  margin-top: 6rpx;
}

.jc-pick__go {
  flex-shrink: 0;
  font-size: 48rpx;
  color: var(--text-3);
}

.jc-change {
  font-size: 26rpx;
  font-weight: 700;
  color: var(--moon);
}

.jc-info {
  display: flex;
  gap: 16rpx;
}

.jc-info__item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6rpx;
  padding: 20rpx 8rpx;
  border-radius: 24rpx;
  background: var(--panel-raised);
}

.jc-info__value {
  font-size: 32rpx;
  font-weight: 700;
  color: var(--gold);
}

.jc-info__label {
  font-size: 22rpx;
  color: var(--text-3);
}

.jc-lineup {
  display: flex;
  flex-wrap: wrap;
  gap: 20rpx 12rpx;
  margin-top: 24rpx;
}

.jc-lineup__item {
  width: 120rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8rpx;
}

.jc-lineup__token {
  width: 80rpx;
  height: 80rpx;
}

.jc-lineup__name {
  font-size: 22rpx;
  color: var(--text-2);
  text-align: center;
}

.jc-lineup__count {
  color: var(--gold);
  font-weight: 700;
}

.jc-rule--column {
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  gap: 16rpx;
  padding: 20rpx 0 24rpx;
}

.jc-order {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12rpx 8rpx;
}

.jc-order__step {
  padding: 6rpx 18rpx;
  border-radius: 999rpx;
  background: var(--panel-raised);
  font-size: 24rpx;
  color: var(--text);
}

.jc-order__arrow {
  font-size: 28rpx;
  color: var(--text-4);
}

.jc-config__value {
  flex: 1;
  margin-left: 24rpx;
  font-size: 26rpx;
  line-height: 1.5;
  color: var(--text);
  text-align: right;
}

.jc-overview {
  display: block;
  padding-bottom: 20rpx;
  font-size: 28rpx;
  line-height: 1.7;
  color: var(--text-soft);
}

.jc-roles {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 24rpx;
}

.jc-role {
  display: flex;
  align-items: flex-start;
  gap: 20rpx;
}

.jc-role__token {
  width: 72rpx;
  height: 72rpx;
  flex-shrink: 0;
}

.jc-role__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.jc-role__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16rpx;
}

.jc-role__name {
  font-size: 28rpx;
  font-weight: 700;
}

.jc-role__more {
  flex-shrink: 0;
  font-size: 24rpx;
  color: var(--moon);
}

.jc-role__line {
  display: block;
  font-size: 26rpx;
  line-height: 1.6;
  color: var(--text-soft);
}

.jc-common {
  min-height: 112rpx;
  border-top: 2rpx solid var(--panel-line);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24rpx;
}

.jc-common__text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}

.jc-common__label {
  font-size: 28rpx;
  color: var(--text);
}

.jc-common__sub {
  font-size: 24rpx;
  line-height: 1.5;
  color: var(--text-2);
}

.jc-common__go {
  flex-shrink: 0;
  font-size: 26rpx;
  font-weight: 700;
  color: var(--moon);
}

.jc-fold {
  border-top: 2rpx solid var(--panel-line);
}

.jc-fold__head {
  min-height: 88rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.jc-fold__value {
  margin-left: auto;
  font-size: 26rpx;
  color: var(--text);
}

.jc-fold__body {
  padding-bottom: 24rpx;
}
</style>
