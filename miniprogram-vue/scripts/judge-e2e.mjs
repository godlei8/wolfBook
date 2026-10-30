// 电子法官联调脚本：用多个 Mock 账号真打本地后端（start-backend-dev.ps1，test profile 任意 code 都能登录）跑完整局，
// 每一轮把每个人的快照交给小程序的视图逻辑（utils/judge/*），检查零场外和请求格式。只对本地跑。
// 用法：node scripts/judge-e2e.mjs [http://127.0.0.1:8088] [observer|joined|both]
import { phaseTitle, pickView, countdown } from '../src/utils/judge/room-view.js'
import { isStepHolder, nightArt, nightPanel, panelHeight, seatBadges } from '../src/utils/judge/night-panel.js'
import { lobbyAction } from '../src/utils/judge/lobby.js'
import { pendingPanel } from '../src/utils/judge/pending.js'
import { announcePanel, sheriffPanel } from '../src/utils/judge/sheriff.js'
import { hostActions, votePanel } from '../src/utils/judge/day.js'
import { voiceCue } from '../src/utils/judge/voice-cue.js'
import { roundRows, verdictText } from '../src/utils/judge/rounds.js'
import { godSeats, tonightRows } from '../src/utils/judge/judge-bar.js'

const BASE = process.argv[2] || 'http://127.0.0.1:8088'
const MODE = process.argv[3] || 'both'
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
const problems = []
const seen = new Set()
let narrations = 0

function fail(message) {
  problems.push(message)
  console.log(`  ✖ ${message}`)
}

