<script setup>
import { computed, ref } from 'vue'
import { onHide, onLoad, onShareAppMessage, onShow, onUnload } from '@dcloudio/uni-app'
import AssistantDock from '../../components/assistant/AssistantDock.vue'
import api from '../../services/api'
import storage from '../../services/storage'
import { formatDateTime } from '../../utils/format'
import { getJudgePhaseLabel, getJudgeRoomStatusLabel, getJudgeSupportMeta } from '../../utils/judge/meta'
import { buildJudgeVoiceScript, createJudgeVoicePlayer } from '../../utils/judge/voice'

const roomId = ref('')
const snapshot = ref(null)
const loading = ref(false)
const actionLoading = ref(false)
const announcement = ref('')
const nightActionType = ref('night_action')
const nightTargetSeat = ref('')
const nightNote = ref('')
const voteTargetSeat = ref('')
const nightDeadSeats = ref('')
const voteOutSeat = ref('')
const finishWinnerCamp = ref('')
const voiceAutoEnabled = ref(true)
const voicePlaying = ref(false)
const voiceCurrentLine = ref('')
const lastVoiceScriptKey = ref('')

let pollTimer = null
const voicePlayer = createJudgeVoicePlayer()

const supportMeta = computed(() => getJudgeSupportMeta(snapshot.value?.judgeSupportLevel))
const selfPlayer = computed(() => snapshot.value?.selfPlayer || null)
const alivePlayers = computed(() => (snapshot.value?.players || []).filter((item) => item.playing && item.alive))
const judgeVoiceScript = computed(() => buildJudgeVoiceScript(snapshot.value))
const judgeVoiceSupported = computed(() => voicePlayer.supported)

