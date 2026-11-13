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
 * 房主点有人的座位可以请他离开（被请离的不能再进这个房间）：不入座的法官直接是请离，坐着的房主在换座和请离里选（choices）。
 */
export function seatTap(snapshot, seatNo) {
  const self = snapshot?.selfPlayer
  const player = (snapshot?.players || []).find((item) => item?.seatNo === seatNo)
  const remove = player && snapshot?.host && seatNo !== self?.seatNo
    ? { action: 'remove', seatNo, confirm: `请 ${seatNo} 号 ${player.nickname || ''} 离开房间？离开后他不能再进这个房间。` }
    : null
  if (!self?.seatNo) return remove
  if (seatNo === self.seatNo) return null
  if (!player) return { action: 'move', seatNo, confirm: '' }
  const swap = snapshot?.seatSwap?.outgoing?.seatNo === seatNo
    ? { action: 'cancel', seatNo, confirm: `撤回和 ${seatNo} 号的换座申请？` }
    : { action: 'request', seatNo, confirm: `申请和 ${seatNo} 号 ${player.nickname || ''} 换座？对方 30 秒内同意才会换。` }
  if (!remove) return swap
  return { choices: [{ ...swap, label: swap.action === 'cancel' ? '撤回换座申请' : '申请换座' }, { ...remove, label: '请他离开' }] }
}

/** 大厅底部的「解散房间 / 离开房间」（只在大厅）：房主解散（房间就没有了），入座的玩家离开（还可以再进）。 */
export function exitAction(snapshot) {
  if (snapshot?.roomStatus !== 'lobby') return null
  if (snapshot?.host) {
    return { kind: 'dissolve', label: '解散房间', title: '解散房间', confirm: '解散后房间就没有了，房间里的人都会退出。' }
  }
  if (snapshot?.selfPlayer?.seatNo) {
    return { kind: 'leave', label: '离开房间', title: '离开房间', confirm: '离开后座位会空出来，还可以再进。' }
  }
  return null
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
