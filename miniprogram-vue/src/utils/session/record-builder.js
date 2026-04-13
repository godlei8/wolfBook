import { inferPhaseByType, inferSceneByType, normalizeRecord, seatNosToText, extractSeatNosFromText } from './normalizer'

export const SCENE_OPTIONS = [
  { value: 'night', label: '夜间' },
  { value: 'speech', label: '发言' },
  { value: 'vote', label: '投票' },
  { value: 'identity', label: '身份' },
  { value: 'note', label: '备注' },
]

export const NIGHT_SUBTYPE_OPTIONS = [
  { value: 'seer_check', label: '查验' },
  { value: 'wolf_kill', label: '刀口' },
  { value: 'witch_poison', label: '毒杀' },
  { value: 'guard_protect', label: '守护' },
  { value: 'night_note', label: '夜间备注' },
]

export const SPEECH_TAG_OPTIONS = ['跳身份', '点狼', '保人', '站边', '退水', 'PK', '表水', '认好']

export const VOTE_SUBTYPE_OPTIONS = [
  { value: 'normal', label: '普通投票' },
  { value: 'tie', label: '平票' },
  { value: 'abstain', label: '弃票' },
  { value: 'sheriff', label: '警长归票' },
]

export const IDENTITY_ACTION_OPTIONS = [
  { value: 'claim_role', label: '跳身份' },
  { value: 'set_sheriff', label: '设为警长' },
  { value: 'remove_sheriff', label: '取消警长' },
  { value: 'mark_dead', label: '标记出局' },
  { value: 'revive', label: '恢复存活' },
  { value: 'set_real_role', label: '确认真身份' },
]

export const NOTE_SUBTYPE_OPTIONS = [
  { value: 'general', label: '普通备注' },
  { value: 'player_note', label: '玩家备注' },
  { value: 'wolf_pack', label: '狼坑' },
  { value: 'turning_point', label: '关键转折' },
]

export const DEATH_REASON_OPTIONS = [
  { value: 'knife', label: '刀口' },
  { value: 'poison', label: '毒杀' },
  { value: 'vote', label: '放逐' },
  { value: 'skill', label: '技能' },
  { value: 'other', label: '其他' },
]

export const COMMON_ROLE_OPTIONS = ['预言家', '女巫', '猎人', '守卫', '白痴', '骑士', '狼王', '狼人', '平民']

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

function positive(value, fallback = 1) {
  const numeric = safeNumber(value, fallback)
  return numeric > 0 ? numeric : fallback
}

function normalizeSeatList(value) {
  return Array.from(new Set((Array.isArray(value) ? value : []).map((item) => positive(item, 0)).filter((item) => item > 0)))
}

function normalizeTagList(value) {
  return Array.from(new Set((Array.isArray(value) ? value : []).map(safeString).filter(Boolean)))
}

function phaseForScene(scene, currentPhase = '') {
  if (scene === 'night') return 'night'
  if (scene === 'vote') return currentPhase === 'sheriff_race' ? 'sheriff_race' : 'exile_vote'
  if (scene === 'speech') {
    return ['sheriff_race', 'day_speech', 'last_words'].includes(currentPhase) ? currentPhase : 'day_speech'
  }
  return currentPhase || 'day_speech'
}

export function resolveSceneForRecord(record) {
  return safeString(record?.scene) || inferSceneByType(record?.type)
}

export function createDefaultForm(scene, context = {}) {
  const currentDay = positive(context.currentDay, 1)
  const currentPhase = phaseForScene(scene, safeString(context.currentPhase))

  if (scene === 'night') {
    return {
      day: currentDay,
      phase: 'night',
      subtype: 'seer_check',
      actorSeat: null,
      targetSeat: null,
      result: '',
      quickTags: [],
      remark: '',
    }
  }

  if (scene === 'speech') {
    return {
      day: currentDay,
      phase: currentPhase,
      actorSeat: null,
      targetSeats: [],
      speechTags: [],
      claimedRole: '',
      remark: '',
      quickTags: [],
    }
  }

  if (scene === 'vote') {
    return {
      day: currentDay,
      phase: currentPhase === 'sheriff_race' ? 'sheriff_race' : 'exile_vote',
      subtype: 'normal',
      voters: [],
      targetSeat: null,
      tieTargets: [],
      abstainSeats: [],
      sheriffSeat: null,
      sheriffTargetSeat: null,
      sheriffFollowers: [],
      remark: '',
      quickTags: [],
    }
  }

  if (scene === 'identity') {
    return {
      day: currentDay,
      phase: currentPhase,
      action: 'claim_role',
      targetSeat: null,
      roleName: '',
      deathReason: '',
      remark: '',
      quickTags: [],
    }
  }

  return {
    day: currentDay,
    phase: currentPhase,
    subtype: 'general',
    targetSeats: [],
    suspicionLevel: null,
    remark: '',
    quickTags: [],
  }
}

