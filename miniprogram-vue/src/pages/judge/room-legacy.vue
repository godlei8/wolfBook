<script setup>
// 法官房间：按房间状态和身份切四种视图（原型 D6 ~ D9）。
//   大厅 lobby          → 等待入座（短房号、邀请、座位卡背）
//   对局中 + 法官        → 夜晚主持台（按板子夜间顺序逐步引导、点座位结算）/ 白天发言 / 放逐投票
//   对局中 + 玩家        → 翻身份牌（技能、点座位提交夜间行动和投票）
//   已结束 finished     → 对局结束（胜利阵营、全场身份）
import { computed, ref, watch } from 'vue'
import { onHide, onLoad, onShareAppMessage, onShow, onUnload } from '@dcloudio/uni-app'
import NavBar from '../../components/NavBar.vue'
import api from '../../services/api'
import storage from '../../services/storage'
import { campSegment, roleAccent } from '../../utils/board-rules.js'
import { openAssistant } from '../../utils/assistant-entry'
import { getJudgePhaseLabel } from '../../utils/judge/meta'
import { buildJudgeVoiceScript, createJudgeVoicePlayer } from '../../utils/judge/voice'
import { buildNightSteps, checkResult, isWolfTeam, stepHolders as holdersOfStep, witchPotions } from '../../utils/judge/night-order.js'
import { fromNow } from '../../utils/format'
import { getNavInsets, rpxToPx } from '../../utils/nav'
import { goToUserTab, hasAuthToken } from '../../utils/auth'

const roomId = ref('')
const snapshot = ref(null)
const board = ref(null)
const selfRole = ref(null)
const loading = ref(false)
const actionLoading = ref(false)
const announcement = ref('')
const nightActionType = ref('')
const nightTargetSeat = ref(0)
const nightNote = ref('')
const voteTargetSeat = ref(0)
const finishWinnerCamp = ref('')
const voiceAutoEnabled = ref(true)
const voicePlaying = ref(false)
const voiceCurrentLine = ref('')
const lastVoiceScriptKey = ref('')
// 主持台：夜里走到第几步（等于步数时进入「天亮结算」），以及结算时点选的出局座位 / 放逐座位
const stepIndex = ref(0)
const deadPick = ref([])
const exilePick = ref(0)
// 法官替当前这一步的角色代记行动时，等待点选目标座位的行动类型（刀人 / 毒药 / 查验 / 守护 / 行动）
const pickMode = ref('')
const cardRevealed = ref(false)
const showAllResults = ref(false)

let pollTimer = null
let pollFailures = 0
const connectionLost = ref(false)
const voicePlayer = createJudgeVoicePlayer()
const insets = getNavInsets()

const selfPlayer = computed(() => snapshot.value?.selfPlayer || null)
const judgeVoiceScript = computed(() => buildJudgeVoiceScript(snapshot.value))
const judgeVoiceSupported = computed(() => voicePlayer.supported)
const roomCode = computed(() => snapshot.value?.roomCode || '')
const status = computed(() => snapshot.value?.roomStatus || '')
const phase = computed(() => snapshot.value?.currentPhase || '')
const view = computed(() => {
  const value = snapshot.value
  if (!value) return 'loading'
  if (value.roomStatus === 'finished') return 'result'
  if (value.roomStatus === 'lobby') return 'lobby'
  if (value.judgeViewer) return 'host'
  if (value.selfPlayer?.playing) return 'player'
  return 'spectator'
})

// ---------- 角色资料（来自板子详情：头像、立绘、主题色） ----------
const roleMap = computed(() => {
  const map = {}
  ;(board.value?.roles || []).forEach((role) => {
    map[role.roleId] = role
  })
  return map
})

function roleOf(player) {
  if (!player?.roleName) return null
  const known = roleMap.value[player.roleId] || {}
  const base = { name: player.roleName, faction: player.faction, roleType: known.roleType }
  return {
    ...base,
    portrait: known.portrait || '',
    fullIllustration: known.fullIllustration || '',
    accent: known.accent || roleAccent(base),
    campColor: campSegment(base).text,
    campFill: campSegment(base).fill,
  }
}

// ---------- 座位 ----------
const seatedPlayers = computed(() =>
  (snapshot.value?.players || []).filter((item) => item.playing && item.seatNo).sort((a, b) => a.seatNo - b.seatNo),
)
const lobbySeats = computed(() => {
  const count = snapshot.value?.playerCount || 0
  const bySeat = {}
  seatedPlayers.value.forEach((item) => {
    bySeat[item.seatNo] = item
  })
  return Array.from({ length: count }, (_, index) => ({ seatNo: index + 1, player: bySeat[index + 1] || null }))
})
const aliveSeats = computed(() => seatedPlayers.value.filter((item) => item.alive))
const notReadyCount = computed(() => seatedPlayers.value.filter((item) => !item.ready).length)
const emptySeatCount = computed(() => Math.max(0, (snapshot.value?.playerCount || 0) - seatedPlayers.value.length))

// ---------- 主持台：夜间步骤 ----------
// 板子没配夜间顺序时按通用顺序兜底（utils/judge/night-order.js）
const nightSteps = computed(() => buildNightSteps(board.value))
const currentStep = computed(() => nightSteps.value[stepIndex.value] || null)
const currentKind = computed(() => currentStep.value?.kind || 'generic')
const resolvingNight = computed(() => phase.value === 'night_action' && stepIndex.value >= nightSteps.value.length)
const stepHolders = computed(() => holdersOfStep(currentStep.value, seatedPlayers.value))
// 法官代记时记在哪个座位上：这一步第一个存活的持有者
const stepActor = computed(() => stepHolders.value.find((item) => item.alive) || null)
const tonightActions = computed(() => snapshot.value?.nightActions || [])
const stepActions = computed(() => {
  const seats = stepHolders.value.map((item) => item.seatNo)
  return tonightActions.value.filter((item) => seats.includes(item.actorSeatNo))
})
const seatPlayer = (seatNo) => seatedPlayers.value.find((item) => item.seatNo === seatNo) || null
const actionTargets = (predicate, type) =>
  tonightActions.value
    .filter((item) => item.actionType === type && item.targetSeatNo && predicate(seatPlayer(item.actorSeatNo)))
    .map((item) => item.targetSeatNo)
const witchSeats = computed(() => seatedPlayers.value.filter((item) => (item.roleName || '').includes('女巫')).map((item) => item.seatNo))
const isWitch = (player) => !!player && witchSeats.value.includes(player.seatNo)
// 狼队今晚提交的目标 = 刀口
const knifeSeats = computed(() => [...new Set(actionTargets((player) => isWolfTeam(player), '刀人'))])
const savedSeats = computed(() => actionTargets(isWitch, '解药'))
const poisonSeats = computed(() => actionTargets(isWitch, '毒药'))
const guardSeats = computed(() => actionTargets(() => true, '守护'))
const potions = computed(() => witchPotions(snapshot.value?.nightHistory || [], witchSeats.value))
// 女巫只有第一夜可以自救
const witchKnifed = computed(() => knifeSeats.value.some((seat) => witchSeats.value.includes(seat)))
const firstNight = computed(() => snapshot.value?.currentDay === 1)
const canSave = computed(() => potions.value.antidote > 0 && knifeSeats.value.length > 0 && (firstNight.value || !witchKnifed.value))
// 天亮时建议的出局座位：刀口（被救或被守则幸免，同守同救照样出局）+ 毒药目标
const suggestedDead = computed(() => {
  const dead = knifeSeats.value.filter((seat) => savedSeats.value.includes(seat) === guardSeats.value.includes(seat))
  return [...new Set([...dead, ...poisonSeats.value])]
})
const seatsText = (seats) => [...seats].sort((a, b) => a - b).map((seat) => `${seat} 号`).join('、')
const stepHoldersText = computed(() =>
  stepHolders.value.length
    ? stepHolders.value.map((item) => `${item.seatNo} 号 · ${item.nickname}${item.alive ? '' : '（已出局）'}`).join('、')
    : '这个角色不在本局座位上',
)
// 台词：女巫这一步把刀口高亮出来
const stepScript = computed(() => {
  const step = currentStep.value
  if (!step) return []
  if (step.kind === 'witch') {
    if (!knifeSeats.value.length) return [{ text: '昨晚是平安夜，你要用毒药吗？' }]
    return [{ text: '昨晚倒下的是 ' }, { text: seatsText(knifeSeats.value), hl: true }, { text: '，你要用解药吗？你要用毒药吗？' }]
  }
  return [{ text: step.action }]
})
// 这一步已经记下的行动（最近一条）
const stepRecord = computed(() => {
  const list = stepActions.value
  return list.length ? actionText(list[list.length - 1]) : ''
})
function actionText(item) {
  const actor = seatPlayer(item.actorSeatNo)
  const target = item.targetSeatNo ? `${item.targetSeatNo} 号` : ''
  switch (item.actionType) {
    case '刀人':
      return `狼人选择击杀 ${target}`
    case '空刀':
      return '狼人今晚空刀'
    case '解药':
      return `女巫用解药救了 ${target}`
    case '毒药':
      return `女巫毒了 ${target}`
    case '不用药':
      return '女巫今晚不用药'
    case '查验':
      return `${actor?.roleName || '预言家'}查验 ${target}：${checkResult(seatPlayer(item.targetSeatNo))}`
    case '守护':
      return `守卫守护 ${target}`
    case '空守':
      return '守卫今晚空守'
    case '确认':
      return `${actor?.roleName || ''}已确认状态`
    default:
      return `${item.actorSeatNo} 号${actor?.roleName ? ` ${actor.roleName}` : ''}：${item.actionType}${target ? ` → ${target}` : ''}`
  }
}
const logRows = computed(() =>
  tonightActions.value.map((item) => ({
    key: `${item.actorPlayerId}_${item.createTime}`,
    role: roleOf(seatPlayer(item.actorSeatNo)),
    text: actionText(item),
    time: fromNow(item.createTime),
  })),
)
const PICK_HINTS = {
  刀人: '点下面「全场身份」里的座位选刀口',
  毒药: '点下面「全场身份」里的座位选毒药目标',
  查验: '点下面「全场身份」里的座位选查验对象',
  守护: '点下面「全场身份」里的座位选守护对象',
  行动: '点下面「全场身份」里的座位选行动目标',
}
const pickHint = computed(() => PICK_HINTS[pickMode.value] || '')
// 猎人：今晚被毒就不能开枪
const hunterPoisoned = computed(() => stepHolders.value.some((item) => poisonSeats.value.includes(item.seatNo)))
const knifeText = computed(() => seatsText(knifeSeats.value))
const deadPickText = computed(() => (deadPick.value.length ? `出局：${seatsText(deadPick.value)}` : '当前：平安夜'))
const dayLabel = computed(() => {
  const value = snapshot.value
  if (!value) return ''
  return phase.value === 'night_action' ? `第 ${value.currentDay} 夜` : `第 ${value.currentDay} 天`
})

