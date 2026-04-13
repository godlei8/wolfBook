import {
  SESSION_PHASE_ORDER,
} from './constants'

function nowIso() {
  return new Date().toISOString()
}

function safeString(value) {
  return typeof value === 'string' ? value.trim() : ''
}

function safeNumber(value, fallback = 0) {
  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric : fallback
}

function safePositiveNumber(value, fallback = 1) {
  const numeric = safeNumber(value, fallback)
  return numeric > 0 ? numeric : fallback
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, safeNumber(value, min)))
}

function uniqueList(list = []) {
  return Array.from(new Set((list || []).filter(Boolean)))
}

function normalizeIntegerList(list = []) {
  return uniqueList((Array.isArray(list) ? list : []).map((item) => safePositiveNumber(item, 0)).filter((item) => item > 0))
}

function normalizeStringList(list = []) {
  return uniqueList((Array.isArray(list) ? list : []).map(safeString).filter(Boolean))
}

export function toArray(value) {
  if (Array.isArray(value)) return value
  if (!value || typeof value !== 'object') return []
  if (Array.isArray(value.list)) return value.list
  if (Array.isArray(value.records)) return value.records
  if (Array.isArray(value.sessions)) return value.sessions
  if (Array.isArray(value.data)) return value.data
  return Object.values(value)
}

export function extractSeatNosFromText(text) {
  if (!text || typeof text !== 'string') return []
  const matches = text.match(/(\d{1,2})号/g) || []
  return normalizeIntegerList(matches.map((item) => Number(item.replace('号', ''))))
}

export function seatNosToText(seats = []) {
  const normalized = normalizeIntegerList(seats)
  return normalized.map((seat) => `${seat}号`).join('、')
}

export function inferSceneByType(type) {
  if (type === 'seer' || type === 'nightAction') return 'night'
  if (type === 'vote') return 'vote'
  if (type === 'speech') return 'speech'
  if (type === 'identity') return 'identity'
  return 'note'
}

export function inferPhaseByType(type, scene = '') {
  if (scene === 'night' || type === 'seer' || type === 'nightAction') return 'night'
  if (scene === 'vote' || type === 'vote') return 'exile_vote'
  return 'day_speech'
}

function extractTargetSeatsFromPayload(payload = {}) {
  if (!payload || typeof payload !== 'object') return []
  const directLists = [
    payload.targetSeats,
    payload.targets,
    payload.seats,
    payload.voters,
    payload.tieTargets,
    payload.abstainSeats,
    payload.followers,
  ]
  for (const value of directLists) {
    const normalized = normalizeIntegerList(value)
    if (normalized.length) return normalized
  }
  const single = [
    payload.targetSeat,
    payload.target,
    payload.seat,
    payload.actorSeat,
    payload.checkerSeat,
    payload.speakerSeat,
    payload.sheriffSeat,
    payload.sheriffTargetSeat,
  ]
  for (const value of single) {
    const seat = safePositiveNumber(value, 0)
    if (seat > 0) return [seat]
  }
  return []
}

function inferVoteTargetsFromContent(content, actorSeats = []) {
  const seats = extractSeatNosFromText(content)
  if (!seats.length) return []
  if (!actorSeats.length) return seats.length > 1 ? [seats[seats.length - 1]] : seats
  const targets = seats.filter((seat) => !actorSeats.includes(seat))
  return targets.length ? targets : seats
}

function buildLegacyPayload(type, record, actorSeats = [], targetSeats = []) {
  const content = safeString(record?.content)
  if (type === 'seer' || type === 'nightAction') {
    return {
      subtype: 'legacy',
      checkerSeat: actorSeats[0] || null,
      targetSeat: targetSeats[0] || null,
      rawText: content,
    }
  }
  if (type === 'vote') {
    return {
      template: 'legacy',
      voters: actorSeats,
      target: targetSeats[0] || null,
      targets: targetSeats,
      sheriffSeat: null,
      followers: [],
      remark: '',
      rawText: content,
    }
  }
  if (type === 'speech') {
    return {
      speakerSeat: actorSeats[0] || null,
      speechTags: [],
      remark: content,
    }
  }
  if (type === 'identity') {
    return {
      action: 'legacy',
      seat: actorSeats[0] || targetSeats[0] || null,
      rawText: content,
    }
  }
  if (type === 'wolfPack') {
    return {
      subtype: 'wolf_pack',
      targetSeats: targetSeats.length ? targetSeats : actorSeats,
      rawText: content,
    }
  }
  return {
    rawText: content,
  }
}

