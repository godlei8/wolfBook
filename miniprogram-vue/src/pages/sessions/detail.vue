<script setup>
import { computed, reactive, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
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

function syncSessionView(rawSession) {
  if (!rawSession) return
  session.value = {
    ...rawSession,
    records: (rawSession.records || []).slice(),
  }
  players.value = buildPlayers(rawSession.playerCount)
  resetForm(rawSession)
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

async function refreshSession() {
  if (!sessionId.value) return
  try {
    const current = await userData.getSessionById(sessionId.value)
    if (!current) {
      session.value = null
      uni.showToast({ title: '对局不存在', icon: 'none' })
      return
    }
    syncSessionView(current)
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
  refreshSession()
})
</script>

<template>
  <view v-if="session" class="page-shell">
    <view class="glass-card section-card">
      <view class="hero-title" style="font-size: 46rpx;">{{ session.boardName }}</view>
      <view class="hero-subtitle">{{ session.playerCount }} 人局 · 最近更新 {{ formatDateTime(session.updateTime) }}</view>
      <view class="hero-status-row">
        <view class="pill pill-gold">共 {{ displayRecords.length }} 条记录</view>
        <view class="pill pill-white">{{ latestWolfPack }}</view>
      </view>
    </view>

    <view class="glass-card section-card">
      <view class="section-title">事件时间线</view>
      <view v-if="!displayRecords.length" class="section-desc">还没有记录，先从底部工具栏添加第一条。</view>
      <view v-for="item in displayRecords" :key="item.id" class="record-item">
        <view class="record-head">
          <view class="record-badges">
            <view class="pill pill-gold">{{ item.typeLabel }}</view>
            <view class="pill pill-white">{{ item.roundLabel }}</view>
          </view>
          <view class="section-meta">{{ item.timestampLabel }}</view>
        </view>
        <view class="record-content">{{ item.content }}</view>
        <view class="record-player">{{ item.player || '未指定玩家' }}</view>
        <button class="record-remove" @tap="removeRecord(item.id)">删除</button>
      </view>
    </view>

    <view class="tool-grid glass-card">
      <view class="tool-item" @tap="openEditor('seer')">查验</view>
      <view class="tool-item" @tap="openEditor('vote')">投票</view>
      <view class="tool-item" @tap="openEditor('speech')">发言</view>
      <view class="tool-item" @tap="openEditor('wolfPack')">狼坑</view>
    </view>

    <view v-if="showEditor" class="editor-mask" @tap="closeEditor">
      <view class="editor-panel glass-card" @tap.stop>
        <view class="editor-header">
          <view>
            <view class="section-title">{{ editorTitleMap[editorType] }}</view>
            <view class="section-desc editor-desc">把这一阶段的关键信息记下来，复盘会更清晰。</view>
          </view>
          <view class="editor-close" @tap="closeEditor">关闭</view>
        </view>

        <view class="editor-block">
          <view class="section-meta">第几天</view>
          <view class="day-chip-row">
            <view
              v-for="day in dayOptions"
              :key="day"
              class="chip chip--day"
              :class="{ active: recordForm.round === day }"
              @tap="recordForm.round = day"
            >
              {{ formatDay(day) }}
            </view>
          </view>
        </view>

        <template v-if="editorType === 'seer' || editorType === 'speech'">
          <view class="editor-block">
            <view class="section-meta">玩家</view>
            <scroll-view scroll-x class="chip-row compact-row">
              <view
                v-for="player in players"
                :key="player"
                class="chip"
                :class="{ active: recordForm.player === player }"
                @tap="recordForm.player = player"
              >
                {{ player }}
              </view>
            </scroll-view>
          </view>

          <view class="editor-block">
            <view class="section-meta">内容</view>
            <textarea
              v-model="recordForm.content"
              class="field-textarea tactical-textarea"
              placeholder="输入这一天的记录内容"
              auto-height
            />
          </view>
        </template>

        <template v-if="editorType === 'wolfPack'">
          <view class="editor-block">
            <view class="section-meta">狼坑候选</view>
            <view class="player-grid">
              <view
                v-for="player in players"
                :key="player"
                class="chip"
                :class="{ active: recordForm.wolfPack.includes(player) }"
                @tap="toggleArray('wolfPack', player)"
              >
                {{ player }}
              </view>
            </view>
          </view>
        </template>

        <template v-if="editorType === 'vote'">
          <view class="editor-block">
            <view class="section-meta">模板</view>
            <scroll-view scroll-x class="chip-row compact-row">
              <view
                v-for="item in voteTemplateOptions"
                :key="item.value"
                class="chip"
                :class="{ active: recordForm.voteTemplate === item.value }"
                @tap="recordForm.voteTemplate = item.value"
              >
                {{ item.label }}
              </view>
            </scroll-view>
          </view>

          <template v-if="recordForm.voteTemplate === 'normal'">
            <view class="editor-block">
              <view class="section-meta">投票人</view>
              <view class="player-grid">
                <view
                  v-for="player in players"
                  :key="player"
                  class="chip"
                  :class="{ active: recordForm.normalVoters.includes(player) }"
                  @tap="toggleArray('normalVoters', player)"
                >
                  {{ player }}
                </view>
              </view>
            </view>
            <view class="editor-block">
              <view class="section-meta">被投票人</view>
              <scroll-view scroll-x class="chip-row compact-row">
                <view
                  v-for="player in players"
                  :key="player"
                  class="chip"
                  :class="{ active: recordForm.normalTarget === player }"
                  @tap="recordForm.normalTarget = player"
                >
                  {{ player }}
                </view>
              </scroll-view>
            </view>
          </template>

          <template v-if="recordForm.voteTemplate === 'tie'">
            <view class="editor-block">
              <view class="section-meta">平票对象</view>
              <view class="player-grid">
                <view
                  v-for="player in players"
                  :key="player"
                  class="chip"
                  :class="{ active: recordForm.tieTargets.includes(player) }"
                  @tap="toggleArray('tieTargets', player)"
                >
                  {{ player }}
                </view>
              </view>
            </view>
          </template>

          <template v-if="recordForm.voteTemplate === 'abstain'">
            <view class="editor-block">
              <view class="section-meta">弃票玩家</view>
              <view class="player-grid">
                <view
                  v-for="player in players"
                  :key="player"
                  class="chip"
                  :class="{ active: recordForm.abstainPlayers.includes(player) }"
                  @tap="toggleArray('abstainPlayers', player)"
                >
                  {{ player }}
                </view>
              </view>
            </view>
          </template>

          <template v-if="recordForm.voteTemplate === 'sheriff'">
            <view class="editor-block">
              <view class="section-meta">警长</view>
              <scroll-view scroll-x class="chip-row compact-row">
                <view
                  v-for="player in players"
                  :key="player"
                  class="chip"
                  :class="{ active: recordForm.sheriffPlayer === player }"
                  @tap="setSheriffPlayer(player)"
                >
                  {{ player }}
                </view>
              </scroll-view>
            </view>
            <view class="editor-block">
              <view class="section-meta">归票目标</view>
              <scroll-view scroll-x class="chip-row compact-row">
                <view
                  v-for="player in players"
                  :key="player"
                  class="chip"
                  :class="{ active: recordForm.sheriffTarget === player }"
                  @tap="recordForm.sheriffTarget = player"
                >
                  {{ player }}
                </view>
              </scroll-view>
            </view>
            <view class="editor-block">
              <view class="section-meta">跟票玩家</view>
              <view class="player-grid">
                <view
                  v-for="player in players"
                  :key="player"
                  class="chip"
                  :class="{ active: recordForm.sheriffFollowers.includes(player) }"
                  @tap="toggleArray('sheriffFollowers', player)"
                >
                  {{ player }}
                </view>
              </view>
            </view>
          </template>

          <view class="editor-block">
            <view class="section-meta">备注</view>
            <textarea
              v-model="recordForm.voteRemark"
              class="field-textarea tactical-textarea tactical-textarea--compact"
              placeholder="可选，记录归票理由或场上细节"
              auto-height
            />
          </view>
          <view class="vote-preview">{{ votePreview }}</view>
        </template>

        <button class="button-primary submit-btn" @tap="saveRecord">保存记录</button>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.hero-status-row {
  margin-top: 18rpx;
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}

.record-item {
  margin-top: 18rpx;
  padding: 22rpx 22rpx 20rpx;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.04);
  border: 1rpx solid rgba(255, 255, 255, 0.04);
}

.record-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16rpx;
}