// ---------- 结果 ----------
// 每个人这一局干了什么：查验、用药、守护（来自结束后公开的夜间记录）+ 出局时间
function playerRecord(player) {
  const mine = (snapshot.value?.nightHistory || []).filter((item) => item.actorSeatNo === player.seatNo && item.targetSeatNo)
  const parts = []
  const checks = mine.filter((item) => item.actionType === '查验')
  if (checks.length) {
    const wolves = checks.filter((item) => seatPlayer(item.targetSeatNo)?.faction === '狼人').map((item) => item.targetSeatNo)
    parts.push(wolves.length ? `查杀 ${seatsText([...new Set(wolves)])}` : `查验 ${checks.length} 次，都是金水`)
  }
  mine.filter((item) => item.actionType === '解药').forEach((item) => parts.push(`第 ${item.dayNo} 夜救 ${item.targetSeatNo} 号`))
  mine.filter((item) => item.actionType === '毒药').forEach((item) => parts.push(`第 ${item.dayNo} 夜毒 ${item.targetSeatNo} 号`))
  const guards = mine.filter((item) => item.actionType === '守护').length
  if (guards) parts.push(`守护 ${guards} 次`)
  const out = resultNote(player)
  if (out) parts.push(out)
  return parts.join(' · ')
}
const resultHeroHeight = `${Math.round(insets.top + rpxToPx(440))}px`
const resultAliveText = computed(() => {
  const camp = snapshot.value?.winnerCamp || ''
  if (!camp) return `存活 ${aliveSeats.value.length} 人`
  const alive = aliveSeats.value.filter((item) => camp.includes(item.faction)).length
  return `${camp} ${alive} 人存活`
})
const resultRows = computed(() => {
  const rows = seatedPlayers.value.map((item) => ({ ...item, role: roleOf(item), note: playerRecord(item) }))
  return showAllResults.value ? rows : rows.slice(0, 8)
})
const winnerArt = computed(() => {
  const camp = snapshot.value?.winnerCamp || ''
  const seen = new Set()
  const cards = []
  seatedPlayers.value.forEach((item) => {
    const role = roleOf(item)
    if (!role || seen.has(item.roleId) || !(role.fullIllustration || role.portrait)) return
    const inCamp = !camp || camp.includes(item.faction) || (camp.includes('好人') && item.faction === '好人')
    if (!inCamp) return
    seen.add(item.roleId)
    cards.push({ roleId: item.roleId, image: role.fullIllustration || role.portrait, accent: role.accent, rank: roleRank(role) })
  })
  // 最重要的角色放中间，其余左右交替往外排（扇形的 class 按位置从左到右）
  const picked = cards.sort((a, b) => a.rank - b.rank).slice(0, 5)
  const slots = []
  picked.forEach((card, index) => {
    if (index % 2 === 0) slots.push(card)
    else slots.unshift(card)
  })
  return slots
})
function roleRank(role) {
  if (role.roleType === '神职') return 0
  if (role.faction === '狼人' && role.roleType !== '狼人') return 1
  if (role.roleType === '平民') return 3
  return 2
}
const resultTitle = computed(() => {
  const camp = snapshot.value?.winnerCamp
  return camp ? `${camp}胜利` : '对局结束'
})

// ---------- 玩家：夜间行动预设 ----------
const ACTION_PRESETS = ['查验', '守护', '刀人', '解药', '毒药', '其他']
function defaultActionFor(role) {
  if (!role) return '其他'
  if (role.faction === '狼人') return '刀人'
  if (role.name.includes('预言')) return '查验'
  if (role.name.includes('守')) return '守护'
  if (role.name.includes('女巫')) return '解药'
  return '其他'
}
const selfRoleView = computed(() => roleOf(selfPlayer.value))
const selfSkill = computed(() => selfRole.value?.skill || '')

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
    goToUserTab()
  }, 240)
}

