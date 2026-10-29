<script setup lang="ts">
import { computed, ref } from 'vue'
import CampBar from '../ui/CampBar.vue'
import type { Board, Role } from '../../types'
import { campSegments, defaultNightOrder, isWolfTeam, pickLineupArt, roleImage, sortLineup } from '../../utils/board'
import { resolveMediaUrl } from '../../utils/media'

// 编辑页右侧的小程序预览：详情页顶部 / 列表卡片，跟着表单实时变
const props = defineProps<{
  board: Board
  rolesById: Map<number, Role>
  featuredAt: number
}>()

const mode = ref<'detail' | 'card'>('detail')

const hero = computed(() => resolveMediaUrl(props.board.backgroundImage || props.board.coverImage))
const cardTop = computed(() => resolveMediaUrl(props.board.backgroundImage))
const fan = computed(() => {
  const picked = pickLineupArt(props.board.roles, props.rolesById).map((item) => resolveMediaUrl(roleImage(item.role)))
  while (picked.length < 3) picked.push('')
  return picked.slice(0, 3)
})
const segments = computed(() => campSegments(props.board.roles, props.rolesById))
const cards = computed(() =>
  sortLineup(props.board.roles, props.rolesById)
    .map((item) => props.rolesById.get(item.roleId))
    .filter((role): role is Role => Boolean(role))
    .map((role) => ({ id: role.id, name: role.name, image: resolveMediaUrl(roleImage(role)) })),
)
const description = computed(
  () => props.board.cardDescription?.trim() || props.board.tips.find((tip) => tip.trim()) || '还没写列表简介',
)

/** 法官主持台实际会走的夜间步骤：配了就用配的，狼队合成一步；没配用通用顺序。 */
const nightSummary = computed(() => {
  const configured = props.board.ruleDetail.nightOrder
  const steps = configured.length ? configured : defaultNightOrder(props.board.roles, props.rolesById)
  const names: string[] = []
  let wolfDone = false
  steps.forEach((step) => {
    const role = props.rolesById.get(step.roleId)
    if (!role) return
    if (isWolfTeam(role)) {
      if (wolfDone) return
      wolfDone = true
      names.push('狼队')
      return
    }
    names.push(role.name)
  })
  return { names, generic: !configured.length }
})
</script>

<template>
  <aside class="pv" aria-label="小程序预览">
    <div class="pv__head">
      <span class="nt-eyebrow">小程序预览</span>
      <div class="nt-seg pv__seg" role="tablist" aria-label="预览哪一页">
        <button type="button" role="tab" :aria-selected="mode === 'detail'" :class="{ on: mode === 'detail' }" @click="mode = 'detail'">详情页</button>
        <button type="button" role="tab" :aria-selected="mode === 'card'" :class="{ on: mode === 'card' }" @click="mode = 'card'">列表卡片</button>
      </div>
    </div>

    <div v-if="mode === 'detail'" class="phone">
      <div class="phone__hero">
        <img v-if="hero" :src="hero" alt="" />
        <div v-else class="phone__fan">
          <span v-for="(image, index) in fan" :key="index" :class="[`fan fan--${index}`, { 'nt-cardback': !image }]"><img v-if="image" :src="image" alt="" /></span>
        </div>
        <div class="phone__fade"></div>
        <span class="phone__back" aria-hidden="true"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="m15 6-6 6 6 6" /></svg></span>
      </div>
      <div class="phone__body">
        <strong class="phone__name">{{ board.name || '未命名板子' }}</strong>
        <div class="phone__chips">
          <span>{{ board.playerCount }} 人</span><span>{{ board.difficulty }}</span><span>{{ board.winCondition || '屠边' }}</span>
        </div>
        <CampBar v-if="segments.length" :segments="segments" :height="6" :legend="false" />
        <div class="phone__cards">
          <div v-for="card in cards" :key="card.id" class="mini" :class="{ 'nt-cardback': !card.image }">
            <img v-if="card.image" :src="card.image" alt="" />
            <span>{{ card.name }}</span>
          </div>
        </div>
        <div class="phone__panel">
          <strong>夜间行动顺序<small v-if="nightSummary.generic">（通用）</small></strong>
          <span v-if="nightSummary.names.length">{{ nightSummary.names.map((name, index) => `${index + 1} ${name}`).join(' → ') }}</span>
          <span v-else>阵容里没有夜里要睁眼的角色</span>
        </div>
        <div v-if="board.ruleDetail.overview" class="phone__panel">
          <strong>玩法概述</strong>
          <span class="clamp">{{ board.ruleDetail.overview }}</span>
        </div>
      </div>
      <div class="phone__bar">
        <span class="phone__icon" aria-hidden="true"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z" /></svg></span>
        <span class="phone__start">用这个板子开局</span>
        <span class="phone__note">记一局</span>
      </div>
    </div>

    <div v-else class="card">
      <div class="card__top">
        <img v-if="cardTop" :src="cardTop" alt="" />
        <div v-else class="phone__fan">
          <span v-for="(image, index) in fan" :key="index" :class="[`fan fan--${index}`, { 'nt-cardback': !image }]"><img v-if="image" :src="image" alt="" /></span>
        </div>
        <div class="phone__fade"></div>
      </div>
      <div class="card__body">
        <strong class="phone__name">{{ board.name || '未命名板子' }}</strong>
        <span class="card__meta">{{ board.playerCount }} 人 · {{ board.difficulty }} · {{ board.tags.join(' · ') || '无标签' }}</span>
        <CampBar v-if="segments.length" :segments="segments" :height="6" :legend="false" />
        <p class="card__desc">{{ description }}</p>
      </div>
    </div>

    <dl class="facts">
      <div><dt>首页「今晚推荐」</dt><dd :class="{ gold: featuredAt }">{{ board.status !== 1 ? '已下架，不展示' : featuredAt ? `第 ${featuredAt} 位` : '排在 5 位以外' }}</dd></div>
      <div><dt>列表卡片顶部</dt><dd>{{ board.backgroundImage ? '用背景图' : '摆三张阵容立绘' }}</dd></div>
      <div><dt>详情页顶部</dt><dd>{{ board.backgroundImage ? '背景图' : board.coverImage ? '封面图' : '阵容立绘' }}</dd></div>
    </dl>
  </aside>