function buildFallbackPlayer(record, actorSeats, targetSeats) {
  if (safeString(record?.player)) return safeString(record.player)
  if (actorSeats.length) return seatNosToText(actorSeats)
  if (targetSeats.length) return seatNosToText(targetSeats)
  return ''
}

function buildFallbackContent(type, actorSeats = [], targetSeats = [], payload = {}, player = '') {
  if (type === 'vote' && targetSeats.length) {
    const voters = seatNosToText(actorSeats)
    return voters ? `${voters} 投给 ${targetSeats[0]}号` : `投票给 ${targetSeats[0]}号`
  }
  if ((type === 'seer' || type === 'nightAction') && targetSeats.length) {
    return actorSeats.length ? `${actorSeats[0]}号夜间行动 -> ${targetSeats[0]}号` : `夜间行动 -> ${targetSeats[0]}号`
  }
  if (type === 'wolfPack' || (type === 'note' && payload?.subtype === 'wolf_pack')) {
    return `狼坑：${seatNosToText(targetSeats)}`
  }
  if (player) return `${player} 的局内记录`
  return '局内记录'
}

export function normalizePlayer(player, seatNo, sheriffSeat = null, createFallback = nowIso()) {
  return {
    seatNo: safePositiveNumber(player?.seatNo, seatNo),
    nickname: safeString(player?.nickname),
    alive: player?.alive !== false,
    isSheriff: sheriffSeat ? safePositiveNumber(player?.seatNo, seatNo) === sheriffSeat : !!player?.isSheriff,
    claimedRole: safeString(player?.claimedRole),
    realRole: safeString(player?.realRole),
    factionHint: safeString(player?.factionHint),
    suspicionLevel: clamp(player?.suspicionLevel, 0, 3),
    tags: normalizeStringList(player?.tags),
    deathDay: safePositiveNumber(player?.deathDay, 0) || null,
    deathPhase: safeString(player?.deathPhase),
    deathReason: safeString(player?.deathReason),
    isFocus: !!player?.isFocus,
    note: safeString(player?.note),
    createTime: safeString(player?.createTime) || createFallback,
    updateTime: safeString(player?.updateTime) || safeString(player?.createTime) || createFallback,
  }
}

export function buildDefaultPlayers(playerCount = 12, sheriffSeat = null, createFallback = nowIso()) {
  const count = Math.max(1, safePositiveNumber(playerCount, 12))
  return Array.from({ length: count }, (_, index) => normalizePlayer(null, index + 1, sheriffSeat, createFallback))
}

export function normalizePlayers(players, playerCount = 12, sheriffSeat = null, createFallback = nowIso()) {
  const count = Math.max(
    1,
    safePositiveNumber(playerCount, 12),
    ...(Array.isArray(players) ? players.map((item) => safePositiveNumber(item?.seatNo, 0)) : [0]),
  )
  const playerMap = new Map((Array.isArray(players) ? players : []).map((item) => [safePositiveNumber(item?.seatNo, 0), item]))
  return Array.from({ length: count }, (_, index) => normalizePlayer(playerMap.get(index + 1), index + 1, sheriffSeat, createFallback))
}

export function normalizeRecord(record, index = 0) {
  if (!record || typeof record !== 'object') return null

  const timestamp = safeString(record.timestamp) || safeString(record.updateTime) || safeString(record.createTime) || nowIso()
  const type = safeString(record.type) || 'speech'
  const scene = safeString(record.scene) || inferSceneByType(type)
  const day = Math.max(1, safePositiveNumber(record.day, safePositiveNumber(record.round, 1)))
  const round = Math.max(1, safePositiveNumber(record.round, day))
  const payload = record.payload && typeof record.payload === 'object' ? { ...record.payload } : null

  let actorSeats = normalizeIntegerList(record.actorSeats)
  let targetSeats = normalizeIntegerList(record.targetSeats)

  if (!actorSeats.length) {
    actorSeats = extractSeatNosFromText(record.player)
  }

  if (!targetSeats.length && payload) {
    targetSeats = extractTargetSeatsFromPayload(payload)
  }

  if (!targetSeats.length) {
    if (type === 'vote') {
      targetSeats = inferVoteTargetsFromContent(record.content, actorSeats)
    } else {
      targetSeats = extractSeatNosFromText(record.content).filter((seat) => !actorSeats.includes(seat))
    }
  }

  const normalizedPayload = payload || buildLegacyPayload(type, record, actorSeats, targetSeats)
  const player = buildFallbackPlayer(record, actorSeats, targetSeats)
  const content = safeString(record.content) || buildFallbackContent(type, actorSeats, targetSeats, normalizedPayload, player)

  return {
    id: String(record.id || record.recordId || `rec_${Date.now()}_${index}`),
    type,
    scene,
    day,
    round,
    phase: safeString(record.phase) || inferPhaseByType(type, scene),
    actorSeats,
    targetSeats,
    player,
    content,
    payload: normalizedPayload,
    tags: normalizeStringList(record.tags),
    editable: record.editable !== false,
    timestamp,
    createTime: safeString(record.createTime) || timestamp,
    updateTime: safeString(record.updateTime) || timestamp,
  }
}

