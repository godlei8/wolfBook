<script setup>
// 对局记录：房间页右上角「记录」打开的底部面板，白天夜里都能看。
// 「按天看」每天一张夜卡、一张白天卡；「按人看」每人一行经历。人都是「头像 + 座位号角标」。
// 对局中只有公开信息，结束后多出夜间行动、死因和身份（判断都在 utils/judge/game-log.js）。
// 头像是内联结构（自定义组件里不嵌组件）。
import { computed, ref } from 'vue'
import { gameLog } from '../../../utils/judge/game-log.js'

const props = defineProps({
  snapshot: { type: Object, required: true },
})
const emit = defineEmits(['close'])

const tab = ref('day')
const log = computed(() => gameLog(props.snapshot))
</script>

<template>
  <view class="jl-mask" @tap="emit('close')">
    <view class="jl-sheet" @tap.stop>
      <view class="jl-head">
        <view class="jl-head__row">
          <text class="jl-head__title serif">对局记录</text>
          <view class="jl-close" @tap="emit('close')">关闭</view>
        </view>
        <view v-if="log.verdict" class="jl-verdict" :class="`jl-verdict--${log.verdict.camp}`">
          <text class="jl-verdict__title">{{ log.verdict.title }}</text>
          <text class="jl-verdict__reason">{{ log.verdict.reason ? `${log.verdict.reason} · ` : '' }}{{ log.verdict.days }}</text>
        </view>
        <view class="jl-tabs">
          <view class="jl-tab" :class="{ 'jl-tab--on': tab === 'day' }" @tap="tab = 'day'">按天看</view>
          <view class="jl-tab" :class="{ 'jl-tab--on': tab === 'people' }" @tap="tab = 'people'">按人看</view>
        </view>
      </view>

      <scroll-view scroll-y class="jl-body">
        <view v-if="!log.days.length" class="jl-empty">还没有记录</view>

        <block v-else-if="tab === 'day'">
          <view v-for="day in log.days" :key="day.dayNo" class="jl-day">
            <view class="jl-card jl-card--night">
              <view class="jl-card__head">
                <text class="jl-card__icon jl-card__icon--moon">☾</text>
                <text class="jl-card__title serif">{{ day.night.title }}</text>
                <text class="jl-card__result" :class="{ 'jl-card__result--calm': !day.night.deaths.length }">{{ day.night.result }}</text>
              </view>
              <view class="jl-card__body">
                <view v-for="death in day.night.deaths" :key="`d${death.person.seatNo}`" class="jl-death">
                  <view class="jl-av jl-av--m jl-av--dead" :style="{ backgroundColor: death.person.color }">
                    <image v-if="death.person.avatar" class="jl-av__img" :src="death.person.avatar" mode="aspectFill" />
                    <text v-else class="jl-av__ch">{{ death.person.initial }}</text>
                    <text class="jl-av__no">{{ death.person.seatNo }}</text>
                  </view>
                  <view class="jl-death__who">
                    <text class="jl-death__name">{{ death.person.seatNo }} 号 {{ death.person.nickname }} 出局</text>
                    <text v-if="death.role" class="jl-death__role">{{ death.role }}</text>
                  </view>
                  <text v-if="death.cause" class="jl-death__cause">{{ death.cause }}</text>
                </view>
                <view v-for="(act, index) in day.night.actions" :key="`a${index}`" class="jl-act">
                  <view class="jl-stack jl-stack--actors">
                    <view
                      v-for="actor in act.actors"
                      :key="actor.seatNo"
                      class="jl-av jl-av--s jl-stack__item"
                      :style="{ backgroundColor: actor.color }"
                    >
                      <image v-if="actor.avatar" class="jl-av__img" :src="actor.avatar" mode="aspectFill" />
                      <text v-else class="jl-av__ch">{{ actor.initial }}</text>
                      <text class="jl-av__no">{{ actor.seatNo }}</text>
                    </view>
                  </view>
                  <text class="jl-act__role" :class="act.camp ? `jl-camp--${act.camp}` : ''">{{ act.role }}</text>
                  <text class="jl-act__verb">{{ act.verb }}</text>
                  <block v-if="act.target">
                    <text class="jl-act__arrow">→</text>
                    <view class="jl-av jl-av--s" :style="{ backgroundColor: act.target.color }">
                      <image v-if="act.target.avatar" class="jl-av__img" :src="act.target.avatar" mode="aspectFill" />
                      <text v-else class="jl-av__ch">{{ act.target.initial }}</text>
                      <text class="jl-av__no">{{ act.target.seatNo }}</text>
                    </view>
                  </block>
                  <text v-if="act.note" class="jl-act__note">{{ act.note }}</text>
                </view>
                <text v-if="!day.night.deaths.length && !day.night.actions.length" class="jl-muted">没有人出局</text>
              </view>
            </view>

            <view v-if="day.day" class="jl-card jl-card--day">
              <view class="jl-card__head">
                <text class="jl-card__icon jl-card__icon--sun">☀</text>
                <text class="jl-card__title serif">{{ day.day.title }}</text>
                <text v-if="day.day.result" class="jl-card__result jl-card__result--gold">{{ day.day.result }}</text>
              </view>
              <view class="jl-card__body jl-card__body--day">
                <view v-if="day.day.sheriff" class="jl-block">
                  <text class="jl-block__label">警长竞选</text>
                  <view class="jl-sheriff">
                    <text class="jl-sheriff__lead">上警</text>
                    <view
                      v-for="candidate in day.day.sheriff.candidates"
                      :key="candidate.person.seatNo"
                      class="jl-av jl-av--m"
                      :class="{ 'jl-av--elected': candidate.elected, 'jl-av--withdrew': candidate.withdrew }"
                      :style="{ backgroundColor: candidate.person.color }"
                    >
                      <image v-if="candidate.person.avatar" class="jl-av__img" :src="candidate.person.avatar" mode="aspectFill" />
                      <text v-else class="jl-av__ch">{{ candidate.person.initial }}</text>
                      <text class="jl-av__no">{{ candidate.person.seatNo }}</text>
                    </view>
                    <text v-if="!day.day.sheriff.candidates.length" class="jl-muted">没有人上警</text>
                    <text class="jl-sheriff__result">★ {{ day.day.sheriff.result }}</text>
                  </view>
                </view>

                <view v-for="(vote, voteIndex) in day.day.votes" :key="`v${voteIndex}`" class="jl-block">
                  <view class="jl-block__row">
                    <text class="jl-block__label">{{ vote.label }}</text>
                    <text class="jl-block__result">{{ vote.result }}</text>
                  </view>
                  <view v-for="bar in vote.bars" :key="bar.person.seatNo" class="jl-bar">
                    <view class="jl-bar__row">
                      <view class="jl-av jl-av--m" :style="{ backgroundColor: bar.person.color }">
                        <image v-if="bar.person.avatar" class="jl-av__img" :src="bar.person.avatar" mode="aspectFill" />
                        <text v-else class="jl-av__ch">{{ bar.person.initial }}</text>
                        <text class="jl-av__no">{{ bar.person.seatNo }}</text>
                      </view>
                      <view class="jl-bar__track">
                        <view class="jl-bar__fill" :class="{ 'jl-bar__fill--top': bar.top }" :style="{ width: `${bar.pct}%` }"></view>
                      </view>
                      <text class="jl-bar__count">{{ bar.count }}</text>
                    </view>
                    <view class="jl-bar__voters">
                      <text class="jl-bar__hint">投票的人</text>
                      <view class="jl-stack">
                        <view
                          v-for="voter in bar.voters"
                          :key="voter.seatNo"
                          class="jl-av jl-av--xs jl-stack__item"
                          :style="{ backgroundColor: voter.color }"
                        >
                          <image v-if="voter.avatar" class="jl-av__img" :src="voter.avatar" mode="aspectFill" />
                          <text v-else class="jl-av__ch">{{ voter.initial }}</text>
                          <text class="jl-av__no">{{ voter.seatNo }}</text>
                        </view>
                      </view>
                    </view>
                  </view>
                  <view v-if="vote.abstain.length" class="jl-bar__voters jl-bar__voters--abstain">
                    <text class="jl-bar__hint">弃票</text>
                    <view class="jl-stack">
                      <view
                        v-for="voter in vote.abstain"
                        :key="voter.seatNo"
                        class="jl-av jl-av--xs jl-stack__item"
                        :style="{ backgroundColor: voter.color }"
                      >
                        <image v-if="voter.avatar" class="jl-av__img" :src="voter.avatar" mode="aspectFill" />
                        <text v-else class="jl-av__ch">{{ voter.initial }}</text>
                        <text class="jl-av__no">{{ voter.seatNo }}</text>
                      </view>
                    </view>
                  </view>
                </view>

                <view v-for="(event, eventIndex) in day.day.events" :key="`e${eventIndex}`" class="jl-event">
                  <text class="jl-event__tag" :class="`jl-event__tag--${event.kind}`">{{ event.tag }}</text>
                  <view v-if="event.a" class="jl-av jl-av--s" :style="{ backgroundColor: event.a.color }">
                    <image v-if="event.a.avatar" class="jl-av__img" :src="event.a.avatar" mode="aspectFill" />
                    <text v-else class="jl-av__ch">{{ event.a.initial }}</text>
                    <text class="jl-av__no">{{ event.a.seatNo }}</text>
                  </view>
                  <text class="jl-event__text">{{ event.text }}</text>
                  <view v-if="event.b" class="jl-av jl-av--s" :style="{ backgroundColor: event.b.color }">
                    <image v-if="event.b.avatar" class="jl-av__img" :src="event.b.avatar" mode="aspectFill" />
                    <text v-else class="jl-av__ch">{{ event.b.initial }}</text>
                    <text class="jl-av__no">{{ event.b.seatNo }}</text>
                  </view>
                </view>
              </view>
            </view>
          </view>
        </block>

        <block v-else>
          <view class="jl-people">
            <view v-for="row in log.people" :key="row.person.seatNo" class="jl-person" :class="{ 'jl-person--me': row.me }">
              <view
                class="jl-av jl-av--l"
                :class="[{ 'jl-av--dead': row.dead }, row.camp ? `jl-ring--${row.camp}` : '']"
                :style="{ backgroundColor: row.person.color }"
              >
                <image v-if="row.person.avatar" class="jl-av__img" :src="row.person.avatar" mode="aspectFill" />
                <text v-else class="jl-av__ch">{{ row.person.initial }}</text>
                <text class="jl-av__no">{{ row.person.seatNo }}</text>
              </view>
              <view class="jl-person__main">
                <view class="jl-person__line">
                  <text class="jl-person__name">{{ row.person.nickname || `${row.person.seatNo} 号` }}{{ row.me ? '（我）' : '' }}</text>
                  <text v-if="row.role" class="jl-person__role" :class="row.camp ? `jl-camp--${row.camp}` : ''">{{ row.role }}</text>
                </view>
                <text class="jl-person__story">{{ row.story }}</text>
              </view>
              <text class="jl-fate" :class="{ 'jl-fate--dead': row.dead }">{{ row.fate }}</text>
            </view>
          </view>
          <text v-if="!log.verdict" class="jl-foot">对局结束后能看到每个人的身份和夜里做了什么</text>
        </block>
      </scroll-view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.jl-mask {
  position: fixed;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 70;
  background: var(--scrim);
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
}