</template>

<style scoped>
.pv {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.pv__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.pv__seg {
  padding: 3px;
}

.pv__seg button {
  height: 28px;
  font-size: 13px;
}

.phone,
.card {
  width: 340px;
  max-width: 100%;
  align-self: center;
  border-radius: 40px;
  border: 3px solid var(--ink);
  box-shadow: 6px 6px 0 var(--ink);
  background: var(--bg);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.card {
  border-radius: 24px;
}

.phone__hero,
.card__top {
  position: relative;
  height: 210px;
  background: #1b1724;
  overflow: hidden;
}

.card__top {
  height: 160px;
}

.phone__hero > img,
.card__top > img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.phone__fan {
  position: absolute;
  inset: 24px 0 0;
  display: flex;
  align-items: flex-end;
  justify-content: center;
}

.fan {
  width: 80px;
  height: 108px;
  border-radius: 10px;
  border: 2px solid var(--ink);
  overflow: hidden;
}

.fan img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: 50% 12%;
}

.fan--0 {
  transform: rotate(-8deg) translateX(12px);
}

.fan--1 {
  z-index: 2;
  width: 92px;
  height: 124px;
  transform: translateY(-6px);
}

.fan--2 {
  transform: rotate(8deg) translateX(-12px);
}

.phone__fade {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgba(21, 18, 28, 0) 45%, var(--bg) 100%);
}

.phone__back {
  position: absolute;
  top: 40px;
  left: 14px;
  width: 30px;
  height: 30px;
  border-radius: 10px;
  background: rgba(14, 12, 19, 0.7);
  display: flex;
  align-items: center;
  justify-content: center;
}

.phone__body,
.card__body {
  position: relative;
  margin-top: -26px;
  padding: 0 16px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.card__body {
  margin-top: -18px;
}

.phone__name {
  font: 900 26px var(--serif);
  overflow-wrap: anywhere;
}

.phone__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.phone__chips span {
  height: 24px;
  padding: 0 10px;
  border-radius: 12px;
  background: var(--panel-raised);
  font-size: 12px;
  display: inline-flex;
  align-items: center;
}

.phone__cards {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding-bottom: 4px;
  scrollbar-width: thin;
}

.mini {
  position: relative;
  flex: none;
  width: 66px;
  height: 88px;
  border-radius: 10px;
  border: 2px solid var(--ink);
  overflow: hidden;
}

.mini img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: 50% 12%;
}

.mini span {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 2px 0;
  background: rgba(7, 6, 10, 0.75);
  font-size: 10px;
  text-align: center;
}

.phone__panel {
  padding: 12px;
  border-radius: 14px;
  background: var(--panel);
  border: 1px solid var(--panel-line);
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-2);
}

.phone__panel strong {
  font: 900 14px var(--serif);
  color: var(--text);
}

.phone__panel small {
  font: 12px var(--sans);
  color: var(--text-3);
}

.clamp {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.phone__bar {
  margin-top: auto;
  padding: 10px 12px 18px;
  background: var(--bar-bg);
  display: flex;
  gap: 8px;
  align-items: center;
}

.phone__icon,
.phone__note,
.phone__start {
  height: 38px;
  border-radius: 12px;
  border: 2px solid var(--ink);
  font-size: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.phone__icon {
  width: 38px;
  flex: none;
  background: var(--panel-raised);
}

.phone__start {
  flex: 1;
  background: var(--gold);
  color: var(--ink);
  font-weight: 900;
}

.phone__note {
  padding: 0 12px;
  background: var(--panel-raised);
  font-weight: 700;
}

.card__meta {
  font-size: 12px;
  color: var(--text-2);
}

.card__desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-soft);
}

.facts {
  margin: 0;
  padding: 14px 16px;
  border-radius: 16px;
  background: var(--panel);
  border: 1px solid var(--panel-line);
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 13px;
}

.facts div {
  display: flex;
  justify-content: space-between;
  gap: 10px;
}

.facts dt {
  color: var(--text-2);
}

.facts dd {
  margin: 0;
  font-weight: 700;
}

.facts dd.gold {
  color: var(--gold);
}
</style>
