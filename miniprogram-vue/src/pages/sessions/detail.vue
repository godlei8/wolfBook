<script setup>
import { computed, reactive, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import NavBar from '../../components/NavBar.vue'
import userData from '../../services/user-data'
import { formatDateTime } from '../../utils/format'

const sessionId = ref('')
const session = ref(null)
const players = ref([])
const showEditor = ref(false)
const editorType = ref('seer')

const dayOptions = [1, 2, 3, 4, 5]

const recordTypeMap = {
  seer: '查验记录',
  vote: '投票记录',
  speech: '发言记录',
  wolfPack: '狼坑分析',
}

const editorTitleMap = {
  seer: '记录查验',
  vote: '记录投票',
  speech: '记录发言',
  wolfPack: '记录狼坑',
}

const voteTemplateOptions = [
  { value: 'normal', label: '普通投票' },
  { value: 'tie', label: '平票' },
  { value: 'abstain', label: '弃票' },
  { value: 'sheriff', label: '警长归票' },
]

const recordForm = reactive(makeDefaultForm([]))

function formatDay(day) {
  const numeric = Number(day) || 1
  return `第${numeric}天`
}

function typeLabel(type) {
  return recordTypeMap[type] || '局内记录'
}

function makeDefaultForm(playerList, defaultRound = 1) {
  const first = playerList[0] || '1号'
  const second = playerList[1] || first
  return {
    round: defaultRound,
    player: first,
    content: '',
    voteTemplate: 'normal',
    normalVoters: [],
    normalTarget: second,
    tieTargets: [],
    abstainPlayers: [],
    sheriffPlayer: first,
    sheriffTarget: second,
    sheriffFollowers: [],
    voteRemark: '',
    wolfPack: [],
  }
}

function latestRound(rawSession) {
  const rounds = (rawSession?.records || [])
    .map((item) => Number(item.round) || 1)
    .filter((item) => item > 0)
  const current = rounds.length ? Math.max(...rounds) : 1
  return Math.min(current, dayOptions[dayOptions.length - 1])
}

function joinPlayers(playerList) {
  return (playerList || []).filter(Boolean).join('、')
}

function makeId(prefix) {
  return `${prefix}_${Date.now()}_${Math.random().toString(16).slice(2, 8)}`
}

function buildPlayers(playerCount) {
  return Array.from({ length: Math.max(1, Number(playerCount) || 12) }, (_, index) => `${index + 1}号`)
}

function resetForm(rawSession) {
  Object.assign(recordForm, makeDefaultForm(players.value, latestRound(rawSession)))
}

function syncSessionView(rawSession, options = {}) {
  if (!rawSession) return
  session.value = {
    ...rawSession,
    records: (rawSession.records || []).slice(),
  }
  players.value = buildPlayers(rawSession.playerCount)
  // 编辑弹层打开时（比如切到微信聊天再回来）不能重置表单，否则正在填的记录会丢
  if (!options.keepForm) {
    resetForm(rawSession)
  }
}

const displayRecords = computed(() => {
  return (session.value?.records || [])
    .slice()
    .sort((left, right) => new Date(left.timestamp || 0).getTime() - new Date(right.timestamp || 0).getTime())
    .map((item) => ({
      ...item,
      typeLabel: typeLabel(item.type),
      roundLabel: formatDay(item.round),
      timestampLabel: formatDateTime(item.timestamp),
    }))
})

const latestWolfPack = computed(() => {
  const list = (session.value?.records || []).slice().reverse()
  const latest = list.find((item) => item.type === 'wolfPack')
  return latest?.content || '暂无狼坑记录'
})

async function refreshSession(options = {}) {
  if (!sessionId.value) return
  try {
    const current = await userData.getSessionById(sessionId.value)
    if (!current) {
      session.value = null
      uni.showToast({ title: '对局不存在', icon: 'none' })
      return
    }
    syncSessionView(current, options)
  } catch (error) {
    uni.showToast({ title: error?.message || '笔记加载失败', icon: 'none' })
  }
}

function openEditor(type) {
  editorType.value = type
  resetForm(session.value)
  showEditor.value = true
}

function closeEditor() {
  showEditor.value = false
}

function toggleArray(field, value) {
  const list = Array.isArray(recordForm[field]) ? recordForm[field] : []
  const exists = list.includes(value)
  recordForm[field] = exists ? list.filter((item) => item !== value) : list.concat(value)

  if (field === 'sheriffFollowers' && recordForm.sheriffFollowers.includes(recordForm.sheriffPlayer)) {
    recordForm.sheriffFollowers = recordForm.sheriffFollowers.filter((item) => item !== recordForm.sheriffPlayer)
  }
}

function setSheriffPlayer(value) {
  recordForm.sheriffPlayer = value
  recordForm.sheriffFollowers = recordForm.sheriffFollowers.filter((item) => item !== value)
}

function buildVoteRecord() {
  const remark = recordForm.voteRemark.trim()
  const withRemark = (text) => (remark ? `${text}；备注：${remark}` : text)

  if (recordForm.voteTemplate === 'normal') {
    if (!recordForm.normalVoters.length) return null
    return {
      content: withRemark(`${joinPlayers(recordForm.normalVoters)} 投给 ${recordForm.normalTarget}`),
      player: joinPlayers(recordForm.normalVoters),
    }
  }

  if (recordForm.voteTemplate === 'tie') {
    if (recordForm.tieTargets.length < 2) return null
    return {
      content: withRemark(`形成平票：${joinPlayers(recordForm.tieTargets)}`),
      player: joinPlayers(recordForm.tieTargets),
    }
  }

  if (recordForm.voteTemplate === 'abstain') {
    if (!recordForm.abstainPlayers.length) return null
    return {
      content: withRemark(`${joinPlayers(recordForm.abstainPlayers)} 弃票`),
      player: joinPlayers(recordForm.abstainPlayers),
    }
  }

  if (!recordForm.sheriffPlayer || !recordForm.sheriffTarget) return null
  const followers = recordForm.sheriffFollowers.filter((item) => item !== recordForm.sheriffPlayer)
  const followText = followers.length ? `，${joinPlayers(followers)} 跟票` : ''
  return {
    content: withRemark(`警长 ${recordForm.sheriffPlayer} 归票 ${recordForm.sheriffTarget}${followText}`),
    player: joinPlayers([recordForm.sheriffPlayer].concat(followers)),
  }
}

const votePreview = computed(() => {
  if (editorType.value !== 'vote') return ''
  const result = buildVoteRecord()
  return result ? result.content : '先完成投票信息选择，系统会在这里生成预览。'
})

async function persistSession(nextSession) {
  const saved = await userData.saveSession(nextSession)
  syncSessionView(saved)
}

async function saveRecord() {
  if (!session.value) return

  let record = null
  if (editorType.value === 'seer') {
    if (!recordForm.content.trim()) {
      uni.showToast({ title: '请输入查验内容', icon: 'none' })
      return
    }
    record = {
      id: makeId('rec'),
      type: 'seer',
      round: recordForm.round,
      content: recordForm.content.trim(),
      player: recordForm.player,
      timestamp: new Date().toISOString(),
    }
  } else if (editorType.value === 'speech') {
    if (!recordForm.content.trim()) {
      uni.showToast({ title: '请输入发言内容', icon: 'none' })
      return
    }
    record = {
      id: makeId('rec'),
      type: 'speech',
      round: recordForm.round,
      content: recordForm.content.trim(),
      player: recordForm.player,
      timestamp: new Date().toISOString(),
    }
  } else if (editorType.value === 'wolfPack') {
    if (!recordForm.wolfPack.length) {
      uni.showToast({ title: '请至少选择一名玩家', icon: 'none' })
      return
    }
    record = {
      id: makeId('rec'),
      type: 'wolfPack',
      round: recordForm.round,
      content: `狼坑：${joinPlayers(recordForm.wolfPack)}`,
      player: joinPlayers(recordForm.wolfPack),
      timestamp: new Date().toISOString(),
    }
  } else {
    const result = buildVoteRecord()
    if (!result) {
      uni.showToast({ title: '请完善投票信息', icon: 'none' })
      return
    }
    record = {
      id: makeId('rec'),
      type: 'vote',
      round: recordForm.round,
      content: result.content,
      player: result.player,
      timestamp: new Date().toISOString(),
    }
  }

  try {
    await persistSession({
      ...session.value,
      records: (session.value.records || []).concat(record),
      updateTime: new Date().toISOString(),
    })
    closeEditor()
    uni.showToast({ title: '记录已保存', icon: 'success' })
  } catch (error) {
    uni.showToast({ title: error?.message || '保存失败', icon: 'none' })
  }
}

function removeRecord(id) {
  if (!session.value) return
  uni.showModal({
    title: '删除记录',
    content: '确认删除这条记录吗？',
    success: async (res) => {
      if (!res.confirm || !session.value) return
      try {
        await persistSession({
          ...session.value,
          records: (session.value.records || []).filter((item) => item.id !== id),
          updateTime: new Date().toISOString(),
        })
        uni.showToast({ title: '已删除', icon: 'success' })
      } catch (error) {
        uni.showToast({ title: error?.message || '删除失败', icon: 'none' })
      }
    },
  })
}

onLoad((options) => {
  sessionId.value = options?.id || ''
  refreshSession()
})

onShow(() => {
  refreshSession({ keepForm: showEditor.value })
})
</script>

<template>
  <view class="nt-page nt-page--bar">
    <NavBar back title="对局笔记" />

    <view v-if="session" class="nt-gutter nt-stack sd-body">
      <view class="nt-ink sd-head">
        <text class="sd-head__name serif">{{ session.boardName }}</text>
        <text class="nt-meta">{{ session.playerCount }} 人局 · 最近更新 {{ formatDateTime(session.updateTime) }}</text>
        <view class="sd-head__tags">
          <text class="nt-tag c-gold">共 {{ displayRecords.length }} 条记录</text>
          <text class="nt-tag">{{ latestWolfPack }}</text>
        </view>
      </view>

      <view class="nt-panel">
        <view class="nt-panel__head">
          <text class="nt-h2">事件时间线</text>
          <text class="nt-meta">按时间先后</text>
        </view>
        <view v-if="!displayRecords.length" class="nt-empty sd-empty">还没有记录，从下面的按钮记第一条。</view>
        <view class="sd-timeline">
          <view v-for="(item, index) in displayRecords" :key="item.id" class="sd-record">
            <view v-if="index < displayRecords.length - 1" class="sd-record__line" />
            <view class="sd-record__dot" :class="`sd-record__dot--${item.type}`" />
            <view class="sd-record__body">
              <view class="sd-record__top">
                <view class="sd-record__tags">
                  <text class="nt-tag" :class="`sd-type--${item.type}`">{{ item.typeLabel }}</text>
                  <text class="nt-tag">{{ item.roundLabel }}</text>
                </view>
                <text class="sd-record__remove" @tap="removeRecord(item.id)">删除</text>
              </view>
              <text class="sd-record__content">{{ item.content }}</text>
              <text class="nt-meta">{{ item.player || '未指定玩家' }} · {{ item.timestampLabel }}</text>
            </view>
          </view>
        </view>
      </view>
    </view>

    <view class="nt-bar sd-tools">
      <view class="nt-btn nt-btn--ghost sd-tool" @tap="openEditor('seer')">查验</view>
      <view class="nt-btn nt-btn--gold sd-tool" @tap="openEditor('vote')">投票</view>
      <view class="nt-btn nt-btn--ghost sd-tool" @tap="openEditor('speech')">发言</view>
      <view class="nt-btn nt-btn--ghost sd-tool" @tap="openEditor('wolfPack')">狼坑</view>
    </view>

    <view v-if="showEditor" class="sd-mask" @tap="closeEditor">
      <view class="sd-sheet" @tap.stop>
        <view class="sd-sheet__handle" />
        <view class="sd-sheet__head">
          <text class="nt-h2">{{ editorTitleMap[editorType] }}</text>
          <text class="sd-sheet__close" @tap="closeEditor">关闭</text>
        </view>

        <scroll-view scroll-y class="sd-sheet__scroll">
          <view class="sd-block">
            <text class="nt-label">第几天</text>
            <view class="sd-chips">
              <text
                v-for="day in dayOptions"
                :key="day"
                class="nt-chip"
                :class="{ 'nt-chip--on': recordForm.round === day }"
                @tap="recordForm.round = day"
              >{{ formatDay(day) }}</text>
            </view>
          </view>

          <block v-if="editorType === 'seer' || editorType === 'speech'">
            <view class="sd-block">
              <text class="nt-label">玩家</text>
              <view class="sd-grid">
                <text
                  v-for="player in players"
                  :key="player"
                  class="nt-chip sd-seat"
                  :class="{ 'nt-chip--on': recordForm.player === player }"
                  @tap="recordForm.player = player"
                >{{ player }}</text>
              </view>
            </view>
            <view class="sd-block">
              <text class="nt-label">内容</text>
              <textarea v-model="recordForm.content" class="nt-textarea" placeholder="输入这一天的记录内容" placeholder-class="nt-placeholder" auto-height />
            </view>
          </block>

          <block v-if="editorType === 'wolfPack'">
            <view class="sd-block">
              <text class="nt-label">狼坑候选</text>
              <view class="sd-grid">
                <text
                  v-for="player in players"
                  :key="player"
                  class="nt-chip sd-seat"
                  :class="{ 'nt-chip--on': recordForm.wolfPack.includes(player) }"
                  @tap="toggleArray('wolfPack', player)"
                >{{ player }}</text>
              </view>
            </view>
          </block>

          <block v-if="editorType === 'vote'">
            <view class="sd-block">
              <text class="nt-label">模板</text>
              <view class="sd-chips">
                <text
                  v-for="item in voteTemplateOptions"
                  :key="item.value"
                  class="nt-chip"
                  :class="{ 'nt-chip--on': recordForm.voteTemplate === item.value }"
                  @tap="recordForm.voteTemplate = item.value"
                >{{ item.label }}</text>
              </view>
            </view>

            <block v-if="recordForm.voteTemplate === 'normal'">
              <view class="sd-block">
                <text class="nt-label">投票人</text>
                <view class="sd-grid">
                  <text
                    v-for="player in players"
                    :key="player"
                    class="nt-chip sd-seat"
                    :class="{ 'nt-chip--on': recordForm.normalVoters.includes(player) }"
                    @tap="toggleArray('normalVoters', player)"
                  >{{ player }}</text>
                </view>
              </view>
              <view class="sd-block">
                <text class="nt-label">被投票人</text>
                <view class="sd-grid">
                  <text
                    v-for="player in players"
                    :key="player"
                    class="nt-chip sd-seat"
                    :class="{ 'nt-chip--on': recordForm.normalTarget === player }"
                    @tap="recordForm.normalTarget = player"
                  >{{ player }}</text>
                </view>
              </view>
            </block>

            <block v-if="recordForm.voteTemplate === 'tie'">
              <view class="sd-block">
                <text class="nt-label">平票对象</text>
                <view class="sd-grid">
                  <text
                    v-for="player in players"
                    :key="player"
                    class="nt-chip sd-seat"
                    :class="{ 'nt-chip--on': recordForm.tieTargets.includes(player) }"
                    @tap="toggleArray('tieTargets', player)"
                  >{{ player }}</text>
                </view>
              </view>
            </block>

            <block v-if="recordForm.voteTemplate === 'abstain'">
              <view class="sd-block">
                <text class="nt-label">弃票玩家</text>
                <view class="sd-grid">
                  <text
                    v-for="player in players"
                    :key="player"
                    class="nt-chip sd-seat"
                    :class="{ 'nt-chip--on': recordForm.abstainPlayers.includes(player) }"
                    @tap="toggleArray('abstainPlayers', player)"
                  >{{ player }}</text>
                </view>
              </view>
            </block>

            <block v-if="recordForm.voteTemplate === 'sheriff'">
              <view class="sd-block">
                <text class="nt-label">警长</text>
                <view class="sd-grid">
                  <text
                    v-for="player in players"
                    :key="player"
                    class="nt-chip sd-seat"
                    :class="{ 'nt-chip--on': recordForm.sheriffPlayer === player }"
                    @tap="setSheriffPlayer(player)"
                  >{{ player }}</text>
                </view>
              </view>
              <view class="sd-block">
                <text class="nt-label">归票目标</text>
                <view class="sd-grid">
                  <text
                    v-for="player in players"
                    :key="player"
                    class="nt-chip sd-seat"
                    :class="{ 'nt-chip--on': recordForm.sheriffTarget === player }"
                    @tap="recordForm.sheriffTarget = player"
                  >{{ player }}</text>
                </view>
              </view>
              <view class="sd-block">
                <text class="nt-label">跟票玩家</text>
                <view class="sd-grid">
                  <text
                    v-for="player in players"
                    :key="player"
                    class="nt-chip sd-seat"
                    :class="{ 'nt-chip--on': recordForm.sheriffFollowers.includes(player) }"
                    @tap="toggleArray('sheriffFollowers', player)"
                  >{{ player }}</text>
                </view>
              </view>
            </block>

            <view class="sd-block">
              <text class="nt-label">备注</text>
              <textarea v-model="recordForm.voteRemark" class="nt-textarea sd-remark" placeholder="可选，记录归票理由或场上细节" placeholder-class="nt-placeholder" auto-height />
            </view>
            <text class="sd-preview">{{ votePreview }}</text>
          </block>
        </scroll-view>

        <view class="nt-btn nt-btn--gold nt-btn--block" @tap="saveRecord">保存记录</view>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.sd-body {
  margin-top: 16rpx;
}

.sd-head {
  padding: 32rpx;
  display: flex;
  flex-direction: column;
  gap: 14rpx;
}

.sd-head__name {
  font-size: 48rpx;
  font-weight: 900;
}

.sd-head__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}

