<script setup>
// 电子法官房间页（新流程房间）。页面只管：拉快照和轮询、导航栏、暂停遮罩、把快照交给当前视图组件。
// 画面上的判断都在 utils/judge/ 下的纯函数里；视图组件放在 ./views/，页面里只嵌一层。
// 旧流程房间（线上旧版小程序建的，autoJudge = false）跳到 room-legacy 的旧主持台。
import { computed, nextTick, ref } from 'vue'
import { onHide, onLoad, onShareAppMessage, onShow, onUnload } from '@dcloudio/uni-app'
import NavBar from '../../components/NavBar.vue'
import LobbyView from './views/LobbyView.vue'
import NightView from './views/NightView.vue'
import PendingView from './views/PendingView.vue'
import SheriffView from './views/SheriffView.vue'
import AnnounceView from './views/AnnounceView.vue'
import SpeechView from './views/SpeechView.vue'
import VoteView from './views/VoteView.vue'
import ResultView from './views/ResultView.vue'
import LogSheet from './views/LogSheet.vue'
import NotesBoard from '../../components/NotesBoard.vue'
import api from '../../services/api'
import storage from '../../services/storage'
import { mergePollResult, pollInterval } from '../../utils/judge/room-poll.js'
import { clockOffset, navTitle, pickView } from '../../utils/judge/room-view.js'
import { duelTargets, identityCard } from '../../utils/judge/identity.js'
import { hostMenu, judgeActions, pausedOverlay } from '../../utils/judge/judge-bar.js'
import { judgeSeatSheet } from '../../utils/judge/judge-desk.js'
import { hostActions } from '../../utils/judge/day.js'
import { rememberCue, retryCue, shouldPlay, voiceCue, withTimeout } from '../../utils/judge/voice-cue.js'
import { createJudgeVoicePlayer } from '../../utils/judge/voice'
import { createBgmPlayer, nightBgm } from '../../utils/judge/bgm.js'
import { NIGHT_BGM_URL } from '../../services/config'
import { goToUserTab, hasAuthToken } from '../../utils/auth'
import { showActionSheet, showModal } from '../../utils/dialog.js'
import userData from '../../services/user-data'
import { decodeNote, encodeNote, isNoteV2 } from '../../utils/notes/model.js'
import { createRoomNote, roomNoteId, syncFromSnapshot } from '../../utils/notes/sync.js'
import { roomSpeaker } from '../../utils/notes/speech.js'
import { createNoteSaver } from '../../utils/notes/saver.js'
import NtDialogHost from '../../components/NtDialogHost.vue'

const roomId = ref('')
const loadOptions = ref({})
const snapshot = ref(null)
// 板子详情（立绘、背景图、阵容头像），按快照里的 boardId 拉一次
const board = ref(null)
const pollError = ref('')
const actionLoading = ref(false)
// 牌桌高度（rpx）：视图量出来报上来，下一个阶段的视图直接用，座位不会先小后大
const stageHeight = ref(0)

// 倒计时用的本地时钟，每秒走一下（只是重画，不发请求）
const now = ref(Date.now())
// 手机时钟和服务器的差（毫秒），每次拿到快照按 serverNow 校正；倒计时都用「手机时间 + 偏移」
let serverOffset = 0

// 房主手机播报：同一个 key 只播一次，播过的 key 存本地（切后台、断网重连回来不重播）
const voicePlayer = createJudgeVoicePlayer()
// 夜晚背景音乐：和播报一样只在房主 / 法官的手机上放，跟同一个声音开关走；播报时自动压低
const bgmPlayer = createBgmPlayer({ url: NIGHT_BGM_URL, createAudio: () => uni.createInnerAudioContext() })
const hasSound = voicePlayer.supported || Boolean(NIGHT_BGM_URL)
const voiceOn = ref(true)
let voicePlaying = false
let voiceRun = 0
let playedKeys = []
// 每句播报失败了几次（失败一次会重播一次）
let voiceFailures = {}

let pollTimer = null
let clockTimer = null
// 最近一次提交成功的时刻；比它早发出的轮询结果不再覆盖页面
let lastActionAt = 0
let active = false
let redirected = false
// 房间不存在（4004）：停掉轮询，页面显示一句话，不再反复弹错
const roomGone = ref(false)

