// 牌桌布局（纯函数，有单测）：整局（夜晚、出局技能、上警、天亮、发言、投票、票型）都是两侧座位 + 中间大卡片，
// 座位位置和大小全局不变；每个阶段只是座位上的标记、能不能点不一样。
// 零场外：夜里非持有者的座位上什么标记都没有；出局待办只有当事人能点座位，别人看不出是谁在处理。
import { seatColumns } from './room-view.js'
import { isStepHolder, seatBadges } from './night-panel.js'
import { godSeats } from './judge-bar.js'
import { sheriffPanel, speakerOf } from './sheriff.js'
import { voteResultPanel, votePanel } from './day.js'
import { pendingPanel } from './pending.js'

/** 牌桌下面操作面板区的高度（rpx），整局所有阶段、所有人一样：座位不跳，夜里也看不出谁有面板。 */
export const GAME_PANEL_HEIGHT = 380

/**
 * 面板区的行内样式。没有底部操作栏时（夜里的玩家、出局待办）面板往下多占操作栏那块，
 * 牌桌（座位、立绘）的高度和有操作栏时一样：整局白天黑夜座位位置、大小都不变。
 */
export function gameAreaStyle(bar) {
  return bar ? `height: ${GAME_PANEL_HEIGHT}rpx` : `height: calc(${GAME_PANEL_HEIGHT}rpx + var(--bar-space) - 32rpx)`
}

const NONE = { tappable: [], selected: 0, dimOthers: false }

/** 这一阶段每个座位的标记：tag（左上角，警 / 票数 / PK）、能不能点、选中、变暗、正在发言、被放逐。 */
function phaseMarks(snapshot, view, selection) {
  const marks = { tags: new Map(), tagKind: '', speaking: 0, out: 0, ...NONE }
  if (view === 'sheriff') {
    const panel = sheriffPanel(snapshot)
    for (const seat of panel.candidates) marks.tags.set(seat, '警')
    marks.tagKind = 'moon'
    marks.speaking = panel.speakerSeat || 0
    if (panel.canVote) Object.assign(marks, { tappable: panel.targets, selected: selection, dimOthers: true })
  } else if (view === 'speech') {
    marks.speaking = speakerOf(snapshot) || 0
  } else if (view === 'vote') {
    const panel = votePanel(snapshot)
    if (panel.stage === 'pk') {
      for (const seat of panel.targets) marks.tags.set(seat, 'PK')
      marks.tagKind = 'gold'
    }
    if (panel.canVote) Object.assign(marks, { tappable: panel.targets, selected: selection, dimOthers: true })
  } else if (view === 'voteResult') {
    const outcome = voteResultPanel(snapshot)
    for (const tally of outcome.tallies) marks.tags.set(tally.targetSeatNo, String(tally.weight))
    marks.tagKind = 'votes'
    marks.out = outcome.eliminatedSeat || 0
  } else if (view === 'pending') {
    const panel = pendingPanel(snapshot, selection)
    if (panel.needsTarget) Object.assign(marks, { tappable: panel.targets, selected: selection })
    // 遗言是公开的：留遗言的人座位亮起（技能询问等不公开，别人的座位上什么都没有）
    if (panel.kind === 'last_words') marks.speaking = snapshot.currentPending.seatNo || 0
  }
  return marks
}

export function stageLayout(snapshot, view, { selection = 0, stageHeight = 0 } = {}) {
  const columns = seatColumns(snapshot?.players, snapshot?.playerCount, stageHeight)
  const night = view === 'night'
  const badges = night ? seatBadges(snapshot, selection) : null
  const holder = night && isStepHolder(snapshot)
  const god = godSeats(snapshot)
  const marks = night ? null : phaseMarks(snapshot, view, selection)
  const sheriffSeat = snapshot?.sheriffSeatNo || 0

  const decorate = (seat) => {
    const player = seat.player
    const dead = Boolean(player) && player.alive === false
    const role = god.get(seat.seatNo) || null
    const base = {
      seatNo: seat.seatNo,
      empty: seat.empty,
      avatar: player?.avatar || '',
      initial: (player?.nickname || '').slice(0, 1),
      dead,
      sheriff: sheriffSeat === seat.seatNo,
      role: role?.roleShort || '',
      roleColor: role?.roleColor || '',
      mate: false,
      speaking: false,
      out: false,
      tag: '',
      tagKind: '',
      tappable: false,
      selected: false,
      dim: dead,
    }
    if (night) {
      const badge = badges.get(seat.seatNo) || { mate: false, votes: 0, selected: false, disabled: true }
      const votes = badge.votes || role?.votes || 0
      return {
        ...base,
        mate: badge.mate,
        tag: votes ? String(votes) : '',
        tagKind: votes ? 'votes' : '',
        tappable: holder && !seat.empty && !badge.disabled,
        selected: badge.selected,
      }
    }
    const tappable = !seat.empty && marks.tappable.includes(seat.seatNo)
    const tag = marks.tags.get(seat.seatNo) || ''
    return {
      ...base,
      speaking: marks.speaking === seat.seatNo,
      out: marks.out === seat.seatNo,
      tag,
      tagKind: tag ? marks.tagKind : '',
      tappable,
      selected: tappable && marks.selected === seat.seatNo,
      // 票型公布时被放逐的人已经出局，但这一屏要看清他的红圈，不变暗
      dim: (dead && marks.out !== seat.seatNo) || (marks.dimOthers && !tappable),
    }
  }

  return {
    size: columns.size,
    gap: columns.gap,
    cardHeight: columns.cardHeight,
    baseHeight: columns.baseHeight,
    sides: [
      { key: 'left', seats: columns.left.map(decorate) },
      { key: 'right', seats: columns.right.map(decorate) },
    ],
  }
}

/**
 * 量出视图底边后，算座位列和中间卡片该有多高才能贴到屏幕底（有操作栏时给它留 200rpx + 安全区）。
 * 差几 rpx 或和现在一样返回 null（不用改）。
 */
export function fitStageHeight({ cardHeight, current = 0, bottom, windowHeight, windowWidth, bar, safeBottom = 0 }) {
  if (!windowHeight || !windowWidth) return null
  const pxToRpx = 750 / windowWidth
  const reserve = bar ? 200 / pxToRpx + safeBottom : 32 / pxToRpx
  const free = (windowHeight - reserve - bottom) * pxToRpx
  const next = Math.max(0, Math.floor(cardHeight + free))
  if (Math.abs(next - cardHeight) <= 4 || next === current) return null
  return next
}
