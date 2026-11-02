// 陪玩机器人：在浏览器（H5）里手动测电子法官房间时，用 Mock 账号把其余座位坐满，并且只替自己的座位行动
// （夜间行动、上警报名和投票、警长定发言顺序、放逐投票、出局技能），房主 / 法官的推进按钮留给浏览器里的人点。只对本地后端用。
// 用法：node scripts/judge-bots.mjs <房号或 roomId> [机器人数量] [http://127.0.0.1:8088]
import { nightPanel } from '../src/utils/judge/night-panel.js'
import { pendingPanel } from '../src/utils/judge/pending.js'
import { announcePanel, sheriffPanel } from '../src/utils/judge/sheriff.js'
import { votePanel } from '../src/utils/judge/day.js'
import { pickView } from '../src/utils/judge/room-view.js'

const ROOM = process.argv[2]
const COUNT = Number(process.argv[3] || 0)
const BASE = process.argv[4] || 'http://127.0.0.1:8088'
if (!ROOM) {
  console.log('用法：node scripts/judge-bots.mjs <房号> [机器人数量] [后端地址]')
  process.exit(1)
}
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

async function call(token, path, method = 'GET', body) {
  const response = await fetch(`${BASE}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  return response.json()
}

async function act(bot, path, body, label) {
  const result = await call(bot.token, `/api/judge/rooms/${ROOM}${path}`, 'POST', body)
  const text = result.code === 0 ? 'ok' : `${result.code} ${result.msg}`
  console.log(`  ${bot.name}${bot.seat ? `（${bot.seat} 号）` : ''} ${label} ${body ? JSON.stringify(body) : ''} → ${text}`)
  return result
}

const run = Date.now().toString(36)
const bots = []
const first = await call(null, `/api/login`, 'POST', { code: `bot-${run}-0` })
const peek = (await call(first.data.token, `/api/judge/rooms/${ROOM}`)).data
const free = (peek?.playerCount || 0) - (peek?.joinedCount || 0)
const count = COUNT || free
console.log(`房间 ${peek?.roomCode}（${peek?.boardName}），空位 ${free}，派 ${count} 个机器人`)
for (let i = 0; i < count; i += 1) {
  const login = i === 0 ? first : await call(null, `/api/login`, 'POST', { code: `bot-${run}-${i}` })
  bots.push({ name: `机器人${i + 1}`, token: login.data.token, seat: null })
}

let lastView = ''
while (true) {
  for (const bot of bots) {
    const result = await call(bot.token, `/api/judge/rooms/${ROOM}`)
    if (result.code !== 0) continue
    const s = result.data
    bot.seat = s.selfPlayer?.seatNo || null
    const view = pickView(s)
    if (view === 'result') {
      console.log('对局结束，机器人退出')
      process.exit(0)
    }
    if (view === 'lobby') {
      if (s.canJoin) await act(bot, '/join', undefined, '入座')
      else if (s.selfPlayer && !s.selfPlayer.ready) await act(bot, '/ready', undefined, '准备')
      continue
    }
    if (bot === bots[0] && view !== lastView) {
      console.log(`· ${view}`)
      lastView = view
    }
    if (s.paused) continue
    const alive = (s.players || []).filter((p) => p.seatNo && p.alive !== false).map((p) => p.seatNo)
    const others = alive.filter((seat) => seat !== bot.seat)
    const pick = others[Math.floor(Math.random() * others.length)]
    if (view === 'night') {
      const panel = nightPanel(s)
      if (!panel || panel.done) continue
      if (panel.kind === 'wolf') {
        if (s.nightInfo?.wolfPicks?.find((p) => p.seatNo === bot.seat)?.decided) continue
        const mates = s.nightInfo?.teammateSeats || []
        const target = others.find((seat) => !mates.includes(seat))
        await act(bot, '/night-action', { actionType: 'kill', targetSeatNo: target }, '刀人')
      } else if (panel.kind === 'witch') {
        await act(bot, '/night-action', { actionType: 'none', targetSeatNo: null }, '不用药')
      } else if (panel.kind === 'seer') {
        await act(bot, '/night-action', { actionType: 'check', targetSeatNo: pick }, '查验')
      } else if (panel.kind === 'guard') {
        const target = alive.find((seat) => seat !== s.nightInfo?.lastGuardSeat)
        await act(bot, '/night-action', { actionType: 'guard', targetSeatNo: target }, '守护')
      } else {
        await act(bot, '/night-action', { actionType: 'confirm', targetSeatNo: null }, '确认')
      }
    } else if (view === 'pending') {
      const panel = pendingPanel(s, 0)
      if (panel.kind === 'waiting') continue
      const button = panel.needsTarget ? pendingPanel(s, panel.targets[0]).buttons[0] : panel.buttons[0]
      await act(bot, '/skill', { decision: button.decision, targetSeatNo: button.target }, panel.kind)
    } else if (view === 'sheriff') {
      const panel = sheriffPanel(s)
      if (panel.stage === 'signup' && panel.canSignUp && !panel.candidate && bot.seat % 4 === 2) {
        await act(bot, '/sheriff', { candidate: true }, '上警')
      } else if ((panel.stage === 'vote' || panel.stage === 'pk') && panel.canVote && !panel.voted && panel.targets.length) {
        await act(bot, '/sheriff', { targetSeatNo: panel.targets[0] }, '投警长')
      }
    } else if (view === 'announce') {
      const panel = announcePanel(s)
      if (panel.canChooseOrder && !s.speech) await act(bot, '/sheriff', { speechOrder: 'right' }, '定发言顺序')
    } else if (view === 'vote') {
      const panel = votePanel(s)
      if (panel.canVote && !panel.voted && panel.targets.length) {
        const target = panel.targets.find((seat) => seat !== bot.seat) ?? panel.targets[0]
        await act(bot, '/vote', { targetSeatNo: target }, '投票')
      }
    }
  }
  await sleep(2000)
}