const view = computed(() => pickView(snapshot.value))
const title = computed(() => navTitle(snapshot.value))
// 自己的身份牌：每个入座的玩家导航栏都有「身份」，点开才显示
const identity = computed(() => identityCard(snapshot.value, board.value))
const identityOpen = ref(false)
const judgeBar = computed(() => judgeActions(snapshot.value))
const overlay = computed(() => pausedOverlay(snapshot.value))
// 底部控场栏只给法官；无法官房主的暂停 / 提前结束在右上方的设置菜单（hostMenu）
// 法官的底部栏：提前推进白天的按钮 + 暂停 / 继续（点杀在点座位的菜单里，提前结束在右上角设置）
const hasJudgeBar = computed(() => Boolean(snapshot.value?.judgeViewer) && (judgeBar.value.pause || judgeBar.value.resume))
const menuItems = computed(() => hostMenu(snapshot.value))
// 法官提前推进白天的按钮（每一步本来就按倒计时自动走），和暂停 / 更多（点杀、提前结束）放在同一条操作栏；
// 无法官房主只有暂停 / 继续和提前结束
const hostItems = computed(() => hostActions(snapshot.value))
const hasBar = computed(() => view.value === 'lobby' || hasJudgeBar.value || hostItems.value.length > 0)
// 点杀：先打开座位面板选人，再二次确认
// 对局记录面板：对局中只有法官顶栏有入口（玩家看笔记），结束后从结果页底部打开
const logOpen = ref(false)

// 对局笔记（入座的玩家才有，法官没有）：第一次点开才建，建了之后每次快照把公开信息并进去（utils/notes/sync.js，
// 只读全场都看得到的字段）。夜里每换一步面板自动收起——所有人一样，免得有技能的人埋头记笔记错过自己的行动。
const NOTE_FLAG = 'wolfbook_room_note:'
const roomNote = ref(null)
const notesOpen = ref(false)
let notesBusy = false
let notesRestored = false
let nightStepKey = ''
const noteSaver = createNoteSaver({ save: (value) => userData.saveSession(encodeNote(value, new Date().toISOString())) })
const canNote = computed(() => Boolean(snapshot.value?.selfPlayer?.seatNo) && !snapshot.value.judgeViewer && view.value !== 'lobby')
const noteNames = computed(() => Object.fromEntries(
  (snapshot.value?.players || []).filter((player) => player.seatNo).map((player) => [player.seatNo, player.nickname || '']),
))
const noteAvatars = computed(() => Object.fromEntries(
  (snapshot.value?.players || []).filter((player) => player.seatNo && player.avatar).map((player) => [player.seatNo, player.avatar]),
))
const noteSpeaker = computed(() => roomSpeaker(snapshot.value))

async function loadRoomNote(id) {
  try {
    const session = await userData.getSessionById(id)
    return session && isNoteV2(session) ? decodeNote(session) : null
  } catch (error) {
    return null
  }
}

function setRoomNote(note) {
  const synced = syncFromSnapshot(note, snapshot.value)
  roomNote.value = synced
  if (synced !== note) noteSaver.touch(synced)
}

async function openNotes() {
  if (!canNote.value || notesBusy) return
  if (!roomNote.value) {
    notesBusy = true
    const id = roomNoteId(snapshot.value)
    const existing = await loadRoomNote(id)
    const note = existing || createRoomNote(snapshot.value, board.value, new Date().toISOString())
    setRoomNote(note)
    if (!existing) noteSaver.touch(roomNote.value)
    try {
      uni.setStorageSync(NOTE_FLAG + id, 1)
    } catch (error) {
      // 记不住也没关系，下次点开会再去取
    }
    notesBusy = false
  }
  notesTop.value = 0
  notesScroll = 0
  notesHome = null
  notesOpen.value = true
}

// 笔记面板自己滚：点开的座位被座位面板挡住时，往上滚一点让那一排停在面板上方
const notesTop = ref(0)
let notesScroll = 0
function onNotesScroll(event) {
  notesScroll = event.detail?.scrollTop || 0
}
let notesHome = null
function scrollNotesTo(value) {
  // scroll-top 要先对上现在的位置再改，不然同一个值不会再滚
  notesTop.value = notesScroll
  nextTick(() => {
    notesTop.value = value
  })
}
function onNotesReveal(delta) {
  if (notesHome === null) notesHome = notesScroll
  scrollNotesTo(notesScroll + delta)
}
// 座位面板收起：滚回点开之前的位置
function onNotesSettle() {
  if (notesHome === null) return
  scrollNotesTo(notesHome)
  notesHome = null
}

function onNoteChange(next) {
  roomNote.value = next
  noteSaver.touch(next)
}

