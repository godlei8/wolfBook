// 大厅的纯逻辑（有单测）：座位网格、底部主按钮、本局规则行。
import { ROOM_RULE_STEPS } from './create-room.js'
import { countdown } from './room-view.js'

export function lobbySeats(snapshot) {
  const count = Number(snapshot?.playerCount) || 0
  const bySeat = new Map()
  for (const player of snapshot?.players || []) {
    if (player?.seatNo) bySeat.set(player.seatNo, player)
  }
  const requestedSeat = snapshot?.seatSwap?.outgoing?.seatNo || null
  return Array.from({ length: count }, (_, i) => {
    const player = bySeat.get(i + 1)
    if (!player) return { seatNo: i + 1, nickname: '', avatar: '', ready: false, me: false, empty: true }
    return {
      seatNo: i + 1,
      nickname: player.nickname || `${i + 1} 号`,
      avatar: player.avatar || '',
      ready: Boolean(player.ready),
      me: Boolean(player.self),
      empty: false,
      requested: requestedSeat === i + 1,
    }
  })
}

/**
 * 点大厅里的一个座位（用户给定规则）：空位直接入座；有人的座位申请换座（对方 30 秒内同意才换）；
 * 已经申请过的座位再点是撤回。没入座的人（法官、还没进房）点了没反应。confirm 不为空时先弹确认框。
 */
export function seatTap(snapshot, seatNo) {
  const self = snapshot?.selfPlayer
  if (!self?.seatNo || seatNo === self.seatNo) return null
  const player = (snapshot?.players || []).find((item) => item?.seatNo === seatNo)
  if (!player) return { action: 'move', seatNo, confirm: '' }
  if (snapshot?.seatSwap?.outgoing?.seatNo === seatNo) {
    return { action: 'cancel', seatNo, confirm: `撤回和 ${seatNo} 号的换座申请？` }
  }
  return { action: 'request', seatNo, confirm: `申请和 ${seatNo} 号 ${player.nickname || ''} 换座？对方 30 秒内同意才会换。` }
}

/** 大厅顶部的换座提示条：先显示别人发给我的（同意 / 拒绝），再显示我发出去的（撤回）；过期了不显示。 */
export function swapBanner(snapshot, now) {
  const swap = snapshot?.seatSwap
  const incoming = swap?.incoming
  if (incoming) {
    const left = countdown(incoming.deadline, now, false).seconds
    if (left > 0) {
      return {
        kind: 'incoming',
        text: `${incoming.seatNo} 号 ${incoming.nickname} 想和你换座`,
        seconds: left,
        buttons: [
          { action: 'accept', label: '同意', seatNo: incoming.seatNo },
          { action: 'reject', label: '拒绝', seatNo: incoming.seatNo },
        ],
      }
    }
  }
  const outgoing = swap?.outgoing
  if (outgoing) {
    const left = countdown(outgoing.deadline, now, false).seconds
    if (left > 0) {
      return {
        kind: 'outgoing',
        text: `已申请和 ${outgoing.seatNo} 号 ${outgoing.nickname} 换座，等对方同意`,
        seconds: left,
        buttons: [{ action: 'cancel', label: '撤回', seatNo: outgoing.seatNo }],
      }
    }
  }
  return null
}

/**
 * 底部主按钮。有法官房间的法官本人不入座，只有「开始游戏」；无法官房主坐 1 号，先准备再开局。
 * kind：start / ready / unready / join（受邀进来还没入座）/ wait。
 */
export function lobbyAction(snapshot) {
  const self = snapshot?.selfPlayer || null
  const seated = Boolean(self?.seatNo)
  if (snapshot?.host && (!seated || self?.ready)) {
    if (snapshot.canStart) return { kind: 'start', label: '开始游戏', disabled: false }
    const missing = Math.max(0, (Number(snapshot.playerCount) || 0) - (Number(snapshot.readyCount) || 0))
    return { kind: 'start', label: `还差 ${missing} 人`, disabled: true }
  }
  if (seated) {
    return self.ready
      ? { kind: 'unready', label: '取消准备', disabled: false }
      : { kind: 'ready', label: '我已准备', disabled: false }
  }
  if (snapshot?.canJoin) return { kind: 'join', label: '加入并入座', disabled: false }
  return { kind: 'wait', label: '房间已满', disabled: true }
}

/** 本局规则（节奏项）。快照里上警开关叫 sheriffDefault，改规则接口叫 sheriffEnabled。 */
export function rulesRows(snapshot) {
  const rules = snapshot?.rules || {}
  const editable = Boolean(snapshot?.host) && snapshot?.roomStatus === 'lobby'
  return [
    { key: 'sheriffEnabled', label: '上警竞选', value: rules.sheriffDefault === false ? '关' : '开', editable },
    ...Object.entries(ROOM_RULE_STEPS).map(([key, range]) => ({
      key,
      label: range.label,
      value: `${rules[key] || '—'} 秒`,
      editable,
    })),
  ]
}
