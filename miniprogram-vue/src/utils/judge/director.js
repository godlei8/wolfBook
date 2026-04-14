const NIGHT_STEP_DEFINITIONS = [
  {
    key: 'wolves',
    label: '狼人',
    durationSeconds: 45,
    match(player) {
      const roleName = String(player?.roleName || '')
      const faction = String(player?.faction || '').toLowerCase()
      return faction.includes('wolf')
        || ['狼人', '狼王', '狼美人', '白狼王', '恶灵骑士'].some((keyword) => roleName.includes(keyword))
    },
    buildIntro(step) {
      return `${step.label}请睁眼，${seatLine(step.seatNos)}请确认今晚击杀目标，你们有${step.durationSeconds}秒行动时间。`
    },
    outro: '狼人请闭眼。',
  },
  {
    key: 'nightmare',
    label: '梦魇',
    durationSeconds: 20,
    match: (player) => String(player?.roleName || '').includes('梦魇'),
    buildIntro(step) {
      return `${step.label}请睁眼，${seatLine(step.seatNos)}请选择你要恐惧的玩家，你有${step.durationSeconds}秒行动时间。`
    },
    outro: '梦魇请闭眼。',
  },
  {
    key: 'gargoyle',
    label: '石像鬼',
    durationSeconds: 20,
    match: (player) => String(player?.roleName || '').includes('石像鬼'),
    buildIntro(step) {
      return `${step.label}请睁眼，${seatLine(step.seatNos)}请选择你要查验的玩家，你有${step.durationSeconds}秒行动时间。`
    },
    outro: '石像鬼请闭眼。',
  },
  {
    key: 'seer',
    label: '预言家',
    durationSeconds: 20,
    match: (player) => String(player?.roleName || '').includes('预言家'),
    buildIntro(step) {
      return `${step.label}请睁眼，${seatLine(step.seatNos)}请选择你要查验的玩家，你有${step.durationSeconds}秒行动时间。`
    },
    outro: '预言家请闭眼。',
  },
  {
    key: 'guard',
    label: '守卫',
    durationSeconds: 15,
    match: (player) => String(player?.roleName || '').includes('守卫'),
    buildIntro(step) {
      return `${step.label}请睁眼，${seatLine(step.seatNos)}请选择你要守护的玩家，你有${step.durationSeconds}秒行动时间。`
    },
    outro: '守卫请闭眼。',
  },
  {
    key: 'witch',
    label: '女巫',
    durationSeconds: 20,
    match: (player) => String(player?.roleName || '').includes('女巫'),
    buildIntro(step) {
      return `${step.label}请睁眼，${seatLine(step.seatNos)}请确认今晚信息，并决定是否使用解药或毒药，你有${step.durationSeconds}秒行动时间。`
    },
    outro: '女巫请闭眼。',
  },
  {
    key: 'dreamer',
    label: '摄梦人',
    durationSeconds: 20,
    match: (player) => String(player?.roleName || '').includes('摄梦'),
    buildIntro(step) {
      return `${step.label}请睁眼，${seatLine(step.seatNos)}请选择你今晚要摄梦的玩家，你有${step.durationSeconds}秒行动时间。`
    },
    outro: '摄梦人请闭眼。',
  },
  {
    key: 'magician',
    label: '魔术师',
    durationSeconds: 20,
    match: (player) => String(player?.roleName || '').includes('魔术师'),
    buildIntro(step) {
      return `${step.label}请睁眼，${seatLine(step.seatNos)}请选择需要交换的玩家，你有${step.durationSeconds}秒行动时间。`
    },
    outro: '魔术师请闭眼。',
  },
]

function seatLine(seatNos) {
  if (!Array.isArray(seatNos) || !seatNos.length) {
    return '对应角色玩家'
  }
  return seatNos.map((seatNo) => `${seatNo}号`).join('、')
}

function normalizeSubmittedSeatNos(nightActions) {
  const submitted = new Set()
  for (const action of Array.isArray(nightActions) ? nightActions : []) {
    const seatNo = Number(action?.actorSeatNo)
    if (Number.isFinite(seatNo) && seatNo > 0) {
      submitted.add(seatNo)
    }
  }
  return submitted
}

export function buildJudgeNightDirector(snapshot, durationOverrides = {}) {
  if (!snapshot?.judgeViewer || snapshot?.roomStatus !== 'playing' || snapshot?.currentPhase !== 'night_action') {
    return {
      key: '',
      day: 0,
      steps: [],
    }
  }

  const alivePlayers = Array.isArray(snapshot?.players)
    ? snapshot.players.filter((player) => player?.playing && player?.alive)
    : []
  const submittedSeatSet = normalizeSubmittedSeatNos(snapshot?.nightActions)
  const steps = NIGHT_STEP_DEFINITIONS
    .map((definition) => {
      const players = alivePlayers.filter((player) => definition.match(player))
      if (!players.length) {
        return null
      }
      const seatNos = players
        .map((player) => Number(player?.seatNo))
        .filter((seatNo) => Number.isFinite(seatNo) && seatNo > 0)
        .sort((left, right) => left - right)
      const durationSeconds = Math.max(5, Number(durationOverrides?.[definition.key]) || definition.durationSeconds)
      const submittedSeatNos = seatNos.filter((seatNo) => submittedSeatSet.has(seatNo))
      const step = {
        key: definition.key,
        label: definition.label,
        durationSeconds,
        seatNos,
        submittedSeatNos,
        submittedCount: submittedSeatNos.length,
        totalCount: seatNos.length,
        allSubmitted: seatNos.length > 0 && submittedSeatNos.length >= seatNos.length,
        introLine: '',
        outroLine: definition.outro,
      }
      step.introLine = definition.buildIntro(step)
      return step
    })
    .filter(Boolean)

  return {
    key: `${snapshot?.roomId || 'room'}:${snapshot?.currentDay || 1}:night`,
    day: snapshot?.currentDay || 1,
    steps,
  }
}