.jl-sheet {
  background: var(--bg);
  border-top: 2rpx solid var(--panel-line);
  border-radius: 36rpx 36rpx 0 0;
  padding-bottom: env(safe-area-inset-bottom);
  display: flex;
  flex-direction: column;
}

.jl-head {
  padding: 32rpx 32rpx 24rpx;
  display: flex;
  flex-direction: column;
  gap: 24rpx;
  border-bottom: 2rpx solid var(--panel-line);
}

.jl-head__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.jl-head__title {
  font-size: 44rpx;
  font-weight: 700;
  color: var(--text);
}

.jl-close {
  height: 72rpx;
  padding: 0 28rpx;
  border-radius: 36rpx;
  border: 2rpx solid var(--line-strong);
  color: var(--text-2);
  font-size: 28rpx;
  line-height: 68rpx;
}

.jl-verdict {
  padding: 22rpx 28rpx;
  border-radius: 28rpx;
  border: 2rpx solid var(--line-strong);
  background: var(--panel);
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.jl-verdict--wolf {
  border-color: var(--camp-wolf);
  background: rgba(255, 116, 104, 0.1);
}

.jl-verdict--good {
  border-color: var(--camp-villager);
  background: rgba(143, 208, 166, 0.1);
}

.jl-verdict__title {
  font-size: 32rpx;
  font-weight: 700;
  color: var(--text);
}

.jl-verdict--wolf .jl-verdict__title {
  color: var(--camp-wolf);
}

.jl-verdict--good .jl-verdict__title {
  color: var(--camp-villager);
}

.jl-verdict__reason {
  font-size: 24rpx;
  color: var(--text-2);
}

.jl-tabs {
  display: flex;
  padding: 6rpx;
  border-radius: 40rpx;
  background: var(--panel);
  border: 2rpx solid var(--panel-line);
}

.jl-tab {
  flex: 1;
  height: 72rpx;
  border-radius: 36rpx;
  color: var(--text-2);
  font-size: 28rpx;
  line-height: 72rpx;
  text-align: center;
}

.jl-tab--on {
  background: var(--gold);
  color: var(--ink);
  font-weight: 700;
}

.jl-body {
  height: 62vh;
}

.jl-empty,
.jl-foot {
  display: block;
  padding: 48rpx 32rpx;
  text-align: center;
  color: var(--text-3);
  font-size: 24rpx;
}

.jl-day {
  padding: 28rpx 32rpx 0;
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}

.jl-card {
  border-radius: 32rpx;
  border: 2rpx solid var(--panel-line);
  overflow: hidden;
}

.jl-card--night {
  background: var(--panel-sunk);
}

.jl-card--day {
  background: var(--panel);
}

.jl-card__head {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 22rpx 28rpx;
  background: var(--panel-raised);
}

.jl-card__icon {
  font-size: 32rpx;
}

.jl-card__icon--moon {
  color: var(--moon);
}

.jl-card__icon--sun {
  color: var(--gold);
}

.jl-card__title {
  font-size: 32rpx;
  font-weight: 700;
  color: var(--text);
}

.jl-card__result {
  margin-left: auto;
  font-size: 26rpx;
  font-weight: 700;
  color: var(--camp-wolf);
}

.jl-card__result--calm {
  color: var(--camp-villager);
}

.jl-card__result--gold {
  color: var(--gold);
}

.jl-card__body {
  padding: 8rpx 28rpx 20rpx;
  display: flex;
  flex-direction: column;
}

.jl-card__body--day {
  padding-top: 24rpx;
  gap: 32rpx;
}

.jl-muted {
  padding: 16rpx 0 4rpx;
  font-size: 24rpx;
  color: var(--text-3);
}

/* 头像 + 座位号角标 */
.jl-av {
  position: relative;
  flex-shrink: 0;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--bg);
  font-weight: 700;
}