.sd-empty {
  padding: 40rpx 0;
}

.sd-timeline {
  display: flex;
  flex-direction: column;
  gap: 32rpx;
}

.sd-record {
  position: relative;
  display: flex;
  gap: 24rpx;
}

.sd-record__line {
  position: absolute;
  left: 13rpx;
  top: 36rpx;
  bottom: -36rpx;
  width: 4rpx;
  background: var(--line);
}

.sd-record__dot {
  position: relative;
  z-index: 1;
  width: 30rpx;
  height: 30rpx;
  margin-top: 8rpx;
  border-radius: 50%;
  border: 4rpx solid var(--ink);
  background: var(--gold);
  box-sizing: border-box;
  flex-shrink: 0;
}

.sd-record__dot--vote {
  background: var(--camp-wolf);
}

.sd-record__dot--speech {
  background: var(--moon);
}

.sd-record__dot--wolfPack {
  background: #7a1a22;
}

.sd-record__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 10rpx;
}

.sd-record__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.sd-record__tags {
  display: flex;
  gap: 10rpx;
}

.sd-type--seer {
  color: var(--gold);
}

.sd-type--vote {
  color: var(--camp-wolf);
}

.sd-type--speech {
  color: var(--moon);
}

.sd-record__remove {
  color: var(--text-3);
  font-size: 24rpx;
}