// 每次拿到新快照：已经有笔记就并进公开信息；这台手机上记过这个房间，就在后台把笔记取回来接着记
function followNotes(data) {
  const stepKey = data.currentPhase === 'night_action' ? `${data.currentDay}:${data.currentStep?.index}` : ''
  if (stepKey && stepKey !== nightStepKey) notesOpen.value = false
  nightStepKey = stepKey
  if (roomNote.value) {
    setRoomNote(roomNote.value)
    return
  }
  if (notesRestored || !canNote.value) return
  notesRestored = true
  const id = roomNoteId(data)
  let known = false
  try {
    known = Boolean(uni.getStorageSync(NOTE_FLAG + id))
  } catch (error) {
    known = false
  }
  if (known) loadRoomNote(id).then((note) => { if (note && !roomNote.value) setRoomNote(note) })
}

function safeDecode(value) {
  if (!value) return ''
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

function redirectInviteToLogin() {
  storage.setPendingJudgeInvite({ roomId: roomId.value, boardName: safeDecode(loadOptions.value.boardName || '') })
  uni.showToast({ title: '登录后可继续进入法官房', icon: 'none' })
  setTimeout(() => goToUserTab(), 240)
}

// 自己离开 / 解散了房间：停掉轮询，轻提示后回开局页
function leaveRoomPage(text) {
  if (redirected) return
  redirected = true
  stopPolling()
  uni.showToast({ title: text, icon: 'none' })
  setTimeout(() => uni.switchTab({ url: '/pages/judge/index' }), 600)
}

// 房间被房主解散了、自己被请离了：提示一次，回到开局页
function exitRoom(text) {
  if (redirected) return
  redirected = true
  stopPolling()
  showModal({
    title: '已退出房间',
    content: text,
    showCancel: false,
    success: () => uni.switchTab({ url: '/pages/judge/index' }),
  })
}

// 旧版小程序建的房间（旧流程）已经不再支持：提示一次，回到开局页
function goLegacy() {
  if (redirected) return
  redirected = true
  stopPolling()
  showModal({
    title: '房间已失效',
    content: '这个房间是旧版小程序建的，新版不再支持，请重新建房。',
    showCancel: false,
    success: () => uni.switchTab({ url: '/pages/judge/index' }),
  })
}

function applySnapshot(data) {
  if (!data) return
  if (data.autoJudge === false) {
    goLegacy()
    return
  }
  snapshot.value = data
  if (data.serverNow) {
    serverOffset = clockOffset(data.serverNow, Date.now())
    now.value = Date.now() + serverOffset
  }
  storage.clearPendingJudgeInvite()
  loadBoard(data.boardId)
  narrate()
  followNotes(data)
}

async function loadBoard(boardId) {
  if (!boardId || board.value?.id === boardId) return
  try {
    board.value = await api.getBoardDetail(boardId)
  } catch (error) {
    board.value = null
  }
}

async function refresh() {
  if (!roomId.value || redirected) return
  if (!hasAuthToken()) {
    stopPolling()
    redirectInviteToLogin()
    return
  }
  let result
  const requestedAt = Date.now()
  try {
    result = { ok: true, snapshot: await api.getJudgeRoom(roomId.value) }
  } catch (error) {
    result = { ok: false, code: error?.code, message: error?.message }
  }
  const merged = mergePollResult(snapshot.value, result, { requestedAt, lastActionAt })
  pollError.value = merged.error
  if (merged.exit) {
    exitRoom(merged.exit)
    return
  }
  if (merged.gone) {
    roomGone.value = true
    stopPolling()
    return
  }
  if (result.ok) {
    if (merged.snapshot !== snapshot.value) applySnapshot(merged.snapshot)
  } else if (!snapshot.value && merged.error) {
    uni.showToast({ title: merged.error, icon: 'none' })
  }
}

// setTimeout 链：上一次请求回来才排下一次，网络慢时不会堆积请求。
function schedulePoll() {
  stopPolling()
  if (!active || redirected || roomGone.value) return
  const delay = pollInterval(snapshot.value)
  if (!delay) return
  pollTimer = setTimeout(async () => {
    pollTimer = null
    try {
      await refresh()
    } catch (error) {
      // 这一次出意外也照样排下一次，不能让轮询就此停掉
    } finally {
      schedulePoll()
    }
  }, delay)
}

function stopPolling() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

function voiceStorageKey() {
  return `wolfbook:judgeVoice:${roomId.value}`
}

function loadPlayedKeys() {
  try {
    const saved = uni.getStorageSync(voiceStorageKey())
    playedKeys = Array.isArray(saved) ? saved : []
  } catch (error) {
    playedKeys = []
  }
}

function savePlayedKeys() {
  try {
    uni.setStorageSync(voiceStorageKey(), playedKeys)
  } catch (error) {
    // 存不了就只在内存里记
  }
}

function syncBgm() {
  bgmPlayer.sync(nightBgm(snapshot.value, { on: voiceOn.value && active, url: NIGHT_BGM_URL, speaking: voicePlaying }))
}

function narrate() {
  syncBgm()
  if (!voicePlayer.supported || !voiceOn.value || voicePlaying || !active) return
  const next = voiceCue(snapshot.value, now.value, playedKeys)
  if (!shouldPlay(next, playedKeys)) return
  playedKeys = rememberCue(playedKeys, next)
  savePlayedKeys()
  voicePlaying = true
  const run = ++voiceRun
  // 每句最多等 10 秒：来电或别的应用抢走音频时播放回调可能永远不来，超时就放开，后面的照常播
  withTimeout(voicePlayer.playScript(next), next.lines.length * 10000)
    .catch(() => {
      if (run !== voiceRun) return
      voicePlayer.stop()
      ;({ playedKeys, failures: voiceFailures } = retryCue(playedKeys, next, voiceFailures))
      savePlayedKeys()
    })
    .finally(() => {
      if (run === voiceRun) voicePlaying = false
    })
}

function toggleVoice() {
  voiceOn.value = !voiceOn.value
  if (!voiceOn.value) {
    stopVoice()
    bgmPlayer.stop()
    return
  }
  syncBgm()
  if (!voicePlayer.supported) return
  // 打开时先试播一句：房主当场知道这台手机有没有声音；合成失败、播放失败分开提示
  stopVoice()
  const run = ++voiceRun
  voicePlaying = true
  withTimeout(voicePlayer.playScript({ lines: ['播报已开启'] }), 10000)
    .catch((error) => {
      if (run === voiceRun) uni.showToast({ title: error?.message || '播报没有声音', icon: 'none' })
    })
    .finally(() => {
      if (run === voiceRun) voicePlaying = false
    })
}

// 停播时当前那句的播放回调可能不再回来，直接放开「正在播」
function stopVoice() {
  voicePlayer.stop()
  voiceRun += 1
  voicePlaying = false
}

function startClock() {
  stopClock()
  now.value = Date.now() + serverOffset
  clockTimer = setInterval(() => {
    now.value = Date.now() + serverOffset
    narrate()
  }, 1000)
}

function stopClock() {
  if (clockTimer) {
    clearInterval(clockTimer)
    clockTimer = null
  }
}

function keepScreenOn(on) {
  try {
    uni.setKeepScreenOn({ keepScreenOn: on })
  } catch (error) {
    // H5 没有这个接口
  }
}

/** 视图里的提交统一走这里：成功换快照；4002（状态刚变）只刷新不弹错；其他错误弹翻译好的文案。 */
async function runAction(task) {
  if (actionLoading.value) return false
  actionLoading.value = true
  try {
    const data = await task()
    lastActionAt = Date.now()
    applySnapshot(data)
    return true
  } catch (error) {
    if (error?.code === 4002) {
      await refresh()
    } else {
      uni.showToast({ title: error?.message || '操作失败', icon: 'none' })
    }
    return false
  } finally {
    actionLoading.value = false
  }
}

/** 视图组件 emit('act', { kind, ... })，在这里换成接口调用。 */
function onAct(event) {
  const id = snapshot.value?.roomId || roomId.value
  const tasks = {
    join: () => api.joinJudgeRoom(id),
    ready: () => api.toggleJudgeReady(id),
    unready: () => api.toggleJudgeReady(id),
    start: () => api.startJudgeRoom(id),
    rules: () => api.updateJudgeRoomRules(id, event.rules),
    night: () => api.submitJudgeNightAction(id, { actionType: event.actionType, targetSeatNo: event.targetSeatNo }),
    advance: () => api.advanceJudgeRoom(id, event.payload),
    skill: () => api.submitJudgeSkill(id, { decision: event.decision, targetSeatNo: event.targetSeatNo }),
    sheriff: () => api.submitJudgeSheriff(id, event.payload),
    vote: () => api.submitJudgeVote(id, { targetSeatNo: event.targetSeatNo }),
    seat: () => api.changeJudgeSeat(id, event.payload),
    remove: () => api.removeJudgePlayer(id, event.seatNo),
    leave: () => api.leaveJudgeRoom(id).then(() => {
      leaveRoomPage('已离开房间')
      return null
    }),
    dissolve: () => api.dissolveJudgeRoom(id).then(() => {
      leaveRoomPage('房间已解散')
      return null
    }),
    selfDestruct: () => api.selfDestructJudge(id),
    duel: () => api.duelJudge(id, event.targetSeatNo),
  }
  if (event?.kind === 'judgeSeat') {
    openJudgeSeat(event.seatNo)
    return
  }
  const task = tasks[event?.kind]
  if (task) runAction(task)
}

function tapHostItem(item) {
  if (item.disabled || actionLoading.value) return
  onAct({ kind: 'advance', payload: item.payload })
}

// 提前结束：记流局，二次确认
function confirmFinish() {
  if (actionLoading.value) return
  showModal({
    title: '提前结束',
    content: '提前结束这一局，记为流局？',
    confirmText: '结束',
    success: (result) => {
      if (result.confirm) onAct({ kind: 'advance', payload: { action: 'finish' } })
    },
  })
}

// 房主右上方的设置菜单：暂停 / 继续游戏、提前结束（声音开关在顶栏）
function openMenu() {
  const items = menuItems.value
  if (!items.length) return
  showActionSheet({
    itemList: items.map((item) => item.label),
    success: (result) => {
      const item = items[result.tapIndex]
      if (!item) return
      if (item.key === 'finish') confirmFinish()
      else onAct({ kind: 'advance', payload: { action: item.key } })
    },
  })
}

// 法官点座位（用户给定：以牌桌为主叠加法官功能）：这个人的身份和经历，能点杀、正在发言时能跳过
function openJudgeSeat(seatNo) {
  const sheet = judgeSeatSheet(snapshot.value, seatNo)
  if (!sheet) return
  const player = (snapshot.value?.players || []).find((item) => item.seatNo === seatNo)
  showActionSheet({
    title: sheet.title,
    content: sheet.content,
    itemList: sheet.items.map((item) => item.label),
    success: (result) => {
      const item = sheet.items[result.tapIndex]
      if (!item) return
      if (item.key === 'skip') onAct({ kind: 'advance', payload: { action: 'next_speaker' } })
      else if (item.key === 'forceOut' && player) confirmForceOut(player)
    },
  })
}

function confirmSelfDestruct() {
  showModal({
    title: '自爆',
    content: '确定自爆？自爆后直接出局，有 60 秒遗言',
    confirmText: '自爆',
    success: (result) => {
      if (!result.confirm) return
      identityOpen.value = false
      onAct({ kind: 'selfDestruct' })
    },
  })
}

// 骑士决斗（roles/骑士.md）：选一个人，再确认一次；全场只知道对方是狼人还是好人。
// 按钮一直在（活着的骑士），警上发言、夜里、用过之后是禁用状态，点了没反应
function tapDuel() {
  if (identity.value?.duel?.enabled) chooseDuel()
}

function chooseDuel() {
  const targets = duelTargets(snapshot.value)
  if (!targets.length) return
  showActionSheet({
    title: '决斗',
    content: '对方是狼人：他出局，直接天黑；是好人：你出局。整局只有一次',
    itemList: targets.map((target) => target.label),
    success: (result) => {
      const target = targets[result.tapIndex]
      if (!target) return
      showModal({
        title: '决斗',
        content: `确定决斗 ${target.label}？`,
        confirmText: '决斗',
        success: (answer) => {
          if (!answer.confirm) return
          identityOpen.value = false
          onAct({ kind: 'duel', targetSeatNo: target.seatNo })
        },
      })
    },
  })
}

function pauseOrResume() {
  onAct({ kind: 'advance', payload: { action: judgeBar.value.resume ? 'resume' : 'pause' } })
}

function confirmForceOut(player) {
  showModal({
    title: '点杀',
    content: `确定让 ${player.seatNo} 号 ${player.nickname || ''} 直接出局？`,
    confirmText: '点杀',
    success: (result) => {
      if (result.confirm) onAct({ kind: 'advance', payload: { action: 'force_out', forceOutSeatNo: player.seatNo } })
    },
  })
}

function judgeSharePayload() {
  const room = snapshot.value
  const boardName = encodeURIComponent(room?.boardName || '')
  return {
    title: room?.boardName ? `房号 ${room.roomCode || ''} · 来「${room.boardName}」入座` : '邀请你加入狼人杀电子法官房间',
    path: `/pages/judge/room?roomId=${room?.roomId || roomId.value}&boardName=${boardName}&fromShare=1`,
    imageUrl: board.value?.backgroundImage || board.value?.coverImage || '',
  }
}

onLoad((options) => {
  loadOptions.value = options || {}
  roomId.value = options?.roomId || ''
  if (!roomId.value) {
    uni.showToast({ title: '缺少房间号', icon: 'none' })
    setTimeout(() => uni.switchTab({ url: '/pages/judge/index' }), 180)
    return
  }
  const boardName = safeDecode(options?.boardName || '')
  if (boardName) {
    storage.setPendingJudgeInvite({ roomId: roomId.value, boardName })
  }
  loadPlayedKeys()
})

onShareAppMessage(() => judgeSharePayload())

onShow(async () => {
  active = true
  keepScreenOn(true)
  startClock()
  await refresh()
  schedulePoll()
})

onHide(() => {
  active = false
  stopPolling()
  stopClock()
  stopVoice()
  bgmPlayer.stop()
  keepScreenOn(false)
  noteSaver.flush()
})

onUnload(() => {
  active = false
  stopPolling()
  stopClock()
  voicePlayer.destroy()
  bgmPlayer.destroy()
  keepScreenOn(false)
  noteSaver.flush()
})
</script>

<template>
  <view class="nt-page" :class="{ 'nt-page--bar': hasBar }">
    <NavBar back :title="title">
      <template #side>
        <!-- 玩家看「笔记」（公开信息自动记在里面），不再有「记录」；法官没有笔记，对局中还用「记录」。结束后结果页底部有「对局记录」 -->
        <text v-if="snapshot && snapshot.judgeViewer && view !== 'lobby' && view !== 'result'" class="jr-logbtn" @tap="logOpen = true">记录</text>
        <text v-if="identity" class="jr-logbtn" @tap="identityOpen = true">身份</text>
        <text v-if="canNote" class="jr-logbtn" @tap="openNotes">笔记</text>
        <text v-if="snapshot && snapshot.judgeViewer && view !== 'lobby'" class="jr-judgechip">法官</text>
        <text v-if="snapshot && snapshot.host && hasSound" class="jr-logbtn" @tap="toggleVoice">{{ voiceOn ? '声音开' : '声音关' }}</text>
      </template>
      <template #right>
        <view v-if="menuItems.length" class="jr-gear" aria-label="设置" @tap="openMenu">
          <image class="jr-gear__icon" src="/static/icons/settings.svg" mode="aspectFit" />
        </view>
      </template>
    </NavBar>
    <view v-if="pollError" class="nt-gutter">
      <view class="jr-warn">{{ pollError }}</view>
    </view>
    <view v-if="!snapshot && roomGone" class="jr-loading">房间不存在或已经关闭</view>
    <view v-else-if="!snapshot" class="jr-loading">正在进入房间…</view>
    <LobbyView v-else-if="view === 'lobby'" :snapshot="snapshot" :board="board" :now="now" :busy="actionLoading" @act="onAct" />
    <NightView v-else-if="view === 'night'" :snapshot="snapshot" :board="board" :now="now" :busy="actionLoading" :stage-height="stageHeight" :bar="hasBar" @fit="stageHeight = $event" @act="onAct" />
    <PendingView v-else-if="view === 'pending'" :snapshot="snapshot" :now="now" :busy="actionLoading" :stage-height="stageHeight" :bar="hasBar" @fit="stageHeight = $event" @act="onAct" />
    <SheriffView v-else-if="view === 'sheriff'" :snapshot="snapshot" :now="now" :busy="actionLoading" :stage-height="stageHeight" :bar="hasBar" @fit="stageHeight = $event" @act="onAct" />
    <AnnounceView v-else-if="view === 'announce'" :snapshot="snapshot" :now="now" :busy="actionLoading" :stage-height="stageHeight" :bar="hasBar" @fit="stageHeight = $event" @act="onAct" />
    <SpeechView v-else-if="view === 'speech'" :snapshot="snapshot" :now="now" :busy="actionLoading" :stage-height="stageHeight" :bar="hasBar" @fit="stageHeight = $event" @act="onAct" />
    <VoteView v-else-if="view === 'vote' || view === 'voteResult'" :snapshot="snapshot" :now="now" :busy="actionLoading" :stage-height="stageHeight" :bar="hasBar" @fit="stageHeight = $event" @act="onAct" />
    <ResultView v-else-if="view === 'result'" :snapshot="snapshot" :board="board" @open-log="logOpen = true" />
    <view v-else class="nt-gutter">
      <view class="nt-panel jr-placeholder">
        <text class="jr-placeholder__title">{{ snapshot.boardName }}</text>
        <text class="jr-placeholder__phase">{{ title }}</text>
      </view>
    </view>

    <!-- 法官暂停：所有人同一张遮罩，倒计时停表；导航栏（返回、记录、身份）和法官的操作栏浮在遮罩上面 -->
    <view v-if="overlay" class="jr-pause">
      <text class="jr-pause__moon">☾</text>
      <text class="jr-pause__title serif">{{ overlay.title }}</text>
      <text class="jr-pause__hint">{{ overlay.hint }}</text>
      <view v-if="overlay.canResume" class="nt-btn nt-btn--gold jr-pause__resume" :class="{ 'nt-btn--disabled': actionLoading }" @tap="onAct({ kind: 'advance', payload: { action: 'resume' } })">继续游戏</view>
    </view>

    <view v-if="view !== 'lobby' && (hasJudgeBar || hostItems.length)" class="nt-bar jr-judgebar">
      <view
        v-for="item in hostItems"
        :key="item.action"
        class="nt-btn jr-judgebar__btn"
        :class="['nt-btn--gold', { 'nt-btn--disabled': item.disabled || actionLoading }]"
        @tap="tapHostItem(item)"
      >{{ item.label }}</view>
      <view
        v-if="hasJudgeBar"
        class="nt-btn jr-judgebar__btn"
        :class="[judgeBar.resume ? 'nt-btn--gold' : 'nt-btn--ghost', { 'nt-btn--disabled': actionLoading }]"
        @tap="pauseOrResume"
      >{{ judgeBar.resume ? '继续游戏' : '暂停游戏' }}</view>
    </view>

    <LogSheet v-if="logOpen && snapshot" :snapshot="snapshot" @close="logOpen = false" />

    <view v-if="identityOpen && identity" class="jr-sheet jr-identity" @tap="identityOpen = false">
      <view class="nt-ink jr-identity__card" @tap.stop>
        <view class="nt-card jr-identity__art">
          <view class="nt-card__frame">
            <image v-if="identity.art" class="nt-card__img" :src="identity.art" mode="aspectFill" webp />
            <view v-else class="nt-card__back">
              <view class="nt-card__line" />
              <text class="nt-card__moon">☾</text>
            </view>
          </view>
        </view>
        <text class="jr-identity__seat">{{ identity.seatNo }} 号{{ identity.alive ? '' : ' · 已出局' }}</text>
        <text class="jr-identity__role serif">{{ identity.roleName }}</text>
        <text class="jr-identity__camp" :class="{ 'jr-identity__camp--wolf': identity.wolf }">{{ identity.camp }}</text>
        <view v-if="identity.canSelfDestruct" class="nt-btn jr-identity__explode" @tap="confirmSelfDestruct">自爆</view>
        <view
          v-if="identity.duel"
          class="nt-btn jr-identity__explode"
          :class="{ 'nt-btn--disabled': !identity.duel.enabled }"
          @tap="tapDuel"
        >决斗</view>
        <text v-if="identity.duel && identity.duel.hint" class="jr-identity__hint">{{ identity.duel.hint }}</text>
        <view class="nt-btn nt-btn--ghost jr-identity__close" @tap="identityOpen = false">盖上</view>
      </view>
    </view>

    <!-- 对局笔记：和「记录」同级的底部面板。公开信息自动记，自己只点判断 -->
    <view v-if="notesOpen && roomNote" class="jr-sheet" @tap="notesOpen = false">
      <scroll-view
        class="jr-notes"
        scroll-y
        scroll-with-animation
        :show-scrollbar="false"
        :scroll-top="notesTop"
        @scroll="onNotesScroll"
        @tap.stop
      >
        <view class="jr-notes__body">
          <view class="jr-notes__head">
            <text class="jr-notes__title serif">对局笔记</text>
            <text class="jr-notes__close" @tap="notesOpen = false">收起</text>
          </view>
          <NotesBoard
            room
            :note="roomNote"
            :me="snapshot.selfPlayer.seatNo"
            :speaking="noteSpeaker"
            :names="noteNames"
            :avatars="noteAvatars"
            :today="snapshot.currentDay || 1"
            @change="onNoteChange"
            @reveal="onNotesReveal"
            @settle="onNotesSettle"
          />
        </view>
      </scroll-view>
    </view>

    <NtDialogHost />
  </view>
</template>

<style scoped lang="scss">
.jr-warn {
  margin: 16rpx 0;
  padding: 16rpx 24rpx;
  border-radius: 16rpx;
  background: var(--warn-bg);
  color: var(--text);
  font-size: 24rpx;
}

.jr-placeholder {
  margin-top: 24rpx;
  padding: 32rpx;
  display: flex;
  flex-direction: column;
  gap: 12rpx;
}

.jr-placeholder__title {
  font-family: var(--serif);
  font-size: 36rpx;
  color: var(--text);
}

.jr-placeholder__phase {
  font-size: 26rpx;
  color: var(--text-2);
}

.jr-pause {
  position: fixed;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 15;
  background: var(--scrim-strong);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16rpx;
}

.jr-pause__moon {
  color: var(--gold);
  font-size: 96rpx;
}

.jr-pause__title {
  color: var(--text);
  font-size: 48rpx;
  font-weight: 900;
}

.jr-pause__hint {
  color: var(--text-2);
  font-size: 26rpx;
}

.jr-pause__resume {
  margin-top: 32rpx;
  min-width: 320rpx;
}

.jr-gear {
  width: 64rpx;
  height: 56rpx;
  border-radius: 20rpx;
  background: var(--panel-raised);
  display: flex;
  align-items: center;
  justify-content: center;
}

.jr-judgechip {
  margin-left: 12rpx;
  padding: 8rpx 18rpx;
  border-radius: 24rpx;
  background: rgba(242, 179, 61, 0.16);
  color: var(--gold);
  font-size: 22rpx;
  font-weight: 700;
}

.jr-gear__icon {
  width: 36rpx;
  height: 36rpx;
}

.jr-judgebar {
  z-index: 50;
  gap: 20rpx;
}

.jr-judgebar__btn {
  flex: 1;
  min-width: 0;
  padding-left: 8rpx;
  padding-right: 8rpx;
  font-size: 26rpx;
  white-space: nowrap;
}

.jr-sheet {
  position: fixed;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 60;
  background: var(--scrim);
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
}






.jr-notes {
  /* 几乎占满：上面留一条能看到牌桌、点一下收起 */
  height: 94vh;
  box-sizing: border-box;
  background: var(--bg);
  border-top: 4rpx solid var(--ink);
  border-radius: 48rpx 48rpx 0 0;
}

.jr-notes__body {
  box-sizing: border-box;
  padding: 24rpx 32rpx calc(env(safe-area-inset-bottom) + 40rpx);
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.jr-notes__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.jr-notes__title {
  font-size: 34rpx;
  font-weight: 900;
}

.jr-notes__close {
  flex-shrink: 0;
  height: 64rpx;
  padding: 0 24rpx;
  border-radius: 24rpx;
  border: 3rpx solid var(--ink);
  background: var(--panel);
  box-shadow: 3rpx 3rpx 0 var(--ink);
  display: flex;
  align-items: center;
  font-size: 26rpx;
}

.jr-logbtn {
  margin-left: 12rpx;
  padding: 8rpx 20rpx;
  border-radius: 24rpx;
  background: var(--panel-raised);
  color: var(--text);
  font-size: 24rpx;
}

.jr-identity {
  justify-content: center;
  align-items: center;
}

.jr-identity__card {
  width: 520rpx;
  padding: 36rpx;
  border-radius: 36rpx;
  background: var(--panel);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12rpx;
}

.jr-identity__art {
  width: 360rpx;
  margin-bottom: 12rpx;
}

.jr-identity__seat {
  color: var(--text-3);
  font-size: 24rpx;
}

.jr-identity__role {
  color: var(--text);
  font-size: 48rpx;
  font-weight: 900;
}

.jr-identity__camp {
  color: var(--camp-god);
  font-size: 26rpx;
  font-weight: 700;
}

.jr-identity__camp--wolf {
  color: var(--camp-wolf);
}

.jr-identity__explode {
  margin-top: 24rpx;
  width: 100%;
  background: var(--camp-wolf);
  color: var(--text);
}

.jr-identity__close {
  margin-top: 16rpx;
  width: 100%;
}

.jr-identity__hint {
  margin-top: 12rpx;
  font-size: 24rpx;
  color: var(--text-3);
}

.jr-loading {
  padding: 120rpx 0;
  text-align: center;
  color: var(--text-2);
  font-size: 26rpx;
}
</style>
