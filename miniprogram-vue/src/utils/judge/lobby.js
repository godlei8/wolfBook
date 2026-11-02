// 大厅的纯逻辑（有单测）：座位网格、底部主按钮、本局规则行。
import { ROOM_RULE_STEPS } from './create-room.js'

export function lobbySeats(snapshot) {
  const count = Number(snapshot?.playerCount) || 0
  const bySeat = new Map()
  for (const player of snapshot?.players || []) {
    if (player?.seatNo) bySeat.set(player.seatNo, player)
  }
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
    }
  })
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