function getPlayerBySeat(players = [], seatNo) {
  return (players || []).find((item) => item.seatNo === seatNo) || null
}

function mergeTags(tags = [], nextTags = []) {
  return normalizeStringList([...(tags || []), ...(nextTags || [])])
}

export function applyRecordEffects(state, record) {
  if (!state || !record) return state

  const payload = record.payload || {}
  const firstSeat = record.targetSeats?.[0] || record.actorSeats?.[0] || safePositiveNumber(payload.targetSeat, 0) || safePositiveNumber(payload.seat, 0) || null

  if (record.type === 'identity') {
    const action = safeString(payload.action || payload.subtype)
    const targetSeat = firstSeat
    const player = getPlayerBySeat(state.players, targetSeat)
    if (!player) return state

    if (action === 'claim_role') {
      player.claimedRole = safeString(payload.roleName || payload.claimedRole || payload.role)
    } else if (action === 'set_real_role') {
      player.realRole = safeString(payload.roleName || payload.role)
    } else if (action === 'set_sheriff') {
      state.sheriffSeat = targetSeat
    } else if (action === 'remove_sheriff' || action === 'unset_sheriff') {
      if (state.sheriffSeat === targetSeat) state.sheriffSeat = null
      player.isSheriff = false
    } else if (action === 'mark_dead' || action === 'mark_out') {
      player.alive = false
      player.deathDay = record.day
      player.deathPhase = record.phase
      player.deathReason = safeString(payload.deathReason || payload.reason)
    } else if (action === 'revive') {
      player.alive = true
      player.deathDay = null
      player.deathPhase = ''
      player.deathReason = ''
    }
    player.updateTime = record.updateTime || nowIso()
  }

  if (record.type === 'seer' || record.type === 'nightAction') {
    const subtype = safeString(payload.subtype || payload.template)
    const targetSeat = record.targetSeats?.[0] || safePositiveNumber(payload.targetSeat, 0) || safePositiveNumber(payload.target, 0)
    const player = getPlayerBySeat(state.players, targetSeat)
    if (player) {
      if (subtype === 'wolf_kill' || safeString(payload.result) === 'dead') {
        player.alive = false
        player.deathDay = record.day
        player.deathPhase = record.phase
        player.deathReason = subtype === 'witch_poison' ? 'poison' : 'knife'
      }
      if (subtype === 'witch_poison' || safeString(payload.result) === 'poisoned') {
        player.alive = false
        player.deathDay = record.day
        player.deathPhase = record.phase
        player.deathReason = 'poison'
      }
    }
  }

  if (record.type === 'note' || record.type === 'wolfPack') {
    const subtype = safeString(payload.subtype || (record.type === 'wolfPack' ? 'wolf_pack' : ''))
    if (subtype === 'player_note' && record.targetSeats?.length === 1) {
      const player = getPlayerBySeat(state.players, record.targetSeats[0])
      if (player) {
        player.note = safeString(payload.remark || record.content)
        player.updateTime = record.updateTime || nowIso()
      }
    }
    if (subtype === 'wolf_pack') {
      state.latestWolfPackSeats = normalizeIntegerList(payload.targetSeats || record.targetSeats)
    }
  }

  return state
}

export function replaySessionPlayers(session) {
  const createFallback = safeString(session?.createTime) || nowIso()
  const records = (session?.records || []).map(normalizeRecord).filter(Boolean).sort(sortRecordsAsc)
  const seedPlayers = normalizePlayers(session?.players, session?.playerCount, session?.sheriffSeat, createFallback).map((item) => ({ ...item }))
  const state = {
    players: seedPlayers,
    sheriffSeat: safePositiveNumber(session?.sheriffSeat, 0) || null,
    latestWolfPackSeats: normalizeIntegerList(session?.summary?.latestWolfPackSeats),
  }

  records.forEach((record) => {
    applyRecordEffects(state, record)
  })

  state.players.forEach((player) => {
    player.isSheriff = !!state.sheriffSeat && player.seatNo === state.sheriffSeat
  })

  return state
}

