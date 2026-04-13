import { getDeathReasonLabel } from './constants'

function safeString(value) {
  return typeof value === 'string' ? value.trim() : ''
}

function safePositiveNumber(value) {
  const numeric = Number(value)
  return Number.isFinite(numeric) && numeric > 0 ? numeric : 0
}

export function localizeDeathReason(reason) {
  return getDeathReasonLabel(safeString(reason)) || safeString(reason)
}

export function localizeRecordContent(record) {
  if (!record || typeof record !== 'object') {
    return ''
  }

  const payload = record.payload && typeof record.payload === 'object' ? record.payload : {}
  const action = safeString(payload.action || payload.subtype)
  let content = safeString(record.content)

  if (record.type === 'identity' && action === 'mark_dead') {
    const deathReason = localizeDeathReason(payload.deathReason)
    if (!content) {
      const seatNo = record.targetSeats?.[0] || record.actorSeats?.[0] || safePositiveNumber(payload.targetSeat || payload.seat)
      return `${seatNo ? `${seatNo}号` : '玩家'}出局${deathReason ? `（${deathReason}）` : ''}`
    }
    if (deathReason) {
      content = content
        .replace(/(?:\(|（)\s*(knife|poison|vote|skill|other)\s*(?:\)|）)/gi, `（${deathReason}）`)
        .replace(/\b(knife|poison|vote|skill|other)\b/gi, (token) => localizeDeathReason(token))
    }
  }

  return content
}

export function localizeSessionRecord(record) {
  if (!record || typeof record !== 'object') {
    return record
  }
  const payload = record.payload && typeof record.payload === 'object'
    ? { ...record.payload }
    : record.payload

  return {
    ...record,
    payload,
    content: localizeRecordContent({ ...record, payload }),
  }
}