.jl-av__img {
  width: 100%;
  height: 100%;
  border-radius: 50%;
}

.jl-av__ch {
  font-weight: 700;
}

.jl-av__no {
  position: absolute;
  right: -10rpx;
  bottom: -8rpx;
  min-width: 32rpx;
  height: 32rpx;
  padding: 0 6rpx;
  box-sizing: border-box;
  border-radius: 16rpx;
  background: var(--ink);
  border: 2rpx solid var(--line-strong);
  color: var(--text);
  font-size: 20rpx;
  font-weight: 700;
  line-height: 28rpx;
  text-align: center;
}

.jl-av--xs {
  width: 44rpx;
  height: 44rpx;
  font-size: 20rpx;
}

.jl-av--xs .jl-av__no {
  min-width: 26rpx;
  height: 26rpx;
  right: -8rpx;
  bottom: -6rpx;
  font-size: 16rpx;
  line-height: 22rpx;
}

.jl-av--s {
  width: 52rpx;
  height: 52rpx;
  font-size: 24rpx;
}

.jl-av--m {
  width: 60rpx;
  height: 60rpx;
  font-size: 26rpx;
}

.jl-av--l {
  width: 76rpx;
  height: 76rpx;
  font-size: 30rpx;
}

.jl-av--dead {
  filter: grayscale(1);
  opacity: 0.55;
}