function safeDecode(value) {
  if (!value) return ''
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

function redirectInviteToLogin() {
  if (!roomId.value) {
    uni.switchTab({ url: '/pages/judge/index' })
    return
  }
  storage.setPendingJudgeInvite({
    roomId: roomId.value,
    boardName: safeDecode(snapshot.value?.boardName || ''),
  })
  uni.showToast({ title: '登录后可继续进入法官房', icon: 'none' })
  setTimeout(() => {
    uni.switchTab({ url: '/pages/user/index' })
  }, 240)
}

function startPolling() {
  stopPolling()
  pollTimer = setInterval(() => {
    refreshRoom()
  }, 3000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function refreshRoom(showError = false) {
  if (!roomId.value) return
  if (!storage.getAuthToken()) {
    redirectInviteToLogin()
    return
  }
  if (!snapshot.value) {
    loading.value = true
  }
  const previousVoiceKey = judgeVoiceScript.value?.key || ''
  try {
    const data = await api.getJudgeRoom(roomId.value)
    snapshot.value = data
    storage.clearPendingJudgeInvite()
    if (!announcement.value && data.latestAnnouncement) {
      announcement.value = data.latestAnnouncement
    }
    const nextVoiceScript = buildJudgeVoiceScript(data)
    if (
      voiceAutoEnabled.value
      && nextVoiceScript.lines.length
      && nextVoiceScript.key !== previousVoiceKey
      && nextVoiceScript.key !== lastVoiceScriptKey.value
      && !voicePlaying.value
    ) {
      playJudgeVoice(nextVoiceScript)
    }
  } catch (error) {
    if (showError) {
      uni.showToast({ title: error?.message || '房间加载失败', icon: 'none' })
    }
  } finally {
    loading.value = false
  }
}

async function runAction(task) {
  actionLoading.value = true
  try {
    const previousVoiceKey = judgeVoiceScript.value?.key || ''
    snapshot.value = await task()
    const nextVoiceScript = buildJudgeVoiceScript(snapshot.value)
    if (
      voiceAutoEnabled.value
      && nextVoiceScript.lines.length
      && nextVoiceScript.key !== previousVoiceKey
      && nextVoiceScript.key !== lastVoiceScriptKey.value
    ) {
      playJudgeVoice(nextVoiceScript)
    }
  } catch (error) {
    uni.showToast({ title: error?.message || '操作失败', icon: 'none' })
  } finally {
    actionLoading.value = false
  }
}

function parseSeatNos(text) {
  return String(text || '')
    .split(/[^\d]+/)
    .map((item) => Number(item))
    .filter((item) => Number.isFinite(item) && item > 0)
}

async function joinRoom() {
  await runAction(() => api.joinJudgeRoom(roomId.value))
}

async function toggleReady() {
  await runAction(() => api.toggleJudgeReady(roomId.value))
}

async function startRoom() {
  await runAction(() => api.startJudgeRoom(roomId.value))
}

async function submitNightAction() {
  await runAction(async () => {
    const data = await api.submitJudgeNightAction(roomId.value, {
      actionType: nightActionType.value || 'night_action',
      targetSeatNo: Number(nightTargetSeat.value) || null,
      note: nightNote.value.trim(),
    })
    return data
  })
}

async function submitVote() {
  const targetSeatNo = Number(voteTargetSeat.value)
  if (!targetSeatNo) {
    uni.showToast({ title: '请输入投票目标座位', icon: 'none' })
    return
  }
  await runAction(() => api.submitJudgeVote(roomId.value, { targetSeatNo }))
}

async function judgeBroadcast() {
  if (!announcement.value.trim()) {
    uni.showToast({ title: '请输入公告内容', icon: 'none' })
    return
  }
  await runAction(() => api.advanceJudgeRoom(roomId.value, { action: 'broadcast', announcement: announcement.value.trim() }))
}

async function judgeStartVote() {
  await runAction(() => api.advanceJudgeRoom(roomId.value, { action: 'start_vote', announcement: announcement.value.trim() }))
}

async function judgeResolveNight() {
  await runAction(() =>
    api.advanceJudgeRoom(roomId.value, {
      action: 'resolve_night',
      deadSeatNos: parseSeatNos(nightDeadSeats.value),
      announcement: announcement.value.trim(),
    }),
  )
}

async function judgeResolveVote() {
  await runAction(() =>
    api.advanceJudgeRoom(roomId.value, {
      action: 'resolve_vote',
      eliminatedSeatNo: Number(voteOutSeat.value) || null,
      announcement: announcement.value.trim(),
    }),
  )
}

async function judgeFinish() {
  await runAction(() =>
    api.advanceJudgeRoom(roomId.value, {
      action: 'finish',
      winnerCamp: finishWinnerCamp.value.trim(),
      announcement: announcement.value.trim(),
    }),
  )
}

async function playJudgeVoice(script = judgeVoiceScript.value) {
  if (!script?.lines?.length) {
    uni.showToast({ title: '当前阶段没有可播报的语音', icon: 'none' })
    return
  }
  if (!voicePlayer.supported) {
    uni.showToast({ title: '当前环境不支持法官语音', icon: 'none' })
    return
  }

  voicePlayer.stop()
  voicePlaying.value = true
  voiceCurrentLine.value = ''

  try {
    const completed = await voicePlayer.playScript(script, {
      onLineStart(line) {
        voiceCurrentLine.value = line
      },
      onFinish() {
        voiceCurrentLine.value = ''
      },
    })
    if (completed) {
      lastVoiceScriptKey.value = script.key
    }
  } catch (error) {
    uni.showToast({ title: error?.message || '法官语音播放失败', icon: 'none' })
  } finally {
    voicePlaying.value = false
    voiceCurrentLine.value = ''
  }
}

function stopJudgeVoice() {
  voicePlayer.stop()
  voicePlaying.value = false
  voiceCurrentLine.value = ''
}

function toggleVoiceAuto() {
  voiceAutoEnabled.value = !voiceAutoEnabled.value
}

function copyRoomId() {
  if (!snapshot.value?.roomId) return
  uni.setClipboardData({ data: snapshot.value.roomId })
}

function judgeSharePayload() {
  const room = snapshot.value
  const roomCode = room?.roomId || roomId.value
  const boardName = encodeURIComponent(room?.boardName || '')
  const title = room?.boardName
    ? '邀请你加入「' + room.boardName + '」法官房'
    : '邀请你加入狼人杀电子法官房间'
  return {
    title,
    path: `/pages/judge/room?roomId=${roomCode}&boardName=${boardName}&fromShare=1`,
    imageUrl: room?.boardCoverImage || room?.coverImage || '',
  }
}

function openBoard() {
  if (!snapshot.value?.boardId) return
  uni.navigateTo({ url: `/pages/boards/detail?id=${snapshot.value.boardId}` })
}

function openNote() {
  if (!snapshot.value?.boardId) return
  uni.navigateTo({
    url: `/pages/sessions/edit?source=judge&boardId=${snapshot.value.boardId}&boardName=${encodeURIComponent(snapshot.value.boardName || '')}`,
  })
}

function openCommunity() {
  if (!snapshot.value?.boardId) return
  uni.switchTab({ url: '/pages/community/index' })
  setTimeout(() => {
    uni.navigateTo({
      url: `/pages/community/index?boardId=${snapshot.value.boardId}&boardName=${encodeURIComponent(snapshot.value.boardName || '')}`,
    })
  }, 60)
}

function playerRoleText(player) {
  if (!player?.roleName) return '身份待发放'
  return `${player.roleName}${player.faction ? ` · ${player.faction}` : ''}`
}

function playerStateText(player) {
  if (player.judgeObserver) return '法官'
  if (!player.playing) return '旁观'
  if (!player.alive) return `已出局${player.deathPhase ? ` · ${getJudgePhaseLabel(player.deathPhase)}` : ''}`
  return player.ready ? '已准备' : '待准备'
}

function timelineText(item) {
  const payload = item.payload || {}
  const result = item.resultPayload || {}
  switch (item.actionType) {
    case 'room_created':
      return '法官创建了房间'
    case 'player_joined':
      return `${payload.nickname || `${item.actorSeatNo || ''}号`}加入房间`
    case 'ready_changed':
      return `${item.actorSeatNo || ''}号${payload.ready ? '已准备' : '取消准备'}`
    case 'game_started':
      return result.announcement || '游戏开始，进入第一夜'
    case 'broadcast':
      return payload.announcement || '房间公告'
    case 'night_resolved':
      return result.announcement || '夜晚已结算'
    case 'vote_opened':
      return payload.announcement || '已开启放逐投票'
    case 'vote_resolved':
      return result.announcement || '投票已结算'
    case 'game_finished':
      return result.announcement || '对局已结束'
    case 'night_action_submit':
      return `${item.actorSeatNo || ''}号提交了夜间动作`
    case 'vote_submit':
      return `${item.actorSeatNo || ''}号提交了投票`
    default:
      return item.actionType
  }
}

onLoad((options) => {
  roomId.value = options?.roomId || ''
  if (!roomId.value) {
    uni.showToast({ title: '缺少房间号', icon: 'none' })
    setTimeout(() => {
      uni.switchTab({ url: '/pages/judge/index' })
    }, 180)
    return
  }
  const boardName = safeDecode(options?.boardName || '')
  if (boardName) {
    storage.setPendingJudgeInvite({
      roomId: roomId.value,
      boardName,
    })
  }
  refreshRoom(true)
})

onShareAppMessage(() => judgeSharePayload())

onShow(() => {
  startPolling()
  refreshRoom()
})
onHide(() => {
  stopPolling()
  stopJudgeVoice()
})
onUnload(() => {
  stopPolling()
  voicePlayer.destroy()
})
</script>

<template>
  <view class="page-shell">
    <view v-if="snapshot" class="hero-card glass-card section-card">
      <view class="hero-top">
        <view>
          <view class="hero-title room-hero-title">{{ snapshot.boardName }}</view>
          <view class="hero-subtitle room-subtitle">
            {{ getJudgeRoomStatusLabel(snapshot.roomStatus) }} · 第 {{ snapshot.currentDay }} 天 · {{ getJudgePhaseLabel(snapshot.currentPhase) }}
          </view>
        </view>
        <view class="pill pill-gold" @tap="copyRoomId">{{ snapshot.roomId }}</view>
      </view>
      <view class="hero-meta">
        <view class="pill pill-white">{{ snapshot.playerCount }} 人局</view>
        <view class="pill pill-white">{{ supportMeta.label }}</view>
      </view>
      <view class="hero-actions">
        <button class="button-ghost hero-action" @tap="copyRoomId">复制房间号</button>
        <button class="button-primary hero-action" open-type="share">微信邀请进房</button>
      </view>
      <view v-if="snapshot.latestAnnouncement" class="hero-announcement">{{ snapshot.latestAnnouncement }}</view>
      <view class="section-desc">{{ snapshot.roomTip }}</view>
    </view>

    <view v-if="snapshot?.canJoin" class="glass-card section-card">
      <view class="section-title">加入房间</view>
      <view class="section-desc">当前房间仍在大厅阶段，你可以直接加入并等待法官发身份。</view>
      <button class="button-primary action-button" :loading="actionLoading" @tap="joinRoom">加入并分配座位</button>
    </view>

    <view v-if="snapshot && !snapshot.selfPlayer && !snapshot.canJoin" class="glass-card section-card empty-state">
      房间当前不可加入，可能已经开局或人数已满。
    </view>

    <view v-if="snapshot?.selfPlayer && snapshot.roomStatus === 'lobby'" class="glass-card section-card">
      <view class="section-title">大厅状态</view>
      <view class="section-desc">已入座 {{ snapshot.joinedCount }}/{{ snapshot.playerCount }}，已准备 {{ snapshot.readyCount }}/{{ snapshot.playerCount }}</view>
      <view class="lobby-actions">
        <button v-if="snapshot.canReady" class="button-primary" :loading="actionLoading" @tap="toggleReady">
          {{ selfPlayer?.ready ? '取消准备' : '我已准备' }}
        </button>
        <button v-if="snapshot.canStart" class="button-primary" :loading="actionLoading" @tap="startRoom">开始发身份</button>
      </view>
    </view>

    <view v-if="selfPlayer?.roleName || snapshot?.roomStatus === 'finished'" class="glass-card section-card">
      <view class="section-title">我的信息</view>
      <view class="self-role-card">
        <view class="self-role-name">{{ selfPlayer?.nickname || '当前账号' }}</view>
        <view class="self-role-desc">{{ playerRoleText(selfPlayer) }}</view>
      </view>
    </view>

    <view v-if="snapshot?.judgeViewer && snapshot.roomStatus === 'playing'" class="glass-card section-card">
      <view class="section-title">{{ judgeVoiceScript.title }}</view>
      <view class="section-desc">{{ judgeVoiceScript.summary }}</view>
      <view v-if="voiceCurrentLine" class="voice-status">当前播报：{{ voiceCurrentLine }}</view>
      <view class="lobby-actions">
        <button
          class="button-primary"
          :disabled="!judgeVoiceSupported || !judgeVoiceScript.lines.length"
          @tap="playJudgeVoice()"
        >
          {{ voicePlaying ? '重播阶段语音' : '播放阶段语音' }}
        </button>
        <button class="button-ghost" @tap="toggleVoiceAuto">
          {{ voiceAutoEnabled ? '自动播报已开启' : '自动播报已关闭' }}
        </button>
        <button v-if="voicePlaying" class="button-danger" @tap="stopJudgeVoice">停止播报</button>
      </view>
      <view v-if="!judgeVoiceSupported" class="section-desc">当前环境不支持法官语音，请使用微信小程序真机进入房间。</view>
    </view>

    <view v-if="snapshot?.judgeViewer && snapshot.roomStatus === 'playing'" class="glass-card section-card">
      <view class="section-title">法官控制台</view>
      <view class="section-desc">这部分只在主持人模式下显示，用来广播、推进和结算当前阶段。</view>
      <textarea v-model="announcement" class="field-textarea console-textarea" maxlength="200" placeholder="公告、夜晚结算结果或放逐说明" />

      <view v-if="snapshot.currentPhase === 'night_action'" class="console-block">
        <view class="section-meta">昨夜死亡座位</view>
        <input v-model="nightDeadSeats" class="field-input" placeholder="例如 3, 8" />
        <button class="button-primary action-button" :loading="actionLoading" @tap="judgeResolveNight">结算夜晚</button>
      </view>

      <view v-if="snapshot.currentPhase === 'day_speech'" class="console-block">
        <button class="button-ghost action-button" :loading="actionLoading" @tap="judgeBroadcast">发送公告</button>
        <button class="button-primary action-button" :loading="actionLoading" @tap="judgeStartVote">开启投票</button>
      </view>

      <view v-if="snapshot.currentPhase === 'exile_vote'" class="console-block">
        <view class="section-meta">放逐目标座位</view>
        <input v-model="voteOutSeat" class="field-input" placeholder="留空则按唯一最高票自动判定" />
        <button class="button-primary action-button" :loading="actionLoading" @tap="judgeResolveVote">结算投票</button>
      </view>

      <view class="console-block">
        <view class="section-meta">直接结束对局</view>
        <input v-model="finishWinnerCamp" class="field-input" placeholder="例如 好人 / 狼人 / 第三方" />
        <button class="button-danger action-button" :loading="actionLoading" @tap="judgeFinish">结束并进入结果</button>
      </view>
    </view>

    <view v-if="snapshot?.canSubmitNightAction" class="glass-card section-card">
      <view class="section-title">提交夜间动作</view>
      <view class="section-desc">当前为夜间阶段，提交后只有法官可见，你自己会看到已提交状态。</view>
      <input v-model="nightActionType" class="field-input" placeholder="动作类型，例如 查验 / 守护 / 毒药" />
      <input v-model="nightTargetSeat" class="field-input" placeholder="目标座位号，可留空" />
      <textarea v-model="nightNote" class="field-textarea" maxlength="200" placeholder="补充说明，例如 查到金水 / 今晚空守" />
      <view class="pending-text">
        {{ snapshot.pendingNightAction?.submitted ? `已提交：目标 ${snapshot.pendingNightAction.targetSeatNo || '-'}，${snapshot.pendingNightAction.note || '无备注'}` : '当前还没有提交夜间动作' }}
      </view>
      <button class="button-primary action-button" :loading="actionLoading" @tap="submitNightAction">提交夜间动作</button>
    </view>

    <view v-if="snapshot?.canVote" class="glass-card section-card">
      <view class="section-title">放逐投票</view>
      <input v-model="voteTargetSeat" class="field-input" placeholder="输入你要投票的座位号" />
      <button class="button-primary action-button" :loading="actionLoading" @tap="submitVote">提交投票</button>
    </view>

    <view v-if="snapshot?.voteTallies?.length" class="glass-card section-card">
      <view class="section-title">当前票型</view>
      <view v-for="item in snapshot.voteTallies" :key="item.targetSeatNo" class="tally-item">
        <view class="tally-title">{{ item.targetSeatNo }}号 · {{ item.targetNickname }}</view>
        <view class="tally-desc">{{ item.voteCount }} 票 · 投票人 {{ item.voterSeatNos.join('、') }}</view>
      </view>
    </view>

    <view v-if="snapshot?.nightActions?.length" class="glass-card section-card">
      <view class="section-title">夜间提交</view>
      <view v-for="item in snapshot.nightActions" :key="`${item.actorPlayerId}_${item.createTime}`" class="tally-item">
        <view class="tally-title">{{ item.actorSeatNo }}号 · {{ item.actorNickname }}</view>
        <view class="tally-desc">{{ item.actionType }} → {{ item.targetSeatNo || '-' }}号 · {{ item.note || '无备注' }}</view>
      </view>
    </view>

    <view v-if="snapshot?.players?.length" class="glass-card section-card">
      <view class="section-title">房间成员</view>
      <view class="player-grid">
        <view v-for="item in snapshot.players" :key="item.playerId" class="player-card">
          <view class="player-seat">{{ item.seatNo ? `${item.seatNo}号` : '法官' }}</view>
          <view class="player-name">{{ item.nickname }}</view>
          <view class="player-meta">{{ playerStateText(item) }}</view>
          <view class="player-role">{{ playerRoleText(item) }}</view>
        </view>
      </view>
    </view>

    <view v-if="snapshot?.timeline?.length" class="glass-card section-card">
      <view class="section-title">时间线</view>
      <view v-for="item in snapshot.timeline" :key="item.eventId" class="timeline-item">
        <view class="timeline-title">{{ timelineText(item) }}</view>
        <view class="timeline-meta">{{ formatDateTime(item.createTime) }}</view>
      </view>
    </view>

    <view v-if="snapshot?.roomStatus === 'finished'" class="glass-card section-card">
      <view class="section-title">结果出口</view>
      <view class="section-desc">{{ snapshot.winnerCamp ? `胜利阵营：${snapshot.winnerCamp}` : snapshot.winnerSuggestion }}</view>
      <view class="result-actions">
        <button class="button-ghost" @tap="openBoard">回到板子详情</button>
        <button class="button-ghost" @tap="openNote">去开笔记</button>
        <button class="button-primary" @tap="openCommunity">去社区讨论</button>
      </view>
    </view>

    <AssistantDock
      scene="judge_room"
      :page-context="{ page: 'judge/room', roomId, boardName: snapshot?.boardName || '', phase: snapshot?.currentPhase || '', judgeViewer: snapshot?.judgeViewer || false }"
    />
  </view>
</template>

<style scoped lang="scss">
.room-hero-title {
  font-size: 44rpx;
}

.hero-top,
.hero-meta,
.hero-actions,
.lobby-actions,
.result-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 14rpx;
}

.hero-top {
  justify-content: space-between;
  align-items: flex-start;
}

.room-subtitle,
.hero-announcement,
.pending-text {
  margin-top: 10rpx;
  color: #cbb58a;
  font-size: 24rpx;
  line-height: 1.7;
}

.voice-status {
  margin-top: 14rpx;
  padding: 16rpx 18rpx;
  border-radius: 18rpx;
  background: rgba(255, 192, 0, 0.08);
  color: #f7d977;
  font-size: 24rpx;
  line-height: 1.6;
}

.self-role-card,
.player-card,
.tally-item,
.timeline-item {
  margin-top: 18rpx;
  padding: 18rpx 20rpx;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.04);
}

.self-role-name,
.tally-title,
.timeline-title,
.player-seat {
  font-size: 26rpx;
  font-weight: 700;
}

.self-role-desc,
.tally-desc,
.timeline-meta,
.player-meta,
.player-role {
  margin-top: 8rpx;
  color: #9a9a9a;
  font-size: 22rpx;
  line-height: 1.6;
}

.action-button {
  margin-top: 16rpx;
  width: 100%;
}

.hero-action {
  min-width: 220rpx;
}

.console-textarea {
  min-height: 160rpx;
}

.console-block {
  margin-top: 22rpx;
}

.player-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14rpx;
  margin-top: 16rpx;
}

.player-card {
  margin-top: 0;
}

@media (max-width: 640rpx) {
  .player-grid {
    grid-template-columns: 1fr;
  }
}
</style>