function inferVoteSubtype(record) {
  const payload = record?.payload || {}
  const template = safeString(payload.template || payload.subtype)
  if (template) return template
  const content = safeString(record?.content)
  if (content.includes('平票')) return 'tie'
  if (content.includes('弃票')) return 'abstain'
  if (content.includes('归票')) return 'sheriff'
  return 'normal'
}

export function hydrateFormByRecord(scene, record, context = {}) {
  const normalized = normalizeRecord(record || {})
  const form = createDefaultForm(scene, {
    ...context,
    currentDay: normalized?.day,
    currentPhase: normalized?.phase,
  })
  const payload = normalized?.payload || {}

  if (scene === 'night') {
    return {
      ...form,
      day: normalized.day,
      phase: normalized.phase || 'night',
      subtype: safeString(payload.subtype || payload.template) || (normalized.type === 'seer' ? 'seer_check' : 'night_note'),
      actorSeat: normalized.actorSeats?.[0] || payload.actorSeat || payload.checkerSeat || null,
      targetSeat: normalized.targetSeats?.[0] || payload.targetSeat || payload.target || null,
      result: safeString(payload.result || payload.resultTag),
      quickTags: normalizeTagList(normalized.tags),
      remark: safeString(payload.remark || payload.rawText || normalized.content),
    }
  }

  if (scene === 'speech') {
    return {
      ...form,
      day: normalized.day,
      phase: normalized.phase,
      actorSeat: normalized.actorSeats?.[0] || payload.actorSeat || payload.speakerSeat || null,
      targetSeats: normalizeSeatList(payload.targetSeats || normalized.targetSeats),
      speechTags: normalizeTagList(payload.speechTags || normalized.tags),
      claimedRole: safeString(payload.claimedRole || payload.roleName || payload.role),
      remark: safeString(payload.remark || normalized.content),
      quickTags: normalizeTagList(normalized.tags),
    }
  }

  if (scene === 'vote') {
    const subtype = inferVoteSubtype(normalized)
    return {
      ...form,
      day: normalized.day,
      phase: normalized.phase,
      subtype,
      voters: normalizeSeatList(payload.voters || normalized.actorSeats),
      targetSeat: payload.targetSeat || payload.target || normalized.targetSeats?.[0] || null,
      tieTargets: normalizeSeatList(payload.tieTargets || payload.targets || normalized.targetSeats),
      abstainSeats: normalizeSeatList(payload.abstainSeats || normalized.actorSeats),
      sheriffSeat: payload.sheriffSeat || normalized.actorSeats?.[0] || null,
      sheriffTargetSeat: payload.sheriffTargetSeat || payload.targetSeat || payload.target || normalized.targetSeats?.[0] || null,
      sheriffFollowers: normalizeSeatList(payload.followers || normalized.actorSeats?.slice(1)),
      remark: safeString(payload.remark || normalized.content),
      quickTags: normalizeTagList(normalized.tags),
    }
  }

  if (scene === 'identity') {
    return {
      ...form,
      day: normalized.day,
      phase: normalized.phase,
      action: safeString(payload.action || payload.subtype) || 'claim_role',
      targetSeat: normalized.targetSeats?.[0] || normalized.actorSeats?.[0] || payload.targetSeat || payload.seat || null,
      roleName: safeString(payload.roleName || payload.role || payload.claimedRole),
      deathReason: safeString(payload.deathReason || payload.reason),
      remark: safeString(payload.remark || payload.rawText || normalized.content),
      quickTags: normalizeTagList(normalized.tags),
    }
  }

  return {
    ...form,
    day: normalized.day,
    phase: normalized.phase,
    subtype: safeString(payload.subtype || (normalized.type === 'wolfPack' ? 'wolf_pack' : 'general')) || 'general',
    targetSeats: normalizeSeatList(payload.targetSeats || normalized.targetSeats),
    suspicionLevel: payload.suspicionLevel ?? null,
    remark: safeString(payload.remark || payload.rawText || normalized.content),
    quickTags: normalizeTagList(normalized.tags),
  }
}