export function inferCurrentDay(records = []) {
  return Math.max(1, ...(records || []).map((item) => safePositiveNumber(item?.day, safePositiveNumber(item?.round, 1))))
}

export function inferCurrentPhase(records = []) {
  const sorted = (records || []).slice().sort(sortRecordsAsc)
  const latest = sorted[sorted.length - 1]
  return latest?.phase || inferPhaseByType(latest?.type, latest?.scene)
}

export function rebuildSessionSummary(session) {
  const players = Array.isArray(session?.players) ? session.players : []
  const records = (session?.records || []).slice().sort(sortRecordsAsc)
  const currentDay = safePositiveNumber(session?.currentDay, inferCurrentDay(records))
  const aliveCount = players.filter((item) => item.alive !== false).length
  const deadCount = Math.max(0, players.length - aliveCount)

  const latestWolfPackRecord = records
    .slice()
    .reverse()
    .find((item) => item.type === 'wolfPack' || item.payload?.subtype === 'wolf_pack')
  const latestWolfPackSeats = normalizeIntegerList(
    latestWolfPackRecord?.payload?.targetSeats ||
      latestWolfPackRecord?.targetSeats ||
      latestWolfPackRecord?.payload?.seats,
  )

  const latestVoteRecord = records
    .slice()
    .reverse()
    .find((item) => item.type === 'vote' && item.day === currentDay && item.phase === 'exile_vote')

  const latestRecord = records[records.length - 1] || null

  return {
    aliveCount,
    deadCount,
    todayOutSeat: latestVoteRecord?.targetSeats?.[0] || latestVoteRecord?.payload?.targetSeat || latestVoteRecord?.payload?.target || null,
    latestWolfPackSeats,
    latestWolfPackText: seatNosToText(latestWolfPackSeats),
    keyEventCount: records.filter((item) => (item.tags || []).length > 0).length,
    latestRecordType: latestRecord?.type || '',
    latestRecordPreview: latestRecord?.content || '',
  }
}

export function rebuildSessionState(session) {
  const base = session || {}
  const records = toArray(base.records).map(normalizeRecord).filter(Boolean).sort(sortRecordsAsc)
  const replayed = replaySessionPlayers({ ...base, records })
  const currentDay = Math.max(safePositiveNumber(base.currentDay, 0), inferCurrentDay(records))
  const currentPhase = safeString(base.currentPhase) || inferCurrentPhase(records)
  const summary = rebuildSessionSummary({
    ...base,
    players: replayed.players,
    records,
    currentDay,
  })

  return {
    ...base,
    version: 2,
    status: safeString(base.status) || 'active',
    currentDay,
    currentPhase: currentPhase || 'day_speech',
    resultCamp: safeString(base.resultCamp),
    sheriffSeat: replayed.sheriffSeat,
    players: replayed.players,
    summary: {
      ...summary,
      ...((base.summary && typeof base.summary === 'object') ? {
        latestRecordType: safeString(base.summary.latestRecordType) || summary.latestRecordType,
        latestRecordPreview: safeString(base.summary.latestRecordPreview) || summary.latestRecordPreview,
      } : {}),
    },
    records,
    createTime: safeString(base.createTime) || nowIso(),
    updateTime: safeString(base.updateTime) || safeString(base.createTime) || nowIso(),
  }
}

export function normalizeSession(session, index = 0) {
  if (!session || typeof session !== 'object') return null

  const boardId = safePositiveNumber(session.boardId, 0)
  const hasBoardId = boardId > 0
  const playerCount = Math.max(1, safePositiveNumber(session.playerCount, 12))

  const base = {
    sessionId: String(session.sessionId || session.id || `session_${Date.now()}_${index}`),
    version: 2,
    boardMode: session.boardMode === 'custom' ? 'custom' : hasBoardId ? 'library' : 'custom',
    boardId: hasBoardId ? boardId : null,
    boardName: safeString(session.boardName),
    playerCount,
    status: safeString(session.status) || 'active',
    currentDay: safePositiveNumber(session.currentDay, 0),
    currentPhase: safeString(session.currentPhase),
    resultCamp: safeString(session.resultCamp),
    sheriffSeat: safePositiveNumber(session.sheriffSeat, 0) || null,
    players: normalizePlayers(session.players, playerCount, safePositiveNumber(session.sheriffSeat, 0) || null, safeString(session.createTime) || nowIso()),
    summary: session.summary && typeof session.summary === 'object' ? { ...session.summary } : {},
    records: toArray(session.records).map(normalizeRecord).filter(Boolean),
    createTime: safeString(session.createTime) || safeString(session.updateTime) || nowIso(),
    updateTime: safeString(session.updateTime) || safeString(session.createTime) || nowIso(),
  }

  return rebuildSessionState(base)
}