function startPolling() {
  stopPolling()
  if (snapshot.value?.roomStatus === 'finished') return
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

async function loadBoardMeta(boardId) {
  if (!boardId || board.value?.id === boardId) return
  try {
    board.value = await api.getBoardDetail(boardId)
  } catch (error) {
    board.value = null
  }
}

async function loadSelfRole(roleId) {
  if (!roleId || selfRole.value?.id === roleId) return
  try {
    selfRole.value = await api.getRoleDetail(roleId)
  } catch (error) {
    selfRole.value = null
  }
}

function applySnapshot(data) {
  snapshot.value = data
  // 用短房号进来的，拿到真实房间号后后续请求都用它
  if (data?.roomId && data.roomId !== roomId.value) {
    roomId.value = data.roomId
  }
  loadBoardMeta(data?.boardId)
  if (data?.selfPlayer?.roleId) {
    loadSelfRole(data.selfPlayer.roleId)
    if (!nightActionType.value) nightActionType.value = defaultActionFor(roleOf(data.selfPlayer))
  }
}

async function refreshRoom(showError = false) {
  if (!roomId.value) return
  if (!hasAuthToken()) {
    redirectInviteToLogin()
    return
  }
  const firstLoad = !snapshot.value
  if (firstLoad) {
    loading.value = true
  }
  const previousVoiceKey = judgeVoiceScript.value?.key || ''
  try {
    const data = await api.getJudgeRoom(roomId.value)
    applySnapshot(data)
    pollFailures = 0
    connectionLost.value = false
    storage.clearPendingJudgeInvite()
    if (data.roomStatus === 'finished') {
      stopPolling()
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
    pollFailures += 1
    if (pollFailures >= 3) {
      connectionLost.value = true
    }
    if (showError) {
      uni.showToast({ title: error?.message || '房间加载失败', icon: 'none' })
    }
  } finally {
    loading.value = false
  }
}

async function runAction(task) {
  if (actionLoading.value) return false
  actionLoading.value = true
  try {
    const previousVoiceKey = judgeVoiceScript.value?.key || ''
    applySnapshot(await task())
    const nextVoiceScript = buildJudgeVoiceScript(snapshot.value)
    if (
      voiceAutoEnabled.value
      && nextVoiceScript.lines.length
      && nextVoiceScript.key !== previousVoiceKey
      && nextVoiceScript.key !== lastVoiceScriptKey.value
    ) {
      playJudgeVoice(nextVoiceScript)
    }
    return true
  } catch (error) {
    uni.showToast({ title: error?.message || '操作失败', icon: 'none' })
    return false
  } finally {
    actionLoading.value = false
  }
}

function clearConsoleInputs() {
  announcement.value = ''
  deadPick.value = []
  exilePick.value = 0
  finishWinnerCamp.value = ''
}

function confirmJudgeAction(title, content) {
  return new Promise((resolve) => {
    uni.showModal({
      title,
      content,
      confirmText: '确认',
      confirmColor: '#E0403A',
      success: (res) => resolve(!!res.confirm),
      fail: () => resolve(false),
    })
  })
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
  if (!nightTargetSeat.value && nightActionType.value !== '其他') {
    uni.showToast({ title: '先点选一个座位', icon: 'none' })
    return
  }
  await runAction(() =>
    api.submitJudgeNightAction(roomId.value, {
      actionType: nightActionType.value || 'night_action',
      targetSeatNo: Number(nightTargetSeat.value) || null,
      note: nightNote.value.trim(),
    }),
  )
}

async function submitVote() {
  const targetSeatNo = Number(voteTargetSeat.value)
  if (!targetSeatNo) {
    uni.showToast({ title: '先点选要投的座位', icon: 'none' })
    return
  }
  await runAction(() => api.submitJudgeVote(roomId.value, { targetSeatNo }))
}

async function judgeBroadcast() {
  if (actionLoading.value) return
  if (!announcement.value.trim()) {
    uni.showToast({ title: '请输入公告内容', icon: 'none' })
    return
  }
  const ok = await runAction(() => api.advanceJudgeRoom(roomId.value, { action: 'broadcast', announcement: announcement.value.trim() }))
  if (ok) clearConsoleInputs()
}

async function judgeStartVote() {
  if (actionLoading.value) return
  const ok = await runAction(() => api.advanceJudgeRoom(roomId.value, { action: 'start_vote', announcement: announcement.value.trim() }))
  if (ok) clearConsoleInputs()
}

async function judgeResolveNight() {
  if (actionLoading.value) return
  const deadSeatNos = [...deadPick.value].sort((a, b) => a - b)
  const summary = deadSeatNos.length ? `昨夜出局：${deadSeatNos.map((seat) => `${seat}号`).join('、')}` : '昨夜平安夜，无人出局'
  const confirmed = await confirmJudgeAction('天亮结算', `${summary}。结算后无法撤销，确认继续？`)
  if (!confirmed) return
  const ok = await runAction(() =>
    api.advanceJudgeRoom(roomId.value, {
      action: 'resolve_night',
      deadSeatNos,
      announcement: announcement.value.trim(),
    }),
  )
  if (ok) clearConsoleInputs()
}

async function judgeResolveVote() {
  if (actionLoading.value) return
  const eliminatedSeatNo = Number(exilePick.value) || null
  const summary = eliminatedSeatNo ? `放逐 ${eliminatedSeatNo} 号` : '按当前票型自动判定（平票则无人出局）'
  const confirmed = await confirmJudgeAction('结算投票', `${summary}。结算后无法撤销，确认继续？`)
  if (!confirmed) return
  const ok = await runAction(() =>
    api.advanceJudgeRoom(roomId.value, {
      action: 'resolve_vote',
      eliminatedSeatNo,
      announcement: announcement.value.trim(),
    }),
  )
  if (ok) clearConsoleInputs()
}

function chooseWinnerAndFinish() {
  const options = ['好人', '狼人', '第三方', '暂不判定']
  uni.showActionSheet({
    itemList: options.map((item) => (item === '暂不判定' ? item : `${item}胜利`)),
    success: (res) => {
      finishWinnerCamp.value = options[res.tapIndex] === '暂不判定' ? '' : options[res.tapIndex]
      judgeFinish()
    },
  })
}

async function judgeFinish() {
  if (actionLoading.value) return
  const winnerCamp = finishWinnerCamp.value.trim()
  const summary = winnerCamp ? `胜利阵营：${winnerCamp}` : '未选择胜利阵营，将记为待法官判定'
  const confirmed = await confirmJudgeAction('结束对局', `${summary}。结束后整局不可恢复，确认结束？`)
  if (!confirmed) return
  const ok = await runAction(() =>
    api.advanceJudgeRoom(roomId.value, {
      action: 'finish',
      winnerCamp,
      announcement: announcement.value.trim(),
    }),
  )
  if (ok) clearConsoleInputs()
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

function toggleVoice() {
  if (voicePlaying.value) {
    stopJudgeVoice()
    return
  }
  playJudgeVoice()
}

function toggleVoiceAuto() {
  voiceAutoEnabled.value = !voiceAutoEnabled.value
}

function copyRoomId() {
  const value = roomCode.value || snapshot.value?.roomId
  if (!value) return
  uni.setClipboardData({ data: value })
}

function judgeSharePayload() {
  const room = snapshot.value
  const roomKey = room?.roomId || roomId.value
  const boardName = encodeURIComponent(room?.boardName || '')
  const title = room?.boardName
    ? `房号 ${room.roomCode || ''} · 来「${room.boardName}」入座`
    : '邀请你加入狼人杀电子法官房间'
  return {
    title,
    path: `/pages/judge/room?roomId=${roomKey}&boardName=${boardName}&fromShare=1`,
    imageUrl: board.value?.backgroundImage || room?.boardCoverImage || '',
  }
}

function openNote() {
  if (!snapshot.value?.boardId) return
  uni.navigateTo({
    url: `/pages/sessions/edit?source=judge&boardId=${snapshot.value.boardId}&boardName=${encodeURIComponent(snapshot.value.boardName || '')}`,
  })
}

function playAgain() {
  if (!snapshot.value?.boardId) return
  uni.redirectTo({ url: `/pages/judge/create?boardId=${snapshot.value.boardId}` })
}

function openCommunity() {
  if (!snapshot.value?.boardId) return
  // 社区是 tabBar 页面，navigateTo 带参数会静默失败；上下文走 storage 传一次
  storage.setCommunityFilterContext({
    boardId: snapshot.value.boardId,
    boardName: snapshot.value.boardName || '',
    source: 'judge',
  })
  uni.switchTab({ url: '/pages/community/index' })
}

function askAi() {
  openAssistant('judge_room', {
    page: 'judge/room',
    roomId: roomId.value,
    boardId: snapshot.value?.boardId || 0,
    boardName: snapshot.value?.boardName || '',
    phase: phase.value,
    judgeViewer: !!snapshot.value?.judgeViewer,
  })
}

// ---------- 主持台交互 ----------
function nextStep() {
  if (stepIndex.value < nightSteps.value.length) stepIndex.value += 1
  // 走到天亮时按今晚记录预填出局座位，法官可以再改
  if (resolvingNight.value && !deadPick.value.length) deadPick.value = [...suggestedDead.value]
}

function startPick(type) {
  if (!stepActor.value) {
    uni.showToast({ title: '这个角色不在场或已出局', icon: 'none' })
    return
  }
  pickMode.value = pickMode.value === type ? '' : type
}

// 线下面杀：玩家闭眼指人，法官在主持台替这个座位记下行动
async function recordStep(actionType, targetSeatNo = null) {
  const actor = stepActor.value
  if (!actor) {
    uni.showToast({ title: '这个角色不在场或已出局', icon: 'none' })
    return
  }
  pickMode.value = ''
  const ok = await runAction(() =>
    api.submitJudgeNightAction(roomId.value, { actionType, targetSeatNo, note: '', actorSeatNo: actor.seatNo }),
  )
  if (ok && actionType === '查验' && targetSeatNo) {
    uni.showToast({ title: `${targetSeatNo} 号是${checkResult(seatPlayer(targetSeatNo))}`, icon: 'none', duration: 2500 })
  }
}

function pickPoison() {
  if (!potions.value.poison) {
    uni.showToast({ title: '毒药已经用过了', icon: 'none' })
    return
  }
  startPick('毒药')
}

function saveKnifed() {
  if (!canSave.value) {
    uni.showToast({ title: potions.value.antidote ? '今晚没有可救的刀口' : '解药已经用过了', icon: 'none' })
    return
  }
  recordStep('解药', knifeSeats.value[0])
}

function toggleVoiceAutoWithTip() {
  toggleVoiceAuto()
  uni.showToast({ title: voiceAutoEnabled.value ? '已开启自动播报' : '已关闭自动播报', icon: 'none' })
}

function stepState(index) {
  if (resolvingNight.value || index < stepIndex.value) return 'done'
  return index === stepIndex.value ? 'now' : 'next'
}

function tapHostSeat(seat) {
  if (!seat.alive) return
  if (phase.value === 'night_action' && pickMode.value && !resolvingNight.value) {
    recordStep(pickMode.value, seat.seatNo)
    return
  }
  if (phase.value === 'night_action' && resolvingNight.value) {
    const list = [...deadPick.value]
    const index = list.indexOf(seat.seatNo)
    if (index >= 0) list.splice(index, 1)
    else list.push(seat.seatNo)
    deadPick.value = list
  } else if (phase.value === 'exile_vote') {
    exilePick.value = exilePick.value === seat.seatNo ? 0 : seat.seatNo
  }
}

function seatFlag(seat) {
  if (!seat.alive) return null
  if (phase.value === 'night_action') {
    if (deadPick.value.includes(seat.seatNo)) return { text: '出局', tone: 'dead' }
    if (!resolvingNight.value && stepHolders.value.some((item) => item.seatNo === seat.seatNo)) return { text: '行动中', tone: 'gold' }
    if (poisonSeats.value.includes(seat.seatNo)) return { text: '中毒', tone: 'wolf' }
    if (knifeSeats.value.includes(seat.seatNo)) {
      return savedSeats.value.includes(seat.seatNo) ? { text: '被救', tone: 'save' } : { text: '刀口', tone: 'wolf' }
    }
  }
  if (phase.value === 'exile_vote' && exilePick.value === seat.seatNo) return { text: '放逐', tone: 'dead' }
  return null
}

function tallyFor(seatNo) {
  return (snapshot.value?.voteTallies || []).find((item) => item.targetSeatNo === seatNo)?.voteCount || 0
}

// ---------- 玩家交互 ----------
function pickNightTarget(seatNo) {
  nightTargetSeat.value = nightTargetSeat.value === seatNo ? 0 : seatNo
}

function pickVoteTarget(seatNo) {
  voteTargetSeat.value = voteTargetSeat.value === seatNo ? 0 : seatNo
}

function resultNote(player) {
  if (player.alive) return ''
  return player.deathDay ? `第 ${player.deathDay} 天${getJudgePhaseLabel(player.deathPhase)}出局` : '已出局'
}

// 每进入新的一天 / 新阶段，主持台从第一步开始，清掉上一阶段的点选
watch(stepIndex, () => {
  pickMode.value = ''
})

watch(
  () => `${snapshot.value?.currentDay}-${snapshot.value?.currentPhase}`,
  () => {
    stepIndex.value = 0
    pickMode.value = ''
    deadPick.value = []
    exilePick.value = 0
    nightTargetSeat.value = 0
    voteTargetSeat.value = 0
  },
)

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
  <view class="nt-page nt-page--bar">
    <!-- ================= 等待入座（D6） ================= -->
    <block v-if="view === 'lobby'">
      <NavBar back title="等待入座" />
      <view class="nt-gutter">
        <view v-if="connectionLost" class="jr-warn">连接中断，正在重试…</view>
        <view class="nt-ink jr-room">
          <view class="jr-room__banner">
            <image class="jr-room__bg" :src="board?.backgroundImage || '/static/scenes/tavern.webp'" mode="aspectFill" webp />
          </view>
          <view class="jr-room__body">
            <view class="jr-room__code-row">
              <view class="jr-room__code">
                <text class="nt-meta">房号</text>
                <text class="jr-room__digits serif">{{ roomCode || '—' }}</text>
              </view>
              <view class="nt-btn nt-btn--ghost nt-btn--small" @tap="copyRoomId">复制</view>
            </view>
            <view class="jr-room__board">
              <view class="jr-stack">
                <view
                  v-for="(role, index) in (board?.cardRoles || []).slice(0, 5)"
                  :key="role.roleId"
                  class="nt-token jr-stack__token"
                  :style="{ marginLeft: index ? '-16rpx' : '0', zIndex: 6 - index }"
                >
                  <image v-if="role.portrait" class="nt-token__img" :src="role.portrait" mode="aspectFill" :style="{ borderColor: role.accent }" webp />
                  <view v-else class="nt-token__text jr-text--sm" :style="{ borderColor: role.accent }">{{ role.name.slice(0, 1) }}</view>
                </view>
              </view>
              <view class="jr-room__board-text">
                <text class="jr-room__board-name">{{ snapshot.boardName }} · {{ snapshot.playerCount }} 人</text>
                <text class="jr-room__board-meta">{{ snapshot.judgeViewer ? '你是法官，不入座' : '法官主持' }} · 手动主持</text>
              </view>
            </view>
            <button class="nt-btn nt-btn--cream nt-btn--block jr-share" open-type="share">邀请微信好友入座</button>
          </view>
        </view>

        <view class="jr-section">
          <text class="nt-h2">座位</text>
          <text class="nt-meta">{{ snapshot.joinedCount }} / {{ snapshot.playerCount }} 已入座 · {{ snapshot.readyCount }} 人已准备</text>
        </view>
        <view class="jr-seats">
          <view
            v-for="seat in lobbySeats"
            :key="seat.seatNo"
            class="jr-seat"
            :class="{ 'jr-seat--empty': !seat.player, 'jr-seat--self': seat.player && seat.player.self }"
          >
            <text class="jr-seat__no serif">{{ seat.seatNo }}</text>
            <block v-if="seat.player">
              <view class="jr-back">
                <view class="jr-back__line" />
                <text class="jr-back__moon">☾</text>
              </view>
              <text class="jr-seat__name">{{ seat.player.nickname }}</text>
              <text class="jr-seat__state" :class="{ 'jr-seat__state--ready': seat.player.ready }">{{ seat.player.ready ? '已准备' : '未准备' }}</text>
            </block>
            <text v-else class="jr-seat__empty">空位</text>
          </view>
        </view>

        <view class="jr-tip">
          <text class="jr-tip__icon">◉</text>
          <text class="jr-tip__text">开局后每人在手机上翻自己的身份牌，法官这边看全部身份。坐满并全部准备后才能发身份。</text>
        </view>
      </view>

      <view class="nt-bar">
        <view v-if="snapshot.canJoin" class="nt-btn nt-btn--gold jr-cta" @tap="joinRoom">加入并入座</view>
        <view v-else-if="snapshot.canStart" class="nt-btn nt-btn--gold jr-cta" @tap="startRoom">☾ 发身份开局</view>
        <view v-else-if="snapshot.canReady" class="nt-btn jr-cta" :class="selfPlayer?.ready ? 'nt-btn--ghost' : 'nt-btn--gold'" @tap="toggleReady">
          {{ selfPlayer?.ready ? '取消准备' : '我已准备' }}
        </view>
        <view v-else-if="snapshot.judgeViewer" class="nt-btn nt-btn--gold nt-btn--disabled jr-cta">
          还差 {{ emptySeatCount }} 人入座，{{ notReadyCount }} 人准备
        </view>
        <view v-else class="nt-btn nt-btn--ghost nt-btn--disabled jr-cta">房间已满或已开局</view>
      </view>
    </block>

    <!-- ================= 法官主持台（D7） ================= -->
    <block v-else-if="view === 'host'">
      <view class="jr-hostsky" :style="{ height: `${insets.top + 200}px` }">
        <image class="jr-hostsky__bg" src="/static/scenes/moon.webp" mode="aspectFill" webp />
      </view>
      <NavBar back overlay>
        <view class="jr-navtitle">
          <text class="jr-navtitle__main serif">{{ dayLabel }}</text>
          <text class="jr-navtitle__sub">房号 {{ roomCode }} · {{ snapshot.boardName }}</text>
        </view>
        <template #side>
          <view
            class="jr-speaker"
            :class="{ 'jr-speaker--on': voicePlaying, 'jr-speaker--off': !voiceAutoEnabled || !judgeVoiceSupported }"
            @tap="toggleVoice"
            @longpress="toggleVoiceAutoWithTip"
          >
            <view class="jr-speaker__box" />
            <view class="jr-speaker__cone" />
            <view class="jr-speaker__wave" />
          </view>
        </template>
      </NavBar>
      <view class="nt-gutter jr-host" :style="{ paddingTop: `${insets.top}px` }">
        <view v-if="connectionLost" class="jr-warn">连接中断，正在重试…</view>
        <text v-if="voiceCurrentLine" class="jr-voice__line">正在播报：{{ voiceCurrentLine }}</text>

        <!-- 夜晚 -->
        <block v-if="phase === 'night_action'">
          <view v-if="nightSteps.length" class="jr-progress">
            <view class="jr-progress__line" />
            <view
              v-for="(step, index) in nightSteps"
              :key="`${step.order}-${step.roleId}`"
              class="jr-progress__item"
              :class="`jr-progress__item--${stepState(index)}`"
              @tap="stepIndex = index"
            >
              <view class="jr-progress__token">
                <view v-if="stepState(index) === 'now'" class="jr-progress__ring" />
                <view class="nt-token jr-progress__img">
                  <image v-if="step.portrait" class="nt-token__img" :src="step.portrait" mode="aspectFill" :style="{ borderColor: step.accent }" webp />
                  <view v-else class="nt-token__text jr-text--md" :style="{ borderColor: step.accent }">{{ step.roleName.slice(0, 1) }}</view>
                </view>
                <view v-if="stepState(index) === 'done'" class="jr-progress__tick">✓</view>
              </view>
              <text class="jr-progress__name">{{ step.roleName }}</text>
            </view>
          </view>

          <view v-if="currentStep && !resolvingNight" class="nt-ink jr-step">
            <view class="jr-step__top">
              <view class="nt-card jr-step__card">
                <view class="nt-card__frame" :style="{ backgroundColor: currentStep.accent }">
                  <image v-if="roleMap[currentStep.roleId]?.fullIllustration" class="nt-card__img" :src="roleMap[currentStep.roleId].fullIllustration" mode="aspectFill" webp />
                  <view v-else class="nt-card__back">
                    <view class="nt-card__line" />
                    <text class="nt-card__moon">☾</text>
                  </view>
                </view>
              </view>
              <view class="jr-step__copy">
                <text class="jr-step__eyebrow">现在 · 第 {{ stepIndex + 1 }} 步</text>
                <text class="jr-step__title serif">{{ currentStep.roleName }}请睁眼</text>
                <text class="jr-step__holders">
                  {{ stepHoldersText }}
                </text>
              </view>
            </view>
            <view class="jr-step__body">
              <view class="jr-step__script serif">
                <text>“</text>
                <text v-for="(part, index) in stepScript" :key="index" :class="{ 'jr-step__hl': part.hl }">{{ part.text }}</text>
                <text>”</text>
              </view>

              <!-- 状态小标签 -->
              <view class="jr-step__chips">
                <block v-if="currentKind === 'witch'">
                  <text class="jr-pill jr-pill--save">解药 ×{{ potions.antidote }}</text>
                  <text class="jr-pill jr-pill--wolf">毒药 ×{{ potions.poison }}</text>
                  <text class="jr-pill">{{ firstNight ? '首夜可以自救' : '首夜已过，不能自救' }}</text>
                </block>
                <text v-else-if="currentKind === 'hunter'" class="jr-pill" :class="hunterPoisoned ? 'jr-pill--wolf' : 'jr-pill--save'">
                  {{ hunterPoisoned ? '今晚被毒，不能开枪' : '可以开枪' }}
                </text>
                <text v-else-if="currentKind !== 'wolf' && knifeSeats.length" class="jr-pill jr-pill--wolf">今晚刀口：{{ knifeText }}</text>
                <text v-if="stepRecord" class="jr-pill jr-pill--done">已记：{{ stepRecord }}</text>
                <text v-else-if="!stepActor" class="jr-pill">{{ stepHolders.length ? '已出局，直接下一步' : '这个角色不在场' }}</text>
              </view>

              <!-- 法官代记 -->
              <view class="jr-step__btns">
                <block v-if="currentKind === 'wolf'">
                  <view class="nt-btn nt-btn--ghost jr-step__btn jr-step__btn--wolf" :class="{ 'jr-step__btn--on': pickMode === '刀人' }" @tap="startPick('刀人')">选刀口</view>
                  <view class="nt-btn nt-btn--ghost jr-step__btn" @tap="recordStep('空刀')">空刀</view>
                </block>
                <block v-else-if="currentKind === 'witch'">
                  <view class="nt-btn nt-btn--ghost jr-step__btn jr-step__btn--save" :class="{ 'nt-btn--disabled': !canSave }" @tap="saveKnifed">
                    {{ knifeSeats.length ? `救 ${knifeSeats[0]} 号` : '无人可救' }}
                  </view>
                  <view
                    class="nt-btn nt-btn--ghost jr-step__btn jr-step__btn--wolf"
                    :class="{ 'jr-step__btn--on': pickMode === '毒药', 'nt-btn--disabled': !potions.poison }"
                    @tap="pickPoison"
                  >选人毒</view>
                  <view class="nt-btn nt-btn--ghost jr-step__btn" @tap="recordStep('不用药')">都不用</view>
                </block>
                <block v-else-if="currentKind === 'seer'">
                  <view class="nt-btn nt-btn--ghost jr-step__btn jr-step__btn--moon" :class="{ 'jr-step__btn--on': pickMode === '查验' }" @tap="startPick('查验')">选人查验</view>
                </block>
                <block v-else-if="currentKind === 'guard'">
                  <view class="nt-btn nt-btn--ghost jr-step__btn jr-step__btn--save" :class="{ 'jr-step__btn--on': pickMode === '守护' }" @tap="startPick('守护')">选人守护</view>
                  <view class="nt-btn nt-btn--ghost jr-step__btn" @tap="recordStep('空守')">空守</view>
                </block>
                <block v-else-if="currentKind === 'hunter'">
                  <view class="nt-btn nt-btn--ghost jr-step__btn" @tap="recordStep('确认')">已确认</view>
                </block>
                <block v-else>
                  <view class="nt-btn nt-btn--ghost jr-step__btn" :class="{ 'jr-step__btn--on': pickMode === '行动' }" @tap="startPick('行动')">选目标</view>
                  <view class="nt-btn nt-btn--ghost jr-step__btn" @tap="recordStep('不行动')">不行动</view>
                </block>
              </view>
              <text v-if="pickHint" class="jr-step__hint">{{ pickHint }}</text>
            </view>
          </view>

          <view v-if="resolvingNight" class="nt-ink jr-step">
            <view class="jr-step__body jr-step__body--flush">
              <text class="jr-step__eyebrow">天亮了</text>
              <text class="jr-step__title serif">点选昨夜出局的座位</text>
              <text class="nt-meta">在下面「全场身份」里点座位标记出局；不点就是平安夜。{{ knifeSeats.length ? `狼队刀口：${knifeText}` : '' }}</text>
              <text class="jr-step__picked">{{ deadPickText }}</text>
            </view>
          </view>
        </block>

        <!-- 白天发言 -->
        <view v-else-if="phase === 'day_speech'" class="nt-ink jr-step">
          <view class="jr-step__body jr-step__body--flush">
            <text class="jr-step__eyebrow">白天 · 发言</text>
            <text class="jr-step__title serif">{{ snapshot.latestAnnouncement || '天亮了，开始发言' }}</text>
            <textarea v-model="announcement" class="nt-textarea jr-announce" maxlength="200" placeholder="发一条公告给全场（可选）" placeholder-class="nt-placeholder" />
            <view class="jr-row">
              <view class="nt-btn nt-btn--ghost nt-btn--small" @tap="judgeBroadcast">发公告</view>
              <view class="nt-btn nt-btn--gold nt-btn--small" @tap="judgeStartVote">开启放逐投票</view>
            </view>
          </view>
        </view>

        <!-- 放逐投票 -->
        <view v-else-if="phase === 'exile_vote'" class="nt-ink jr-step">
          <view class="jr-step__body jr-step__body--flush">
            <text class="jr-step__eyebrow">放逐投票</text>
            <text class="jr-step__title serif">{{ exilePick ? `放逐 ${exilePick} 号？` : '按票型结算，或点座位指定放逐' }}</text>
            <view v-if="snapshot.voteTallies.length" class="jr-tally">
              <view v-for="item in snapshot.voteTallies" :key="item.targetSeatNo" class="jr-tally__row">
                <text class="jr-tally__seat serif">{{ item.targetSeatNo }} 号</text>
                <view class="jr-tally__bar"><view class="jr-tally__fill" :style="{ width: `${Math.min(100, item.voteCount * 18)}%` }" /></view>
                <text class="jr-tally__count">{{ item.voteCount }} 票</text>
              </view>
            </view>
            <text v-else class="nt-meta">还没有人投票。</text>
          </view>
        </view>

        <view class="jr-section">
          <text class="nt-h2">全场身份</text>
          <text class="nt-meta">只有法官看得到 · 存活 {{ aliveSeats.length }}</text>
        </view>
        <view class="jr-host-seats">
          <view
            v-for="seat in seatedPlayers"
            :key="seat.playerId"
            class="jr-hseat"
            :class="{ 'jr-hseat--dead': !seat.alive, 'jr-hseat--picked': deadPick.includes(seat.seatNo) || exilePick === seat.seatNo }"
            @tap="tapHostSeat(seat)"
          >
            <text class="jr-hseat__no serif">{{ seat.seatNo }}</text>
            <text v-if="seatFlag(seat)" class="jr-hseat__flag" :class="`jr-hseat__flag--${seatFlag(seat).tone}`">{{ seatFlag(seat).text }}</text>
            <view class="nt-token jr-hseat__token" :class="{ 'nt-token--dead': !seat.alive }">
              <image v-if="roleOf(seat)?.portrait" class="nt-token__img" :src="roleOf(seat).portrait" mode="aspectFill" :style="{ borderColor: roleOf(seat).accent }" webp />
              <view v-else class="nt-token__text jr-text--md" :style="{ borderColor: roleOf(seat)?.accent }">{{ (seat.roleName || seat.nickname || '?').slice(0, 1) }}</view>
            </view>
            <text class="jr-hseat__role">{{ seat.roleName || '—' }}</text>
            <text v-if="phase === 'exile_vote' && tallyFor(seat.seatNo)" class="jr-hseat__votes">{{ tallyFor(seat.seatNo) }} 票</text>
          </view>
        </view>

        <view v-if="logRows.length" class="nt-panel jr-log">
          <text class="jr-log__title">今晚记录（仅法官）</text>
          <view v-for="item in logRows" :key="item.key" class="jr-log__row">
            <view class="nt-token jr-log__token">
              <image v-if="item.role?.portrait" class="nt-token__img" :src="item.role.portrait" mode="aspectFill" :style="{ borderColor: item.role.accent }" webp />
              <view v-else class="nt-token__text jr-text--sm" :style="{ borderColor: item.role?.accent }">{{ (item.role?.name || '?').slice(0, 1) }}</view>
            </view>
            <text class="jr-log__text">{{ item.text }}</text>
            <text class="jr-log__time">{{ item.time }}</text>
          </view>
        </view>
        <view v-else-if="phase !== 'night_action' && snapshot.latestAnnouncement" class="nt-panel jr-log">
          <text class="jr-log__title">最近公告</text>
          <text class="jr-log__text">{{ snapshot.latestAnnouncement }}</text>
        </view>

        <view class="jr-finish" @tap="chooseWinnerAndFinish">结束对局并宣布胜负</view>
      </view>

      <view class="nt-bar">
        <view class="nt-btn nt-btn--ghost" @tap="askAi">速查</view>
        <block v-if="phase === 'night_action'">
          <view v-if="!resolvingNight" class="nt-btn nt-btn--gold jr-cta" @tap="nextStep">
            下一步：{{ nightSteps[stepIndex + 1] ? nightSteps[stepIndex + 1].roleName : '天亮' }} ›
          </view>
          <view v-else class="nt-btn nt-btn--gold jr-cta" @tap="judgeResolveNight">天亮结算 ›</view>
        </block>
        <view v-else-if="phase === 'day_speech'" class="nt-btn nt-btn--gold jr-cta" @tap="judgeStartVote">开启放逐投票 ›</view>
        <view v-else-if="phase === 'exile_vote'" class="nt-btn nt-btn--gold jr-cta" @tap="judgeResolveVote">结算投票 ›</view>
      </view>
    </block>

    <!-- ================= 玩家翻身份牌（D8） ================= -->
    <block v-else-if="view === 'player'">
      <view class="jr-sky">
        <image class="jr-sky__bg" src="/static/scenes/moon.webp" mode="aspectFill" webp />
      </view>
      <NavBar back overlay>
        <view class="jr-navtitle">
          <text class="jr-navtitle__main serif">{{ selfPlayer.seatNo }} 号 · {{ selfPlayer.nickname }}</text>
          <text class="jr-navtitle__sub">房号 {{ roomCode }} · {{ dayLabel }} · {{ getJudgePhaseLabel(phase) }}</text>
        </view>
      </NavBar>
      <view class="nt-gutter jr-player" :style="{ paddingTop: `${insets.top}px` }">
        <view v-if="connectionLost" class="jr-warn">连接中断，正在重试…</view>
        <view v-if="!selfPlayer.alive" class="jr-out">你已在{{ resultNote(selfPlayer) || '本局' }}，可以继续看局，不能再投票。</view>

        <view class="jr-reveal" @tap="cardRevealed = !cardRevealed">
          <view v-if="cardRevealed && selfRoleView" class="nt-card jr-reveal__card">
            <view class="nt-card__frame" :style="{ backgroundColor: selfRoleView.accent }">
              <image v-if="selfRoleView.fullIllustration" class="nt-card__img" :src="selfRoleView.fullIllustration" mode="aspectFill" webp />
              <view v-else class="nt-card__back">
                <text class="jr-reveal__glyph serif">{{ selfRoleView.name.slice(0, 1) }}</text>
              </view>
            </view>
            <view class="jr-reveal__plate">
              <text class="nt-meta">你的身份</text>
              <view class="jr-reveal__row">
                <text class="jr-reveal__name serif">{{ selfRoleView.name }}</text>
                <view class="jr-reveal__camp">
                  <view class="nt-dot" :style="{ backgroundColor: selfRoleView.campFill }" />
                  <text :style="{ color: selfRoleView.campColor }">{{ selfRoleView.faction }}阵营</text>
                </view>
              </view>
            </view>
          </view>
          <view v-else class="nt-card jr-reveal__card">
            <view class="nt-card__frame">
              <view class="nt-card__back">
                <view class="nt-card__line" />
                <text class="nt-card__moon jr-reveal__moon">☾</text>
              </view>
            </view>
            <view class="jr-reveal__plate jr-reveal__plate--back">
              <text class="jr-reveal__hint">{{ selfRoleView ? '按住下方按钮，或点一下翻牌' : '等待法官发身份' }}</text>
            </view>
          </view>
        </view>

        <view v-if="cardRevealed && selfSkill" class="nt-panel jr-skill">
          <text class="nt-label">你的技能</text>
          <text class="jr-skill__text serif">{{ selfSkill }}</text>
        </view>

        <view v-if="snapshot.latestAnnouncement" class="nt-panel jr-announce-card">
          <text class="nt-label">法官公告</text>
          <text class="jr-skill__note">{{ snapshot.latestAnnouncement }}</text>
        </view>

        <view v-if="snapshot.canSubmitNightAction" class="nt-panel">
          <view class="nt-panel__head">
            <text class="nt-h2">夜间行动</text>
            <text class="nt-meta">只有法官能看到</text>
          </view>
          <text class="nt-meta">面杀时闭眼指给法官就行，法官会在主持台替你记下；也可以在这里自己提交。</text>
          <view class="jr-presets">
            <text
              v-for="item in ACTION_PRESETS"
              :key="item"
              class="nt-chip"
              :class="{ 'nt-chip--on': nightActionType === item }"
              @tap="nightActionType = item"
            >{{ item }}</text>
          </view>
          <view class="jr-pick">
            <view
              v-for="seat in aliveSeats"
              :key="seat.playerId"
              class="jr-pick__seat"
              :class="{ 'jr-pick__seat--on': nightTargetSeat === seat.seatNo, 'jr-pick__seat--self': seat.self }"
              @tap="pickNightTarget(seat.seatNo)"
            >
              <text class="jr-pick__no serif">{{ seat.seatNo }}</text>
              <text class="jr-pick__nick">{{ seat.nickname }}</text>
            </view>
          </view>
          <input v-model="nightNote" class="nt-field jr-note" placeholder="补充说明（可选），比如：查到金水" placeholder-class="nt-placeholder" />
          <text v-if="snapshot.pendingNightAction?.submitted" class="nt-meta">
            已提交：{{ snapshot.pendingNightAction.targetSeatNo ? `${snapshot.pendingNightAction.targetSeatNo} 号` : '无目标' }}{{ snapshot.pendingNightAction.note ? ` · ${snapshot.pendingNightAction.note}` : '' }}，可以改了再交
          </text>
          <view class="nt-btn nt-btn--gold nt-btn--block" @tap="submitNightAction">提交夜间行动</view>
        </view>

        <view v-if="snapshot.canVote" class="nt-panel">
          <view class="nt-panel__head">
            <text class="nt-h2">放逐投票</text>
            <text class="nt-meta">点一个座位</text>
          </view>
          <view class="jr-pick">
            <view
              v-for="seat in aliveSeats"
              :key="seat.playerId"
              class="jr-pick__seat"
              :class="{ 'jr-pick__seat--on': voteTargetSeat === seat.seatNo, 'jr-pick__seat--self': seat.self }"
              @tap="pickVoteTarget(seat.seatNo)"
            >
              <text class="jr-pick__no serif">{{ seat.seatNo }}</text>
              <text class="jr-pick__nick">{{ seat.nickname }}</text>
            </view>
          </view>
          <view class="nt-btn nt-btn--gold nt-btn--block" @tap="submitVote">{{ voteTargetSeat ? `投给 ${voteTargetSeat} 号` : '先点选要投的座位' }}</view>
        </view>
      </view>

      <!-- 按住翻牌、松手盖牌：身边有人时不会一直亮着 -->
      <view class="nt-bar jr-player-bar">
        <view
          class="nt-btn nt-btn--cream nt-btn--block jr-hold"
          @touchstart="cardRevealed = true"
          @touchend="cardRevealed = false"
          @touchcancel="cardRevealed = false"
        >
          <view class="jr-eye"><view class="jr-eye__pupil" /></view>
          <text>{{ cardRevealed ? '松手盖牌' : '按住翻看身份' }}</text>
        </view>
        <text class="jr-hold__hint">身边有人时盖住，别让人看到</text>
      </view>
    </block>

    <!-- ================= 对局结束（D9） ================= -->
    <block v-else-if="view === 'result'">
      <NavBar back overlay title="对局结束" />
      <view class="jr-result-hero" :style="{ height: resultHeroHeight }">
        <image class="jr-result-hero__bg" :src="board?.backgroundImage || '/static/scenes/square.webp'" mode="aspectFill" webp />
        <view class="jr-result-hero__fan" :style="{ top: `${insets.top}px` }">
          <view
            v-for="(art, index) in winnerArt"
            :key="art.roleId"
            class="nt-card jr-result-hero__card"
            :class="`jr-fan-${winnerArt.length}-${index}`"
          >
            <view class="nt-card__frame" :style="{ backgroundColor: art.accent }">
              <image class="nt-card__img" :src="art.image" mode="aspectFill" webp />
            </view>
          </view>
        </view>
      </view>
      <view class="nt-gutter">
        <view class="jr-banner">
          <view class="jr-banner__copy">
            <text class="jr-banner__eyebrow">第 {{ snapshot.currentDay }} 天 · {{ snapshot.latestAnnouncement || snapshot.boardName }}</text>
            <text class="jr-banner__title serif">{{ resultTitle }}</text>
          </view>
          <text class="jr-banner__crown">♛</text>
        </view>
        <text v-if="!snapshot.winnerCamp && snapshot.winnerSuggestion" class="nt-meta jr-suggest">{{ snapshot.winnerSuggestion }}</text>

        <view class="nt-panel nt-panel--tight jr-results">
          <view class="nt-panel__head jr-results__head">
            <text class="nt-h2">全场身份</text>
            <text class="nt-meta">{{ resultAliveText }}</text>
          </view>
          <view v-for="item in resultRows" :key="item.playerId" class="jr-result" :class="{ 'jr-result--dead': !item.alive }">
            <text class="jr-result__no serif" :class="{ 'c-faint': !item.alive }">{{ item.seatNo }}</text>
            <view class="nt-token jr-result__token" :class="{ 'nt-token--dead': !item.alive }">
              <image v-if="item.role?.portrait" class="nt-token__img" :src="item.role.portrait" mode="aspectFill" :style="{ borderColor: item.role.accent }" webp />
              <view v-else class="nt-token__text jr-text--sm" :style="{ borderColor: item.role?.accent }">{{ (item.roleName || '?').slice(0, 1) }}</view>
            </view>
            <view class="jr-result__text">
              <view class="jr-result__name">
                <text>{{ item.nickname }}</text>
                <text class="jr-result__role" :style="{ color: item.alive ? item.role?.campColor : '' }">{{ item.roleName || '未发身份' }}</text>
              </view>
              <text v-if="item.note" class="jr-result__note">{{ item.note }}</text>
            </view>
            <text class="jr-result__state" :class="{ 'jr-result__state--alive': item.alive }">{{ item.alive ? '存活' : '出局' }}</text>
          </view>
          <text v-if="seatedPlayers.length > 8" class="jr-more" @tap="showAllResults = !showAllResults">
            {{ showAllResults ? '收起' : `展开其余 ${seatedPlayers.length - 8} 人` }}
          </text>
        </view>
        <view class="jr-finish" @tap="openCommunity">去酒馆聊聊这一局 ›</view>
      </view>

      <view class="nt-bar">
        <view class="nt-btn nt-btn--ghost jr-half" @tap="openNote">记一局笔记</view>
        <view class="nt-btn nt-btn--gold jr-half" @tap="playAgain">同一桌再来</view>
      </view>
    </block>

    <!-- ================= 旁观 / 加载 ================= -->
    <block v-else>
      <NavBar back title="法官房间" />
      <view class="nt-gutter">
        <view v-if="view === 'loading'" class="nt-empty">正在进入房间…</view>
        <view v-else class="nt-panel">
          <text class="nt-h2">{{ snapshot.boardName }}</text>
          <text class="nt-meta">对局已经开始，你没有在座位上。可以等这一局结束，或者让法官再开一局。</text>
          <view class="nt-btn nt-btn--ghost nt-btn--block" @tap="askAi">问规则</view>
        </view>
      </view>
    </block>
  </view>
</template>

<style scoped lang="scss">
.jr-warn {
  margin-top: 16rpx;
  padding: 16rpx 24rpx;
  border-radius: 20rpx;
  background: #3a2e14;
  color: var(--gold);
  font-size: 26rpx;
}

/* ---------- 等待入座 ---------- */
.jr-room {
  margin-top: 16rpx;
  overflow: hidden;
  border-radius: 40rpx;
}

.jr-room__banner {
  position: relative;
  height: 240rpx;
  border-bottom: 4rpx solid var(--ink);
  overflow: hidden;
  background: #1b1724;
}

.jr-room__bg {
  width: 100%;
  height: 100%;
}

.jr-room__night {
  position: absolute;
  right: -40rpx;
  top: -60rpx;
  width: 260rpx;
  height: 260rpx;
  border-radius: 50%;
  background: #2e2740;
}

.jr-room__body {
  padding: 32rpx;
  display: flex;
  flex-direction: column;
  gap: 28rpx;
}

.jr-room__code-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.jr-room__code {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.jr-room__digits {
  color: var(--gold);
  font-size: 80rpx;
  font-weight: 900;
  letter-spacing: 12rpx;
  line-height: 1.1;
}

.jr-room__board {
  display: flex;
  align-items: center;
  gap: 24rpx;
  padding-top: 24rpx;
  border-top: 2rpx solid var(--panel-line);
}

.jr-room__board-text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.jr-room__board-name {
  font-size: 30rpx;
  font-weight: 700;
}

.jr-stack {
  display: flex;
  padding-left: 4rpx;
  flex-shrink: 0;
}

.jr-stack__token {
  position: relative;
  width: 64rpx;
  height: 64rpx;
}

.jr-text--sm {
  font-size: 26rpx;
}

.jr-text--md {
  font-size: 30rpx;
}

.jr-share {
  margin: 0;
  line-height: normal;
}

.jr-share::after {
  border: none;
}

.jr-section {
  margin: 44rpx 0 20rpx;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 20rpx;
}

.jr-seats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 20rpx;
}

.jr-seat {
  position: relative;
  min-height: 224rpx;
  padding: 20rpx 12rpx;
  border-radius: 28rpx;
  background: var(--panel);
  border: 4rpx solid var(--ink);
  box-shadow: 6rpx 6rpx 0 var(--ink);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10rpx;
  box-sizing: border-box;
}

.jr-seat--self {
  border-color: var(--moon);
}

.jr-seat--empty {
  background: transparent;
  border: 3rpx dashed #4a4060;
  box-shadow: none;
}

.jr-seat__no {
  position: absolute;
  z-index: 2;
  left: 14rpx;
  top: 10rpx;
  color: var(--gold);
  font-size: 26rpx;
  font-weight: 900;
}

.jr-seat--empty .jr-seat__no {
  position: static;
  color: #6b6280;
  font-size: 32rpx;
}

.jr-back {
  position: relative;
  width: 80rpx;
  height: 104rpx;
  border-radius: 14rpx;
  background: var(--panel);
  border: 4rpx solid var(--ink);
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
}

.jr-back__line {
  position: absolute;
  left: 8rpx;
  top: 8rpx;
  right: 8rpx;
  bottom: 8rpx;
  border: 2rpx solid var(--gold);
  border-radius: 8rpx;
  opacity: 0.55;
}

.jr-back__moon {
  color: var(--gold);
  font-size: 30rpx;
}

.jr-seat__name {
  max-width: 100%;
  font-size: 26rpx;
  font-weight: 700;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.jr-seat__state {
  color: var(--text-3);
  font-size: 22rpx;
}

.jr-seat__state--ready {
  color: var(--camp-villager);
  font-weight: 700;
}

.jr-seat__empty {
  color: var(--text-3);
  font-size: 24rpx;
}

.jr-room__board-meta {
  color: var(--text-2);
  font-size: 24rpx;
  white-space: nowrap;
}

.jr-tip {
  margin-top: 36rpx;
  padding: 28rpx;
  border-radius: 28rpx;
  background: var(--panel-raised);
  display: flex;
  gap: 20rpx;
}

.jr-tip__icon {
  color: var(--moon);
  font-size: 32rpx;
}

.jr-tip__text {
  flex: 1;
  color: var(--text-soft);
  font-size: 26rpx;
  line-height: 1.7;
}

.jr-cta {
  flex: 1;
}

.jr-half {
  flex: 1;
}

/* ---------- 主持台 ---------- */
.jr-navtitle {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.jr-navtitle__main {
  font-size: 34rpx;
  font-weight: 900;
}

.jr-navtitle__sub {
  color: var(--text-2);
  font-size: 22rpx;
}

.jr-hostsky {
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  overflow: hidden;
  opacity: 0.35;
  z-index: 0;
}

.jr-hostsky__bg {
  width: 100%;
  height: 100%;
}

.jr-host {
  position: relative;
  z-index: 1;
}

/* 语音按钮：纯 CSS 画的小喇叭；关掉自动播报或环境不支持时变暗 */
.jr-speaker {
  position: relative;
  width: 72rpx;
  height: 72rpx;
  border-radius: 24rpx;
  border: 4rpx solid var(--ink);
  background: var(--panel);
  box-shadow: 4rpx 4rpx 0 var(--ink);
  box-sizing: border-box;
}

.jr-speaker__box {
  position: absolute;
  left: 16rpx;
  top: 26rpx;
  width: 10rpx;
  height: 14rpx;
  background: var(--text);
  border-radius: 2rpx;
}

.jr-speaker__cone {
  position: absolute;
  left: 22rpx;
  top: 17rpx;
  width: 0;
  height: 0;
  border-top: 16rpx solid transparent;
  border-bottom: 16rpx solid transparent;
  border-right: 16rpx solid var(--text);
}

.jr-speaker__wave {
  position: absolute;
  left: 34rpx;
  top: 20rpx;
  width: 16rpx;
  height: 26rpx;
  border: 4rpx solid var(--text);
  border-left: none;
  border-top-color: transparent;
  border-bottom-color: transparent;
  border-radius: 0 50% 50% 0;
}

.jr-speaker--on .jr-speaker__box,
.jr-speaker--on .jr-speaker__wave {
  background-color: var(--gold);
  border-right-color: var(--gold);
}

.jr-speaker--on .jr-speaker__cone {
  border-right-color: var(--gold);
}

.jr-speaker--on .jr-speaker__wave {
  background: transparent;
}

.jr-speaker--off {
  opacity: 0.5;
}

.jr-voice__line {
  display: block;
  margin-top: 12rpx;
  color: var(--gold);
  font-size: 26rpx;
}

.jr-progress {
  position: relative;
  margin-top: 32rpx;
  padding: 28rpx 16rpx 22rpx;
  border-radius: 36rpx;
  background: rgba(33, 28, 43, 0.9);
  border: 4rpx solid var(--ink);
  display: flex;
  justify-content: space-around;
}

.jr-progress__line {
  position: absolute;
  left: 92rpx;
  right: 92rpx;
  top: 68rpx;
  height: 4rpx;
  background: var(--line);
}

.jr-progress__item {
  position: relative;
  width: 120rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14rpx;
}

.jr-progress__token {
  position: relative;
  width: 80rpx;
  height: 80rpx;
}

.jr-progress__img {
  width: 80rpx;
  height: 80rpx;
}

.jr-progress__item--next .jr-progress__img {
  opacity: 0.45;
}

.jr-progress__ring {
  position: absolute;
  left: -12rpx;
  top: -12rpx;
  right: -12rpx;
  bottom: -12rpx;
  border-radius: 50%;
  border: 5rpx solid var(--moon);
}

.jr-progress__tick {
  position: absolute;
  right: -12rpx;
  top: -12rpx;
  width: 40rpx;
  height: 40rpx;
  border-radius: 50%;
  background: var(--camp-villager);
  border: 4rpx solid var(--ink);
  color: var(--ink);
  font-size: 22rpx;
  font-weight: 900;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
}

.jr-progress__name {
  font-size: 24rpx;
  font-weight: 700;
  color: var(--text-soft);
}

.jr-progress__item--now .jr-progress__name {
  color: var(--moon);
}

.jr-progress__item--next .jr-progress__name {
  color: var(--text-3);
}

.jr-step {
  margin-top: 32rpx;
  overflow: hidden;
  border-radius: 40rpx;
  background: #3a2e14;
}

.jr-step__top {
  display: flex;
  align-items: flex-end;
  gap: 28rpx;
  padding: 32rpx 32rpx 0;
}

.jr-step__card {
  width: 208rpx;
  flex-shrink: 0;
  transform: rotate(-3deg);
  margin-bottom: -12rpx;
}

.jr-step__copy {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 10rpx;
  padding-bottom: 20rpx;
}

.jr-step__eyebrow {
  color: var(--gold);
  font-size: 24rpx;
  font-weight: 700;
  letter-spacing: 2rpx;
}

.jr-step__title {
  font-size: 50rpx;
  font-weight: 900;
  line-height: 1.25;
}

.jr-step__holders {
  color: #e8d9b8;
  font-size: 26rpx;
  line-height: 1.5;
}

.jr-step__body {
  margin-top: 28rpx;
  padding: 32rpx;
  background: var(--panel);
  display: flex;
  flex-direction: column;
  gap: 22rpx;
}

.jr-step__body--flush {
  margin-top: 0;
}

.jr-step__script {
  padding: 24rpx 28rpx;
  border-radius: 24rpx;
  background: var(--panel-raised);
  font-size: 32rpx;
  line-height: 1.7;
}

.jr-step__hl {
  color: #ff7468;
  font-weight: 900;
}

.jr-step__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}

.jr-pill {
  padding: 8rpx 18rpx;
  border-radius: 16rpx;
  background: var(--panel-raised);
  color: var(--text-2);
  font-size: 24rpx;
}

.jr-pill--save {
  background: #1d3324;
  color: #a9d59a;
  font-weight: 700;
}

.jr-pill--wolf {
  background: #3a1e22;
  color: #f2a49a;
  font-weight: 700;
}

.jr-pill--done {
  background: #1b3436;
  color: var(--moon);
  font-weight: 700;
}

.jr-step__btns {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16rpx;
}

.jr-step__btn {
  padding: 0 12rpx;
  font-size: 30rpx;
}

.jr-step__btn--save {
  color: #a9d59a;
}

.jr-step__btn--wolf {
  color: #f2a49a;
}

.jr-step__btn--moon {
  color: var(--moon);
}

.jr-step__btn--on {
  border-color: var(--moon);
  background: #1b3436;
}

.jr-step__hint {
  color: var(--moon);
  font-size: 24rpx;
}

.jr-step__picked {
  color: var(--gold);
  font-size: 30rpx;
  font-weight: 700;
}

.jr-announce {
  min-height: 160rpx;
}

.jr-row {
  display: flex;
  gap: 16rpx;
}

.jr-tally {
  display: flex;
  flex-direction: column;
  gap: 14rpx;
}

.jr-tally__row {
  display: flex;
  align-items: center;
  gap: 16rpx;
}

.jr-tally__seat {
  width: 90rpx;
  font-size: 28rpx;
  font-weight: 900;
}

.jr-tally__bar {
  flex: 1;
  height: 16rpx;
  border-radius: 8rpx;
  background: var(--panel-raised);
  overflow: hidden;
}

.jr-tally__fill {
  height: 100%;
  background: var(--camp-wolf);
}

.jr-tally__count {
  width: 80rpx;
  text-align: right;
  color: var(--text-2);
  font-size: 26rpx;
}

.jr-host-seats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16rpx;
}

.jr-hseat {
  position: relative;
  padding: 20rpx 8rpx 16rpx;
  border-radius: 28rpx;
  background: var(--panel);
  border: 3rpx solid var(--panel-line);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8rpx;
  box-sizing: border-box;
}

.jr-hseat--dead {
  background: #1a1622;
}

.jr-hseat--picked {
  border-color: var(--camp-wolf);
}

.jr-hseat__no {
  position: absolute;
  left: 14rpx;
  top: 8rpx;
  color: var(--gold);
  font-size: 24rpx;
  font-weight: 900;
}

.jr-hseat--dead .jr-hseat__no {
  color: #6b6280;
}

.jr-hseat__flag {
  position: absolute;
  right: 8rpx;
  top: 8rpx;
  padding: 2rpx 10rpx;
  border-radius: 10rpx;
  font-size: 20rpx;
  font-weight: 700;
}

.jr-hseat__flag--gold {
  background: #3a2e14;
  color: var(--gold);
}

.jr-hseat__flag--wolf,
.jr-hseat__flag--dead {
  background: #3a1e22;
  color: #f2a49a;
}

.jr-hseat__token {
  width: 76rpx;
  height: 76rpx;
  margin-top: 16rpx;
}

.jr-hseat__role {
  font-size: 24rpx;
  color: var(--text-soft);
}

.jr-hseat--dead .jr-hseat__role {
  color: #7e7494;
}

.jr-hseat__flag--save {
  background: #1d3324;
  color: #a9d59a;
}

.jr-hseat__votes {
  color: var(--camp-wolf);
  font-size: 22rpx;
  font-weight: 700;
}

.jr-log {
  margin-top: 32rpx;
  gap: 14rpx;
}

.jr-log__title {
  color: var(--text-2);
  font-size: 26rpx;
  font-weight: 700;
}

.jr-log__row {
  display: flex;
  align-items: center;
  gap: 20rpx;
}

.jr-log__token {
  width: 52rpx;
  height: 52rpx;
  flex-shrink: 0;
}

.jr-log__text {
  flex: 1;
  min-width: 0;
  font-size: 28rpx;
  line-height: 1.5;
}

.jr-log__time {
  flex-shrink: 0;
  color: var(--text-3);
  font-size: 24rpx;
}

.jr-finish {
  margin: 32rpx 0 8rpx;
  text-align: center;
  color: var(--text-3);
  font-size: 26rpx;
  text-decoration: underline;
}

/* ---------- 玩家 ---------- */
.jr-sky {
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  height: 1380rpx;
  z-index: 0;
  overflow: hidden;
}

.jr-sky__bg {
  width: 100%;
  height: 100%;
}

.jr-player {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  gap: 32rpx;
  padding-top: 24rpx;
}

.jr-out {
  padding: 20rpx 24rpx;
  border-radius: 20rpx;
  background: #3a1e22;
  color: #f2a49a;
  font-size: 26rpx;
}

.jr-reveal {
  align-self: center;
  width: 524rpx;
  transform: rotate(-2deg);
  margin: 12rpx 0 8rpx;
}

.jr-reveal__card {
  border-width: 6rpx;
  border-radius: 32rpx;
  box-shadow: 12rpx 12rpx 0 var(--ink);
}

.jr-reveal__moon {
  font-size: 120rpx;
}

.jr-reveal__glyph {
  color: var(--ink);
  font-size: 160rpx;
  font-weight: 900;
  opacity: 0.6;
}

.jr-reveal__plate {
  padding: 24rpx 28rpx;
  background: var(--ink);
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}

.jr-reveal__plate--back {
  align-items: center;
}

.jr-reveal__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}

.jr-reveal__name {
  font-size: 56rpx;
  font-weight: 900;
}

.jr-reveal__camp {
  display: flex;
  align-items: center;
  gap: 10rpx;
  font-size: 26rpx;
  font-weight: 700;
}

.jr-reveal__hint {
  color: var(--text-2);
  font-size: 28rpx;
  font-weight: 700;
}

.jr-skill {
  gap: 12rpx;
}

.jr-skill__text {
  font-size: 34rpx;
  font-weight: 900;
  line-height: 1.6;
}

.jr-skill__note {
  color: var(--text-soft);
  font-size: 28rpx;
  line-height: 1.7;
}

.jr-announce-card {
  gap: 12rpx;
}

.jr-presets {
  display: flex;
  flex-wrap: wrap;
  gap: 14rpx;
}

.jr-presets .nt-chip {
  height: 60rpx;
  font-size: 26rpx;
}

.jr-pick {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16rpx;
}

.jr-pick__seat {
  padding: 18rpx 8rpx;
  border-radius: 24rpx;
  background: var(--panel-raised);
  border: 4rpx solid transparent;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6rpx;
  box-sizing: border-box;
}

.jr-pick__seat--on {
  border-color: var(--gold);
  background: #3a2e14;
}

.jr-pick__seat--self .jr-pick__nick {
  color: var(--moon);
}

.jr-pick__no {
  color: var(--gold);
  font-size: 34rpx;
  font-weight: 900;
}

.jr-pick__nick {
  max-width: 100%;
  color: var(--text-2);
  font-size: 22rpx;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.jr-note {
  height: 88rpx;
  font-size: 28rpx;
  color: var(--text);
}

/* ---------- 结果 ---------- */
.jr-result-hero {
  position: relative;
  overflow: hidden;
  background: #1b1724;
}

.jr-result-hero__bg {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
  opacity: 0.85;
}

.jr-result-hero__fan {
  position: absolute;
  left: 0;
  right: 0;
  top: 40rpx;
  bottom: 0;
}

.jr-result-hero__card {
  position: absolute;
  width: 200rpx;
}

/* 1~5 张胜利阵营立绘的扇形摆法：jr-fan-总数-序号 */
.jr-fan-1-0 { left: 275rpx; top: 20rpx; }
.jr-fan-2-0 { left: 190rpx; top: 40rpx; transform: rotate(-7deg); }
.jr-fan-2-1 { left: 360rpx; top: 40rpx; transform: rotate(7deg); }
.jr-fan-3-0 { left: 110rpx; top: 60rpx; transform: rotate(-12deg); }
.jr-fan-3-1 { left: 275rpx; top: 10rpx; z-index: 2; }
.jr-fan-3-2 { left: 440rpx; top: 60rpx; transform: rotate(12deg); }
.jr-fan-4-0 { left: 40rpx; top: 80rpx; transform: rotate(-14deg); }
.jr-fan-4-1 { left: 190rpx; top: 30rpx; transform: rotate(-5deg); z-index: 2; }
.jr-fan-4-2 { left: 360rpx; top: 30rpx; transform: rotate(5deg); z-index: 2; }
.jr-fan-4-3 { left: 510rpx; top: 80rpx; transform: rotate(14deg); }
.jr-fan-5-0 { left: 50rpx; top: 90rpx; transform: rotate(-15deg); }
.jr-fan-5-1 { left: 160rpx; top: 50rpx; transform: rotate(-7deg); z-index: 2; }
.jr-fan-5-2 { left: 275rpx; top: 10rpx; z-index: 3; }
.jr-fan-5-3 { left: 390rpx; top: 50rpx; transform: rotate(7deg); z-index: 2; }
.jr-fan-5-4 { left: 500rpx; top: 90rpx; transform: rotate(15deg); }

.jr-banner {
  position: relative;
  z-index: 4;
  margin-top: -96rpx;
  padding: 28rpx 36rpx;
  border-radius: 36rpx;
  background: var(--gold);
  color: var(--ink);
  border: 4rpx solid var(--ink);
  box-shadow: 8rpx 8rpx 0 var(--ink);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.jr-banner__copy {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
  min-width: 0;
}

.jr-banner__eyebrow {
  font-size: 26rpx;
  font-weight: 700;
}

.jr-banner__title {
  font-size: 56rpx;
  font-weight: 900;
  line-height: 1.15;
}

.jr-banner__crown {
  font-size: 80rpx;
  line-height: 1;
}

.jr-suggest {
  display: block;
  margin-top: 16rpx;
}

.jr-results {
  margin-top: 40rpx;
}

.jr-results__head {
  padding-bottom: 20rpx;
}

.jr-result {
  display: flex;
  align-items: center;
  gap: 22rpx;
  min-height: 104rpx;
  padding: 12rpx 0;
  border-top: 2rpx solid var(--panel-line);
}

.jr-result__no {
  width: 44rpx;
  color: var(--gold);
  font-size: 28rpx;
  font-weight: 900;
}

.jr-result__token {
  width: 72rpx;
  height: 72rpx;
}

.jr-result__text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2rpx;
}

.jr-result__name {
  display: flex;
  align-items: baseline;
  gap: 10rpx;
  font-size: 30rpx;
  font-weight: 700;
}

.jr-result__role {
  font-size: 26rpx;
}

.jr-result--dead .jr-result__no,
.jr-result--dead .jr-result__name,
.jr-result--dead .jr-result__role {
  color: #7e7494;
}

.jr-result__note {
  color: var(--text-3);
  font-size: 24rpx;
}

.jr-result__state {
  color: var(--text-3);
  font-size: 24rpx;
}

.jr-result__state--alive {
  color: var(--camp-villager);
}

.jr-player-bar {
  flex-direction: column;
  gap: 14rpx;
}

.jr-hold {
  gap: 14rpx;
}

.jr-hold__hint {
  color: var(--text-3);
  font-size: 24rpx;
}

/* 眼睛图标：杏仁形外框 + 瞳孔 */
.jr-eye {
  position: relative;
  width: 40rpx;
  height: 26rpx;
  border: 4rpx solid var(--ink);
  border-radius: 50% / 50%;
  box-sizing: border-box;
}

.jr-eye__pupil {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 10rpx;
  height: 10rpx;
  margin: -5rpx 0 0 -5rpx;
  border-radius: 50%;
  background: var(--ink);
}

.jr-more {
  align-self: center;
  padding-top: 20rpx;
  color: var(--moon);
  font-size: 28rpx;
  font-weight: 700;
}
</style>