function buildBaseRecord(scene, form, context = {}) {
  const editingRecord = context.editingRecord || null
  const timestamp = nowIso()
  return {
    id: editingRecord?.id || `rec_${Date.now()}_${Math.random().toString(16).slice(2, 8)}`,
    scene,
    day: positive(form.day, positive(context.currentDay, 1)),
    round: positive(form.day, positive(context.currentDay, 1)),
    phase: safeString(form.phase) || phaseForScene(scene, safeString(context.currentPhase)),
    timestamp,
    createTime: editingRecord?.createTime || editingRecord?.timestamp || timestamp,
    updateTime: timestamp,
    editable: true,
  }
}

function buildNightRecord(form, context = {}) {
  const base = buildBaseRecord('night', form, context)
  const actorSeats = form.actorSeat ? [positive(form.actorSeat, 0)] : []
  const targetSeats = form.targetSeat ? [positive(form.targetSeat, 0)] : []
  const payload = {
    subtype: form.subtype,
    actorSeat: actorSeats[0] || null,
    targetSeat: targetSeats[0] || null,
    result: safeString(form.result),
    remark: safeString(form.remark),
  }

  let content = '夜间备注'
  if (form.subtype === 'seer_check') {
    content = `${actorSeats[0] || ''}号查验${targetSeats[0] || ''}号${payload.result ? `，结果${payload.result}` : ''}`
  } else if (form.subtype === 'wolf_kill') {
    content = `夜间刀口指向${targetSeats[0] || ''}号`
  } else if (form.subtype === 'witch_poison') {
    content = `${actorSeats[0] || ''}号女巫毒杀${targetSeats[0] || ''}号`
  } else if (form.subtype === 'guard_protect') {
    content = `${actorSeats[0] || ''}号守卫守护${targetSeats[0] || ''}号`
  } else if (form.subtype === 'night_note') {
    content = safeString(form.remark) || '夜间备注'
  }
  if (payload.remark && form.subtype !== 'night_note') {
    content += `；备注：${payload.remark}`
  }

  return normalizeRecord({
    ...base,
    type: 'nightAction',
    actorSeats,
    targetSeats,
    player: seatNosToText(actorSeats),
    content,
    payload,
    tags: normalizeTagList(['夜间', form.subtype === 'seer_check' ? '查验' : '', ...form.quickTags]),
  })
}

function buildSpeechRecord(form, context = {}) {
  const base = buildBaseRecord('speech', form, context)
  const actorSeats = form.actorSeat ? [positive(form.actorSeat, 0)] : []
  const targetSeats = normalizeSeatList(form.targetSeats)
  const payload = {
    actorSeat: actorSeats[0] || null,
    targetSeats,
    speechTags: normalizeTagList(form.speechTags),
    claimedRole: safeString(form.claimedRole),
    remark: safeString(form.remark),
  }

  const summary = payload.speechTags.length ? payload.speechTags.join('、') : '发言'
  const targetText = targetSeats.length ? `，点到${seatNosToText(targetSeats)}` : ''
  const roleText = payload.claimedRole ? `，跳${payload.claimedRole}` : ''
  const remarkText = payload.remark ? `；备注：${payload.remark}` : ''
  const content = `${actorSeats[0] || ''}号发言：${summary}${roleText}${targetText}${remarkText}`

  return normalizeRecord({
    ...base,
    type: 'speech',
    actorSeats,
    targetSeats,
    player: seatNosToText(actorSeats),
    content,
    payload,
    tags: normalizeTagList(['发言', ...payload.speechTags, ...form.quickTags]),
  })
}

function buildVoteRecord(form, context = {}) {
  const base = buildBaseRecord('vote', form, context)
  let actorSeats = []
  let targetSeats = []
  let content = '投票记录'
  const payload = {
    subtype: form.subtype,
    remark: safeString(form.remark),
  }

  if (form.subtype === 'normal') {
    actorSeats = normalizeSeatList(form.voters)
    targetSeats = form.targetSeat ? [positive(form.targetSeat, 0)] : []
    payload.voters = actorSeats
    payload.targetSeat = targetSeats[0] || null
    content = `${seatNosToText(actorSeats)} 投给 ${targetSeats[0] || ''}号`
  } else if (form.subtype === 'tie') {
    targetSeats = normalizeSeatList(form.tieTargets)
    payload.tieTargets = targetSeats
    content = `形成平票：${seatNosToText(targetSeats)}`
  } else if (form.subtype === 'abstain') {
    actorSeats = normalizeSeatList(form.abstainSeats)
    payload.abstainSeats = actorSeats
    content = `${seatNosToText(actorSeats)} 弃票`
  } else if (form.subtype === 'sheriff') {
    actorSeats = normalizeSeatList([form.sheriffSeat, ...(form.sheriffFollowers || [])])
    targetSeats = form.sheriffTargetSeat ? [positive(form.sheriffTargetSeat, 0)] : []
    payload.sheriffSeat = positive(form.sheriffSeat, 0) || null
    payload.sheriffTargetSeat = targetSeats[0] || null
    payload.followers = normalizeSeatList(form.sheriffFollowers)
    content = `警长${payload.sheriffSeat || ''}号归票${payload.sheriffTargetSeat || ''}号`
    if (payload.followers.length) {
      content += `，${seatNosToText(payload.followers)}跟票`
    }
  }

  if (payload.remark) {
    content += `；备注：${payload.remark}`
  }

  return normalizeRecord({
    ...base,
    type: 'vote',
    actorSeats,
    targetSeats,
    player: seatNosToText(actorSeats),
    content,
    payload,
    tags: normalizeTagList(['投票', form.subtype === 'tie' ? '平票' : '', form.subtype === 'abstain' ? '弃票' : '', form.subtype === 'sheriff' ? '警长归票' : '', ...form.quickTags]),
  })
}