export function normalizeSessions(value) {
  return toArray(value)
    .map(normalizeSession)
    .filter(Boolean)
    .sort((left, right) => new Date(right.updateTime || right.createTime || 0).getTime() - new Date(left.updateTime || left.createTime || 0).getTime())
}

export function createSessionModel(partial = {}) {
  return normalizeSession({
    sessionId: partial.sessionId,
    boardMode: partial.boardMode || 'custom',
    boardId: partial.boardId || null,
    boardName: partial.boardName || '',
    playerCount: partial.playerCount || 12,
    status: partial.status || 'active',
    currentDay: partial.currentDay || 1,
    currentPhase: partial.currentPhase || 'day_speech',
    resultCamp: partial.resultCamp || '',
    sheriffSeat: partial.sheriffSeat || null,
    players: partial.players,
    summary: partial.summary,
    records: partial.records || [],
    createTime: partial.createTime || nowIso(),
    updateTime: partial.updateTime || nowIso(),
  })
}

export function sortRecordsAsc(left, right) {
  const leftTime = new Date(left?.timestamp || left?.updateTime || left?.createTime || 0).getTime()
  const rightTime = new Date(right?.timestamp || right?.updateTime || right?.createTime || 0).getTime()
  if (left?.day !== right?.day) return safePositiveNumber(left?.day, 1) - safePositiveNumber(right?.day, 1)
  if (safeNumber(SESSION_PHASE_ORDER[left?.phase], 99) !== safeNumber(SESSION_PHASE_ORDER[right?.phase], 99)) {
    return safeNumber(SESSION_PHASE_ORDER[left?.phase], 99) - safeNumber(SESSION_PHASE_ORDER[right?.phase], 99)
  }
  return leftTime - rightTime
}

export function sortRecordsDesc(left, right) {
  return -sortRecordsAsc(left, right)
}

export function buildTimelineGroups(records = []) {
  const normalized = (records || []).map(normalizeRecord).filter(Boolean).sort(sortRecordsDesc)
  const dayMap = new Map()

  normalized.forEach((record) => {
    const day = safePositiveNumber(record.day, 1)
    if (!dayMap.has(day)) {
      dayMap.set(day, new Map())
    }
    const phaseMap = dayMap.get(day)
    const phase = record.phase || inferPhaseByType(record.type, record.scene)
    if (!phaseMap.has(phase)) {
      phaseMap.set(phase, [])
    }
    phaseMap.get(phase).push(record)
  })

  return Array.from(dayMap.entries())
    .sort((left, right) => right[0] - left[0])
    .map(([day, phaseMap]) => ({
      day,
      label: `第${day}天`,
      phases: Array.from(phaseMap.entries())
        .sort((left, right) => safeNumber(SESSION_PHASE_ORDER[left[0]], 99) - safeNumber(SESSION_PHASE_ORDER[right[0]], 99))
        .map(([phase, items]) => ({
          phase,
          records: items.slice().sort(sortRecordsAsc),
        })),
      recordCount: Array.from(phaseMap.values()).reduce((total, items) => total + items.length, 0),
    }))
}

export function buildSessionKeyPlayers(session) {
  const players = session?.players || []
  return players
    .slice()
    .sort((left, right) => {
      const leftScore = safeNumber(left.suspicionLevel, 0) + (left.isSheriff ? 2 : 0) + (left.isFocus ? 1 : 0)
      const rightScore = safeNumber(right.suspicionLevel, 0) + (right.isSheriff ? 2 : 0) + (right.isFocus ? 1 : 0)
      return rightScore - leftScore
    })
    .slice(0, 4)
}

export function buildLatestRecords(records = [], count = 3) {
  return (records || [])
    .map(normalizeRecord)
    .filter(Boolean)
    .sort((left, right) => new Date(right.timestamp || right.updateTime || 0).getTime() - new Date(left.timestamp || left.updateTime || 0).getTime())
    .slice(0, count)
}