.record-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 10rpx;
}

.record-content {
  margin-top: 14rpx;
  font-size: 28rpx;
  line-height: 1.8;
}

.record-player {
  margin-top: 10rpx;
  color: #a0a0a0;
  font-size: 24rpx;
}

.record-remove {
  margin: 16rpx 0 0;
  width: 140rpx;
  height: 64rpx;
  line-height: 64rpx;
  border-radius: 12rpx;
  background: rgba(255, 255, 255, 0.06);
  color: #ffffff;
  font-size: 24rpx;
}

.record-remove::after {
  border: none;
}

.tool-grid {
  position: fixed;
  left: 24rpx;
  right: 24rpx;
  bottom: calc(env(safe-area-inset-bottom) + 24rpx);
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12rpx;
  padding: 18rpx;
}

.tool-item {
  height: 80rpx;
  border-radius: 14rpx;
  background: rgba(255, 255, 255, 0.05);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24rpx;
  color: #ffffff;
}

.editor-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.62);
  display: flex;
  align-items: flex-end;
  z-index: 30;
}

.editor-panel {
  width: 100%;
  max-height: 82vh;
  padding: 28rpx 24rpx calc(env(safe-area-inset-bottom) + 26rpx);
  border-radius: 28rpx 28rpx 0 0;
  overflow-y: auto;
}

.editor-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18rpx;
}

.editor-desc {
  margin-top: 8rpx;
}

.editor-close {
  flex: 0 0 auto;
  padding: 10rpx 18rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.06);
  color: #d8d8d8;
  font-size: 22rpx;
}

.editor-block {
  margin-top: 18rpx;
}

.day-chip-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12rpx;
  margin-top: 12rpx;
}

.chip--day {
  justify-content: center;
  min-height: 72rpx;
}

.compact-row {
  margin-top: 12rpx;
}

.player-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 12rpx;
}

.tactical-textarea {
  min-height: 200rpx;
}

.tactical-textarea--compact {
  min-height: 140rpx;
}

.vote-preview {
  margin-top: 18rpx;
  padding: 18rpx 20rpx;
  border-radius: 16rpx;
  background: rgba(255, 192, 0, 0.08);
  color: #f3d27a;
  font-size: 24rpx;
  line-height: 1.7;
}

.submit-btn {
  margin-top: 24rpx;
}
</style>
