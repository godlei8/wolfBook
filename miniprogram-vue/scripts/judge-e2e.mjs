// 电子法官联调脚本：用多个 Mock 账号真打本地后端（start-backend-dev.ps1，test profile 任意 code 都能登录）跑完整局，
// 每一轮把每个人的快照交给小程序的视图逻辑（utils/judge/*），检查零场外和请求格式。只对本地跑。
// 用法：node scripts/judge-e2e.mjs [http://127.0.0.1:8088] [observer|joined|wolfking|explode|mechwolf|both]（both 五局都跑；
// 狼王守卫要先在本地库跑 deploy/sql/2026-11-02-wolf-king-mechanic.sql，机械狼通灵师要先跑 deploy/sql/2026-11-12-mech-wolf-psychic-mechanic.sql）
// explode：无法官预女猎白，第一天两只狼上警、警上一只自爆（竞选推迟），第二天另一只再爆（警徽流失）。
// mechwolf：无法官机械狼通灵师，机械狼首夜学一个人的技能，之后按学到的技能行动、狼人死光后带刀；通灵师每晚查验。
import { phaseTitle, pickView, countdown } from '../src/utils/judge/room-view.js'
import { isStepHolder, nightArt, nightPanel, seatBadges } from '../src/utils/judge/night-panel.js'
import { stageLayout } from '../src/utils/judge/stage.js'
import { lobbyAction } from '../src/utils/judge/lobby.js'
import { pendingPanel } from '../src/utils/judge/pending.js'
import { announcePanel, sheriffPanel } from '../src/utils/judge/sheriff.js'
import { hostActions, votePanel } from '../src/utils/judge/day.js'
import { voiceCue } from '../src/utils/judge/voice-cue.js'
import { roundRows, verdictText } from '../src/utils/judge/rounds.js'
import { godSeats, tonightRows } from '../src/utils/judge/judge-bar.js'
import { identityCard } from '../src/utils/judge/identity.js'

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
  // 同一个时刻算所有人的倒计时（逐个取 Date.now() 跨秒时会差 1 秒，误报不一致）
  const now = Date.now()
  const shared = (s) => JSON.stringify({
    title: phaseTitle(s),
    clock: countdown(s.phaseDeadline, now, s.paused).text,
    art: nightArt(s, null),
    seats: [...seatBadges(s, 0).entries()].map(([seat, badge]) => [seat, badge.dead, badge.disabled]),
    stage: stageLayout(s, 'night').sides.map((side) => side.seats.map((seat) => [seat.seatNo, seat.dead, seat.sheriff, seat.role])),
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

async function runGame({ mode, keyword, count, explode = false }) {
  console.log(`\n=== ${mode === 'observer' ? '有法官' : '无法官'} · ${keyword} · ${count} 人${explode ? ' · 自爆' : ''} ===`)
  let explosions = 0
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
      if (explode && explosions < 2) fail(`自爆局只爆了 ${explosions} 次`)
      if (explode && !rows.some((row) => row.skills.some((text) => text.includes('自爆')))) fail('对局记录里没有自爆')
      return
    }
    if (hostSnap.judgeViewer) {
      if (!godSeats(hostSnap).size) fail('法官看不到身份角标')
      if (view === 'night' && !tonightRows(hostSnap).length) fail('法官看不到今晚汇总')
    }
    const wolves = aliveWolfSeats(snaps)
    for (const s of snaps) {
      // 零场外：能不能自爆只在本人快照里，好人永远不是 true
      if (s.dayInfo?.canSelfDestruct && !wolves.includes(s.selfPlayer?.seatNo)) fail(`${s.selfPlayer?.seatNo} 号不是狼却能自爆`)
    }

    if (view === 'night') {
      checkNightScreens(snaps.map((s, i) => (sameStep[i] ? s : null)), judgeIndex)
      checkMechWolfSecrecy(snaps.map((s, i) => (sameStep[i] ? s : null)))
      for (let i = 0; i < everyone.length; i += 1) {
        const s = snaps[i]
        const panel = nightPanel(s)
        if (!panel || panel.done) continue
        const alive = (s.players || []).filter((p) => p.seatNo && p.alive).map((p) => p.seatNo)
        const nonWolf = alive.filter((seat) => !wolves.includes(seat))
        let button
        if (panel.kind === 'mech_wolf') {
          await mechWolfNight(everyone[i], roomId, s, alive, nonWolf)
          continue
        }
        if (panel.kind === 'wolf') button = nightPanel(s, nonWolf[0]).buttons.find((b) => b.action === 'kill')
        else if (panel.kind === 'witch') button = panel.buttons.find((b) => b.action === 'none')
        else if (panel.kind === 'seer' || panel.kind === 'psychic') button = nightPanel(s, alive.find((seat) => seat !== s.selfPlayer.seatNo)).buttons[0]
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
          const seat = snaps[i].selfPlayer.seatNo
          // 自爆局：再让两只狼和三个好人上警（第二天还要有人接着竞选）
          const goods = (hostSnap.players || []).filter((p) => p.seatNo && p.alive && !wolves.includes(p.seatNo)).map((p) => p.seatNo)
          const wanted = seat <= 2 || (explode && (wolves.slice(0, 2).includes(seat) || goods.slice(1, 4).includes(seat)))
          if (panel.canSignUp && !panel.candidate && wanted) {
            await act(everyone[i], `/api/judge/rooms/${roomId}/sheriff`, { candidate: true }, 'sheriff signup')
            signed += 1
          }
          if (panel.candidate) signed += 1
        }
        if (signed >= 2) await hostDo(host, roomId, hostSnap, 'close_sheriff_signup')
      } else if (stage === 'speech') {
        if (explode && hostSnap.currentDay > 1 && explosions === 0) fail('第二天接着竞选，但第一天没有自爆过')
        const exploder = explode && explosions < 2
          ? snaps.findIndex((s) => (hostSnap.sheriffCandidates || []).includes(s.selfPlayer?.seatNo) && identityCard(s, board)?.canSelfDestruct)
          : -1
        if (exploder >= 0) {
          const result = await act(everyone[exploder], `/api/judge/rooms/${roomId}/self-destruct`, undefined, 'self-destruct')
          explosions += 1
          const text = result.data?.latestAnnouncement || ''
          const expected = explosions === 1 ? '警长竞选推迟到明天' : '警徽流失'
          if (!text.includes(expected)) fail(`第 ${explosions} 次警上自爆公告应含「${expected}」：${text}`)
          console.log(`  ${snaps[exploder].selfPlayer.seatNo} 号警上自爆：${text}`)
        } else {
          await hostDo(host, roomId, hostSnap, 'start_sheriff_vote')
        }
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
      await hostDo(host, roomId, hostSnap, hostSnap.nightNext ? 'start_night' : 'start_speech')
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

/** 机械狼这一步：按面板的草稿规则选好要做的几件事，一次提交。 */
async function mechWolfNight(token, roomId, s, alive, nonWolf) {
  const info = s.nightInfo || {}
  const self = s.selfPlayer.seatNo
  const others = alive.filter((seat) => seat !== self)
  const draft = {}
  if (!info.learned) draft.learn = others[0]
  if (info.learned && info.learnedEffective) {
    if (info.learned === 'psychic') draft.check = others[0]
    if (info.learned === 'witch' && info.mechPoison !== false) draft.poison = nonWolf[0]
    if (info.learned === 'guard') draft.guard = others.find((seat) => seat !== info.lastGuardSeat)
  }
  if (info.armed) {
    draft.kill = nonWolf[0]
    if (info.learned === 'wolf' && info.learnedEffective && nonWolf[1]) draft.kill_pierce = nonWolf[1]
  }
  const panel = nightPanel(s, 0, draft)
  const submit = panel.buttons.find((b) => b.action === 'submit')
  if (submit && !submit.disabled) {
    await act(token, `/api/judge/rooms/${roomId}/night-action`, { actions: submit.actions }, `night mech_wolf ${submit.actions.map((a) => a.actionType).join('+')}`)
    console.log(`  机械狼：${panel.learnedText}${panel.armedText ? `，${panel.armedText}` : ''} → ${submit.actions.map((a) => `${a.actionType} ${a.targetSeatNo}`).join('、')}`)
  } else {
    await act(token, `/api/judge/rooms/${roomId}/night-action`, { actionType: 'none' }, 'night mech_wolf none')
  }
}

/** 零场外：机械狼的信息只在他本人快照里；机械狼那一步所有人看到同一个截止时间。 */
function checkMechWolfSecrecy(snaps) {
  for (const s of snaps) {
    if (!s || s.selfPlayer?.mechanic === 'mech_wolf') continue
    const info = s.nightInfo || {}
    if (info.armed != null || info.learned != null || info.learnedRole != null) fail(`${s.selfPlayer?.seatNo ?? '法官'} 看到了机械狼的信息`)
  }
  const deadlines = new Set(snaps.filter((s) => s?.currentStep?.kind === 'mech_wolf').map((s) => s.currentStep.deadline))
  if (deadlines.size > 1) fail(`机械狼那一步截止时间不一致：${[...deadlines].join(' / ')}`)
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
if (MODE === 'both' || MODE === 'observer') games.push({ mode: 'observer', keyword: '守卫九人局', count: 9 })
if (MODE === 'both' || MODE === 'joined') games.push({ mode: 'joined', keyword: '预女猎白', count: 12 })
if (MODE === 'both' || MODE === 'wolfking') games.push({ mode: 'joined', keyword: '狼王守卫', count: 12 })
if (MODE === 'both' || MODE === 'explode') games.push({ mode: 'joined', keyword: '预女猎白', count: 12, explode: true })
if (MODE === 'both' || MODE === 'mechwolf') games.push({ mode: 'joined', keyword: '机械狼通灵师', count: 12 })
for (const game of games) await runGame(game)
console.log(`\n走过的视图：${[...seen].join(' ')}；房主播报台词出现 ${narrations} 次`)
console.log(problems.length ? `\n${problems.length} 个问题` : '\n没有发现问题')
process.exit(problems.length ? 1 : 0)