.jl-av--elected {
  box-shadow: 0 0 0 4rpx var(--gold);
}

.jl-av--withdrew {
  opacity: 0.45;
}

.jl-ring--wolf {
  box-shadow: 0 0 0 4rpx var(--camp-wolf);
}

.jl-ring--god {
  box-shadow: 0 0 0 4rpx var(--camp-god);
}

.jl-ring--villager {
  box-shadow: 0 0 0 4rpx var(--camp-villager);
}

.jl-ring--third {
  box-shadow: 0 0 0 4rpx var(--camp-third);
}

.jl-camp--wolf {
  color: var(--camp-wolf);
}

.jl-camp--god {
  color: var(--camp-god);
}

.jl-camp--villager {
  color: var(--camp-villager);
}

.jl-camp--third {
  color: var(--camp-third);
}

/* 多个人并排：不重叠（重叠会盖住座位号角标），放不下就换行 */
.jl-stack {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx 22rpx;
  padding: 0 10rpx 8rpx 0;
}

.jl-stack__item {
  flex-shrink: 0;
}

.jl-stack--actors {
  flex: none;
  max-width: 260rpx;
}

/* 夜里：出局者 */
.jl-death {
  margin: 16rpx 0 8rpx;
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 16rpx 20rpx;
  border-radius: 20rpx;
  background: rgba(255, 116, 104, 0.1);
}

.jl-death__who {
  display: flex;
  flex-direction: column;
  gap: 2rpx;
  min-width: 0;
}

