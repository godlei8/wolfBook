<script setup>
import { computed, reactive, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import storage from '../../services/storage'
import { formatDateTime } from '../../utils/format'

const sessionId = ref('')
const session = ref(null)
const players = ref([])
const showEditor = ref(false)
const editorType = ref('seer')

const voteTemplateOptions = [
  { value: 'normal', label: '普通投票' },
  { value: 'tie', label: '平票' },
  { value: 'abstain', label: '弃票' },
  { value: 'sheriff', label: '警长归票' },
]

const recordForm = reactive(makeDefaultForm([]))

function makeDefaultForm(playerList) {
  const first = playerList[0] || '1号'
  const second = playerList[1] || first
  return {
    round: 1,
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

function joinPlayers(playerList) {
  return (playerList || []).filter(Boolean).join('、')
}

function makeId(prefix) {
  return `${prefix}_${Date.now()}_${Math.random().toString(16).slice(2, 8)}`
}

function syncSessionView(rawSession) {
  if (!rawSession) return
  const playerList = Array.from({ length: rawSession.playerCount }, (_, index) => `${index + 1}号`)
  players.value = playerList
  Object.assign(recordForm, makeDefaultForm(playerList))
  session.value = {
    ...rawSession,
    records: (rawSession.records || []).map((record) => ({
      ...record,
      timestampLabel: formatDateTime(record.timestamp),
    })),
    updateLabel: formatDateTime(rawSession.updateTime),
  }
}

function refreshSession() {
  const current = storage.getSessionById(sessionId.value)
  if (!current) {
    session.value = null
    uni.showToast({ title: '对局不存在', icon: 'none' })
    return
  }
  syncSessionView(current)
}

function openEditor(type) {
  editorType.value = type
  Object.assign(recordForm, makeDefaultForm(players.value))
  showEditor.value = true
}

function closeEditor() {
  showEditor.value = false
}

function toggleArray(field, value) {
  const list = recordForm[field]
  if (!Array.isArray(list)) return
  const exists = list.includes(value)
  if (exists) {
    recordForm[field] = list.filter((item) => item !== value)
  } else {
    recordForm[field] = list.concat(value)
  }
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
  return result ? result.content : '请先完成投票信息选择'
})

const latestWolfPack = computed(() => {
  const list = session.value?.records || []
  const latest = [...list].reverse().find((item) => item.type === 'wolfPack')
  return latest?.content || '暂无狼坑'
})

function saveRecord() {
  const current = storage.getSessionById(sessionId.value)
  if (!current) return

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

  current.records = (current.records || []).concat(record)
  current.updateTime = new Date().toISOString()
  storage.upsertSession(current)
  closeEditor()
  refreshSession()
}

function removeRecord(id) {
  const current = storage.getSessionById(sessionId.value)
  if (!current) return
  current.records = (current.records || []).filter((item) => item.id !== id)
  current.updateTime = new Date().toISOString()
  storage.upsertSession(current)
  refreshSession()
}

onLoad((options) => {
  sessionId.value = options?.id || ''
  refreshSession()
})

onShow(refreshSession)
</script>

<template>
  <view v-if="session" class="page-shell">
    <view class="glass-card section-card">
      <view class="hero-title" style="font-size: 46rpx;">{{ session.boardName }}</view>
      <view class="hero-subtitle">{{ session.playerCount }} 人局 · 最近更新 {{ session.updateLabel }}</view>
      <view class="wolf-pack">当前狼坑：{{ latestWolfPack }}</view>
    </view>

    <view class="glass-card section-card">
      <view class="section-title">事件流</view>
      <view v-if="!session.records.length" class="section-desc">还没有记录，先从底部工具栏添加第一条。</view>
      <view v-for="item in session.records" :key="item.id" class="record-item">
        <view class="record-head">
          <view class="pill pill-gold">{{ item.type }}</view>
          <view class="section-meta">R{{ item.round }} · {{ item.timestampLabel }}</view>
        </view>
        <view class="record-content">{{ item.content }}</view>
        <view class="record-player">{{ item.player }}</view>
        <button class="record-remove" @tap="removeRecord(item.id)">删除</button>
      </view>
    </view>

    <view class="tool-grid glass-card">
      <view class="tool-item" @tap="openEditor('seer')">预言家</view>
      <view class="tool-item" @tap="openEditor('vote')">投票</view>
      <view class="tool-item" @tap="openEditor('speech')">发言</view>
      <view class="tool-item" @tap="openEditor('wolfPack')">狼坑</view>
    </view>

    <view v-if="showEditor" class="editor-mask" @tap="closeEditor">
      <view class="editor-panel glass-card" @tap.stop>
        <view class="section-title">
          {{
            editorType === 'seer'
              ? '记录查验'
              : editorType === 'vote'
                ? '记录投票'
                : editorType === 'speech'
                  ? '记录发言'
                  : '记录狼坑'
          }}
        </view>

        <view class="section-meta" style="margin-top: 18rpx;">轮次</view>
        <input v-model="recordForm.round" type="number" class="field-input" />

        <template v-if="editorType === 'seer' || editorType === 'speech'">
          <view class="section-meta" style="margin-top: 18rpx;">玩家</view>
          <scroll-view scroll-x class="chip-row">
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

          <view class="section-meta" style="margin-top: 18rpx;">内容</view>
          <textarea v-model="recordForm.content" class="field-textarea" placeholder="输入本轮记录内容" />
        </template>

        <template v-if="editorType === 'wolfPack'">
          <view class="section-meta" style="margin-top: 18rpx;">狼坑候选</view>
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
        </template>

        <template v-if="editorType === 'vote'">
          <view class="section-meta" style="margin-top: 18rpx;">模板</view>
          <scroll-view scroll-x class="chip-row">
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

          <template v-if="recordForm.voteTemplate === 'normal'">
            <view class="section-meta" style="margin-top: 18rpx;">投票人</view>
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
            <view class="section-meta" style="margin-top: 18rpx;">被投票人</view>
            <scroll-view scroll-x class="chip-row">
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
          </template>

          <template v-if="recordForm.voteTemplate === 'tie'">
            <view class="section-meta" style="margin-top: 18rpx;">平票对象</view>
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
          </template>

          <template v-if="recordForm.voteTemplate === 'abstain'">
            <view class="section-meta" style="margin-top: 18rpx;">弃票玩家</view>
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
          </template>

          <template v-if="recordForm.voteTemplate === 'sheriff'">
            <view class="section-meta" style="margin-top: 18rpx;">警长</view>
            <scroll-view scroll-x class="chip-row">
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
            <view class="section-meta" style="margin-top: 18rpx;">归票目标</view>
            <scroll-view scroll-x class="chip-row">
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
            <view class="section-meta" style="margin-top: 18rpx;">跟票玩家</view>
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
          </template>

          <view class="section-meta" style="margin-top: 18rpx;">备注</view>
          <textarea v-model="recordForm.voteRemark" class="field-textarea" placeholder="可选，记录归票理由或场上细节" />
          <view class="vote-preview">{{ votePreview }}</view>
        </template>

        <button class="button-primary submit-btn" @tap="saveRecord">保存记录</button>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.wolf-pack {
  margin-top: 18rpx;
  color: #ffc000;
  font-size: 24rpx;
}

.record-item {
  margin-top: 18rpx;
  padding: 18rpx 20rpx;
  border-radius: 16rpx;
  background: rgba(255, 255, 255, 0.04);
}

.record-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16rpx;
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
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: flex-end;
  z-index: 30;
}

.editor-panel {
  width: 100%;
  max-height: 80vh;
  padding: 28rpx;
  border-radius: 24rpx 24rpx 0 0;
  overflow-y: auto;
}

.player-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 12rpx;
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
  margin-top: 22rpx;
}
</style>