.sd-record__content {
  font-size: 30rpx;
  line-height: 1.7;
}

.sd-tools {
  gap: 14rpx;
}

.sd-tool {
  flex: 1;
  padding: 0;
}

.sd-mask {
  position: fixed;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 60;
  background: rgba(7, 6, 10, 0.7);
  display: flex;
  align-items: flex-end;
}

.sd-sheet {
  width: 100%;
  max-height: 86vh;
  padding: 24rpx 40rpx calc(32rpx + env(safe-area-inset-bottom));
  border-radius: 40rpx 40rpx 0 0;
  background: var(--panel);
  border-top: 4rpx solid var(--ink);
  display: flex;
  flex-direction: column;
  gap: 24rpx;
  box-sizing: border-box;
}

.sd-sheet__handle {
  align-self: center;
  width: 80rpx;
  height: 8rpx;
  border-radius: 4rpx;
  background: var(--line);
}

.sd-sheet__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.sd-sheet__close {
  color: var(--text-3);
  font-size: 28rpx;
}

.sd-sheet__scroll {
  max-height: 60vh;
}

.sd-block {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
  margin-bottom: 28rpx;
}

.sd-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 14rpx;
}

.sd-chips .nt-chip {
  height: 60rpx;
  font-size: 26rpx;
}

.sd-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12rpx;
}

.sd-seat {
  justify-content: center;
  padding: 0;
  height: 64rpx;
  font-size: 26rpx;
}

.sd-remark {
  min-height: 120rpx;
}

.sd-preview {
  display: block;
  padding: 22rpx 24rpx;
  border-radius: 20rpx;
  background: var(--panel-raised);
  color: var(--gold);
  font-size: 28rpx;
  line-height: 1.6;
  margin-bottom: 8rpx;
}
</style>