.jl-death__name {
  font-size: 28rpx;
  font-weight: 700;
  color: var(--text);
}

.jl-death__role {
  font-size: 24rpx;
  color: var(--text-3);
}

.jl-death__cause {
  margin-left: auto;
  flex-shrink: 0;
  font-size: 24rpx;
  padding: 6rpx 16rpx;
  border-radius: 20rpx;
  background: var(--panel-raised);
  color: var(--camp-wolf);
}

/* 夜里：谁 → 谁 */
.jl-act {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 18rpx 0;
  border-bottom: 2rpx solid var(--panel-line);
}

.jl-act__role {
  margin-left: 4rpx;
  width: 100rpx;
  font-size: 26rpx;
  color: var(--text-2);
}

.jl-act__verb {
  font-size: 26rpx;
  color: var(--text-2);
}

.jl-act__arrow {
  font-size: 26rpx;
  color: var(--text-4);
}

.jl-act__note {
  margin-left: auto;
  font-size: 24rpx;
  color: var(--text-3);
}

/* 白天 */
.jl-block {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.jl-block__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.jl-block__label {
  font-size: 24rpx;
  color: var(--text-3);
  letter-spacing: 2rpx;
}

.jl-block__result {
  font-size: 24rpx;
  font-weight: 700;
  color: var(--text-soft);
}

.jl-sheriff {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 20rpx;
}

.jl-sheriff__lead {
  font-size: 26rpx;
  color: var(--text-2);
}

.jl-sheriff__result {
  margin-left: auto;
  font-size: 26rpx;
  font-weight: 700;
  color: var(--gold);
}

.jl-bar {
  display: flex;
  flex-direction: column;
  gap: 10rpx;
}

.jl-bar__row {
  display: flex;
  align-items: center;
  gap: 20rpx;
}

.jl-bar__track {
  flex: 1;
  height: 20rpx;
  border-radius: 10rpx;
  background: var(--panel-raised);
  overflow: hidden;
}

.jl-bar__fill {
  height: 20rpx;
  border-radius: 10rpx;
  background: var(--text-2);
}

.jl-bar__fill--top {
  background: var(--camp-wolf);
}

.jl-bar__count {
  width: 96rpx;
  text-align: right;
  font-size: 26rpx;
  font-weight: 700;
  color: var(--text);
}

.jl-bar__voters {
  padding-left: 80rpx;
  display: flex;
  align-items: flex-start;
  gap: 16rpx;
}

.jl-bar__voters--abstain {
  padding-left: 0;
}

.jl-bar__hint {
  flex-shrink: 0;
  font-size: 22rpx;
  line-height: 44rpx;
  color: var(--text-4);
}

.jl-event {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 16rpx 20rpx;
  border-radius: 20rpx;
  background: var(--panel-sunk);
}

.jl-event__tag {
  font-size: 24rpx;
  font-weight: 700;
  padding: 4rpx 16rpx;
  border-radius: 18rpx;
  background: var(--panel-raised);
  color: var(--text-soft);
}

.jl-event__tag--badge {
  background: rgba(242, 179, 61, 0.16);
  color: var(--gold);
}

.jl-event__tag--wolf {
  background: rgba(255, 116, 104, 0.14);
  color: var(--camp-wolf);
}

.jl-event__text {
  font-size: 26rpx;
  color: var(--text-2);
}

/* 按人看 */
.jl-people {
  padding: 24rpx 32rpx 0;
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.jl-person {
  display: flex;
  align-items: center;
  gap: 24rpx;
  padding: 16rpx 24rpx 20rpx;
  border-radius: 24rpx;
  background: var(--panel);
  border: 2rpx solid var(--panel-line);
}

.jl-person--me {
  border-color: var(--gold);
}

.jl-person__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.jl-person__line {
  display: flex;
  align-items: center;
  gap: 12rpx;
}

.jl-person__name {
  font-size: 28rpx;
  font-weight: 700;
  color: var(--text);
}

.jl-person__role {
  font-size: 24rpx;
  font-weight: 700;
}

.jl-person__story {
  font-size: 24rpx;
  line-height: 1.5;
  color: var(--text-2);
}

.jl-fate {
  flex-shrink: 0;
  font-size: 24rpx;
  font-weight: 700;
  padding: 6rpx 16rpx;
  border-radius: 18rpx;
  background: rgba(143, 208, 166, 0.14);
  color: var(--camp-villager);
}

.jl-fate--dead {
  background: var(--panel-raised);
  color: var(--camp-wolf);
}
</style>