async function call(token, path, method = 'GET', body) {
  const response = await fetch(`${BASE}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  return response.json()
}

async function act(token, path, body, label) {
  const result = await call(token, path, 'POST', body)
  if (result.code !== 0 && result.code !== 4002) fail(`${label} → ${result.code} ${result.msg} ${JSON.stringify(body)}`)
  return result
}

async function login(code) {
  const result = await call(null, '/api/login', 'POST', { code })
  if (result.code !== 0) throw new Error(`login ${code}: ${result.msg}`)
  return result.data.token
}

async function findBoard(keyword) {
  const list = await call(null, '/api/boards?page=1&size=50')
  const card = (list.data.list || []).find((board) => board.name.includes(keyword))
  if (!card) throw new Error(`没有找到「${keyword}」板子`)
  return (await call(null, `/api/boards/${card.id}`)).data
}

function checkNightScreens(snaps, judgeIndex) {
  const players = snaps.filter((s, i) => s && i !== judgeIndex)
  const shared = (s) => JSON.stringify({
    title: phaseTitle(s),
    clock: countdown(s.phaseDeadline, Date.now(), s.paused).text,
    art: nightArt(s, null),
    height: panelHeight(s.currentStep?.kind),
    seats: [...seatBadges(s, 0).entries()].map(([seat, badge]) => [seat, badge.dead, badge.disabled]),
  })
  const first = shared(players[0])
  for (const s of players) {
    if (shared(s) !== first) fail(`夜里画面不一致：${shared(s)} vs ${first}`)
    const holder = isStepHolder(s)
    if (holder !== Boolean(nightPanel(s))) fail('持有者和面板对不上')
    if (!s.selfPlayer?.alive && nightPanel(s)) fail('死人夜里有面板')
  }
}

function aliveWolfSeats(snaps) {
  return snaps
    .map((s) => s.selfPlayer)
    .filter((p) => p?.seatNo && p.alive && p.faction === '狼人')
    .map((p) => p.seatNo)
}

async function runGame({ mode, keyword, count }) {
  console.log(`\n=== ${mode === 'observer' ? '有法官' : '无法官'} · ${keyword} · ${count} 人 ===`)
  const run = Date.now().toString(36)
  const board = await findBoard(keyword)
  const host = await login(`e2e-${run}-host`)
  const created = await call(host, '/api/judge/rooms', 'POST', {
    boardId: board.id, judgeMode: mode, autoJudge: true,
    rules: { sheriffEnabled: true, speechSeconds: 30, sheriffSpeechSeconds: 30, wolfSeconds: 30, roleSeconds: 15 },
  })
  if (created.code !== 0) throw new Error(`建房失败 ${created.msg}`)
  const roomId = created.data.roomId
  console.log(`房号 ${created.data.roomCode}，autoJudge=${created.data.autoJudge}`)
  if (created.data.autoJudge !== true) fail('建房后快照 autoJudge 不是 true')
  const tokens = mode === 'joined' ? [host] : []
  const seatCount = mode === 'joined' ? count - 1 : count
  for (let i = 1; i <= seatCount; i += 1) {
    const token = await login(`e2e-${run}-p${i}`)
    await act(token, `/api/judge/rooms/${roomId}/join`, undefined, 'join')
    tokens.push(token)
  }
  const everyone = mode === 'joined' ? tokens : [host, ...tokens]
  const judgeIndex = mode === 'joined' ? -1 : 0
  for (const token of tokens) {
    const snap = (await call(token, `/api/judge/rooms/${roomId}`)).data
    const action = lobbyAction(snap)
    if (action.kind !== 'ready') fail(`大厅主按钮应该是准备，实际 ${action.kind}`)
    await act(token, `/api/judge/rooms/${roomId}/ready`, undefined, 'ready')
  }
  const hostLobby = (await call(host, `/api/judge/rooms/${roomId}`)).data
  const start = lobbyAction(hostLobby)
  if (start.kind !== 'start' || start.disabled) fail(`全员准备后房主应能开局：${JSON.stringify(start)}`)
  await act(host, `/api/judge/rooms/${roomId}/start`, undefined, 'start')

  const deadline = Date.now() + 15 * 60 * 1000
  let lastPhase = ''
  while (Date.now() < deadline) {
    const snaps = []
    for (const token of everyone) snaps.push((await call(token, `/api/judge/rooms/${roomId}`)).data)
    const hostSnap = snaps[everyone.indexOf(host)]
    const view = pickView(hostSnap)
    const phaseKey = `${hostSnap.currentDay}-${hostSnap.currentPhase}-${hostSnap.currentStep?.index ?? ''}`
    if (phaseKey !== lastPhase) {
      console.log(`· ${phaseTitle(hostSnap)}（${view}）`)
      lastPhase = phaseKey
    }
    seen.add(view)
    // 逐个拉快照时可能正好跨过换步骤，只比较和房主同一步的快照
    const stepOf = (s) => `${s.roomStatus}-${s.currentDay}-${s.currentPhase}-${s.currentStep?.index ?? ''}`
    const sameStep = snaps.map((s) => stepOf(s) === stepOf(hostSnap))
    for (const [i, s] of snaps.entries()) {
      if (sameStep[i] && pickView(s) !== view) fail(`视图不一致：${pickView(s)} vs ${view}`)
      if (!s.host && voiceCue(s)) fail('非房主拿到了播报台词')
    }
    if (voiceCue(hostSnap)) narrations += 1
    if (view === 'result') {
      const verdict = verdictText(hostSnap)
      const rows = roundRows(hostSnap)
      console.log(`结束：${verdict.title} · ${verdict.reason}；记录 ${rows.length} 天，第 1 天夜里 ${rows[0]?.nightActions.length || 0} 条行动`)
      if (!rows.length) fail('结束后没有对局记录')
      if (!rows.some((row) => row.nightActions.length)) fail('结束后对局记录没有夜间行动')
      return
    }
    if (hostSnap.judgeViewer) {
      if (!godSeats(hostSnap).size) fail('法官看不到身份角标')
      if (view === 'night' && !tonightRows(hostSnap).length) fail('法官看不到今晚汇总')
    }
    const wolves = aliveWolfSeats(snaps)

    if (view === 'night') {
      checkNightScreens(snaps.map((s, i) => (sameStep[i] ? s : null)), judgeIndex)
      for (let i = 0; i < everyone.length; i += 1) {
        const s = snaps[i]
        const panel = nightPanel(s)
        if (!panel || panel.done) continue
        const alive = (s.players || []).filter((p) => p.seatNo && p.alive).map((p) => p.seatNo)
        const nonWolf = alive.filter((seat) => !wolves.includes(seat))
        let button
        if (panel.kind === 'wolf') button = nightPanel(s, nonWolf[0]).buttons.find((b) => b.action === 'kill')
        else if (panel.kind === 'witch') button = panel.buttons.find((b) => b.action === 'none')
        else if (panel.kind === 'seer') button = nightPanel(s, alive.find((seat) => seat !== s.selfPlayer.seatNo)).buttons[0]
        else if (panel.kind === 'guard') {
          const pick = alive.find((seat) => seat !== s.nightInfo?.lastGuardSeat)
          button = nightPanel(s, pick).buttons.find((b) => !b.disabled)
        } else button = panel.buttons[0]
        if (panel.kind === 'wolf' && s.nightInfo?.wolfPicks?.find((p) => p.seatNo === s.selfPlayer.seatNo)?.decided) continue
        if (button && !button.disabled) {
          await act(everyone[i], `/api/judge/rooms/${roomId}/night-action`, { actionType: button.action, targetSeatNo: button.target }, `night ${panel.kind}`)
        }
      }
    } else if (view === 'pending') {
      for (let i = 0; i < everyone.length; i += 1) {
        const panel = pendingPanel(snaps[i], 0)
        if (panel.kind === 'waiting') continue
        const chosen = panel.needsTarget ? pendingPanel(snaps[i], panel.targets[0]).buttons[0] : panel.buttons[0]
        await act(everyone[i], `/api/judge/rooms/${roomId}/skill`, { decision: chosen.decision, targetSeatNo: chosen.target }, `skill ${panel.kind}`)
      }
    } else if (view === 'sheriff') {
      const stage = sheriffPanel(hostSnap).stage
      if (stage === 'signup') {
        let signed = 0
        for (let i = 0; i < everyone.length; i += 1) {
          const panel = sheriffPanel(snaps[i])
          if (panel.canSignUp && !panel.candidate && signed < 2 && snaps[i].selfPlayer.seatNo <= 2) {
            await act(everyone[i], `/api/judge/rooms/${roomId}/sheriff`, { candidate: true }, 'sheriff signup')
            signed += 1
          }
          if (panel.candidate) signed += 1
        }
        if (signed >= 2) await hostDo(host, roomId, hostSnap, 'close_sheriff_signup')
      } else if (stage === 'speech') {
        await hostDo(host, roomId, hostSnap, 'start_sheriff_vote')
      } else {
        for (let i = 0; i < everyone.length; i += 1) {
          const panel = sheriffPanel(snaps[i])
          if (panel.canVote && !panel.voted) {
            await act(everyone[i], `/api/judge/rooms/${roomId}/sheriff`, { targetSeatNo: panel.targets[0] }, 'sheriff vote')
          }
        }
      }
    } else if (view === 'announce') {
      for (let i = 0; i < everyone.length; i += 1) {
        const panel = announcePanel(snaps[i])
        if (panel.canChooseOrder) await act(everyone[i], `/api/judge/rooms/${roomId}/sheriff`, { speechOrder: panel.orderOptions[1].value }, 'speech order')
      }
      await hostDo(host, roomId, hostSnap, 'start_speech')
    } else if (view === 'speech') {
      await hostDo(host, roomId, hostSnap, 'start_vote')
    } else if (view === 'vote') {
      for (let i = 0; i < everyone.length; i += 1) {
        const panel = votePanel(snaps[i])
        if (!panel.canVote || panel.voted) continue
        const target = panel.targets.find((seat) => wolves.includes(seat)) ?? panel.targets[0]
        await act(everyone[i], `/api/judge/rooms/${roomId}/vote`, { targetSeatNo: target }, 'vote')
      }
    } else if (view === 'voteResult') {
      await hostDo(host, roomId, hostSnap, 'continue')
    }
    await sleep(1500)
  }
  fail('15 分钟内没跑完一局')
}

async function hostDo(host, roomId, hostSnap, action) {
  const item = hostActions(hostSnap).find((entry) => entry.action === action)
  if (!item) {
    fail(`房主操作栏里没有 ${action}（阶段 ${hostSnap.currentPhase}）`)
    return
  }
  await act(host, `/api/judge/rooms/${roomId}/advance`, item.payload, `advance ${action}`)
}

const games = []
if (MODE !== 'joined') games.push({ mode: 'observer', keyword: '守卫', count: 9 })
if (MODE !== 'observer') games.push({ mode: 'joined', keyword: '预女猎白', count: 12 })
for (const game of games) await runGame(game)
console.log(`\n走过的视图：${[...seen].join(' ')}；房主播报台词出现 ${narrations} 次`)
console.log(problems.length ? `\n${problems.length} 个问题` : '\n没有发现问题')
process.exit(problems.length ? 1 : 0)