function buildIdentityRecord(form, context = {}) {
  const base = buildBaseRecord('identity', form, context)
  const targetSeats = form.targetSeat ? [positive(form.targetSeat, 0)] : []
  const payload = {
    action: form.action,
    targetSeat: targetSeats[0] || null,
    roleName: safeString(form.roleName),
    deathReason: safeString(form.deathReason),
    remark: safeString(form.remark),
  }

  let content = '身份记录'
  if (form.action === 'claim_role') {
    content = `${targetSeats[0] || ''}号跳${payload.roleName || '身份'}`
  } else if (form.action === 'set_sheriff') {
    content = `${targetSeats[0] || ''}号设为警长`
  } else if (form.action === 'remove_sheriff') {
    content = `${targetSeats[0] || ''}号取消警长`
  } else if (form.action === 'mark_dead') {
    content = `${targetSeats[0] || ''}号出局（${payload.deathReason || '未知原因'}）`
  } else if (form.action === 'revive') {
    content = `${targetSeats[0] || ''}号恢复存活`
  } else if (form.action === 'set_real_role') {
    content = `${targetSeats[0] || ''}号真身份为${payload.roleName || '未填写'}`
  }
  if (payload.remark) {
    content += `；备注：${payload.remark}`
  }

  return normalizeRecord({
    ...base,
    type: 'identity',
    actorSeats: targetSeats.slice(),
    targetSeats,
    player: seatNosToText(targetSeats),
    content,
    payload,
    tags: normalizeTagList(['身份', form.action === 'set_sheriff' ? '警长' : '', form.action === 'mark_dead' ? '出局' : '', ...form.quickTags]),
  })
}

function buildNoteRecord(form, context = {}) {
  const base = buildBaseRecord('note', form, context)
  const targetSeats = normalizeSeatList(form.targetSeats)
  const payload = {
    subtype: form.subtype,
    targetSeats,
    suspicionLevel: form.suspicionLevel ?? null,
    remark: safeString(form.remark),
  }
  let content = payload.remark || '备注'
  if (form.subtype === 'player_note') {
    content = `关于${seatNosToText(targetSeats)}：${payload.remark || '玩家备注'}`
  } else if (form.subtype === 'wolf_pack') {
    content = `狼坑：${seatNosToText(targetSeats)}`
    if (payload.remark) {
      content += `；备注：${payload.remark}`
    }
  } else if (form.subtype === 'turning_point') {
    content = `关键转折：${payload.remark || '未填写'}`
  }

  return normalizeRecord({
    ...base,
    type: 'note',
    actorSeats: [],
    targetSeats,
    player: targetSeats.length ? seatNosToText(targetSeats) : '',
    content,
    payload,
    tags: normalizeTagList(['备注', form.subtype === 'wolf_pack' ? '狼坑' : '', form.subtype === 'turning_point' ? '关键转折' : '', ...form.quickTags]),
  })
}

export function buildRecordFromForm(scene, form, context = {}) {
  if (scene === 'night') return buildNightRecord(form, context)
  if (scene === 'speech') return buildSpeechRecord(form, context)
  if (scene === 'vote') return buildVoteRecord(form, context)
  if (scene === 'identity') return buildIdentityRecord(form, context)
  return buildNoteRecord(form, context)
}

export function inferSceneFromRecord(record) {
  return resolveSceneForRecord(record)
}

export function inferRoleNameFromText(text) {
  const content = safeString(text)
  return COMMON_ROLE_OPTIONS.find((item) => content.includes(item)) || ''
}

export function inferSeatsFromContent(text) {
  return extractSeatNosFromText(text)
}
