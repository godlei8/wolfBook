const NIGHT_ROLE_STEPS = [
  {
    key: 'wolves',
    match(player) {
      const roleName = String(player?.roleName || '')
      const faction = String(player?.faction || '').toLowerCase()
      return faction.includes('wolf')
        || ['狼人', '狼王', '狼美人', '白狼王', '恶灵骑士'].some((keyword) => roleName.includes(keyword))
    },
    line: '狼人请睁眼，确认今晚的袭击目标。狼人请闭眼。',
  },
  {
    key: 'nightmare',
    match: (player) => String(player?.roleName || '').includes('梦魇'),
    line: '梦魇请睁眼，选择你要恐惧的玩家。梦魇请闭眼。',
  },
  {
    key: 'gargoyle',
    match: (player) => String(player?.roleName || '').includes('石像鬼'),
    line: '石像鬼请睁眼，选择你要查验的玩家。石像鬼请闭眼。',
  },
  {
    key: 'seer',
    match: (player) => String(player?.roleName || '').includes('预言家'),
    line: '预言家请睁眼，选择你要查验的玩家。预言家请闭眼。',
  },
  {
    key: 'guard',
    match: (player) => String(player?.roleName || '').includes('守卫'),
    line: '守卫请睁眼，选择你今晚要守护的玩家。守卫请闭眼。',
  },
  {
    key: 'witch',
    match: (player) => String(player?.roleName || '').includes('女巫'),
    line: '女巫请睁眼，请确认今晚的情况，并决定是否使用解药或毒药。女巫请闭眼。',
  },
  {
    key: 'dreamer',
    match: (player) => String(player?.roleName || '').includes('摄梦'),
    line: '摄梦人请睁眼，选择你今晚要梦游的玩家。摄梦人请闭眼。',
  },
  {
    key: 'magician',
    match: (player) => String(player?.roleName || '').includes('魔术师'),
    line: '魔术师请睁眼，选择需要交换的玩家。魔术师请闭眼。',
  },
]

function buildNightLines(snapshot) {
  const alivePlayers = Array.isArray(snapshot?.players)
    ? snapshot.players.filter((player) => player?.playing && player?.alive)
    : []
  const roleLines = NIGHT_ROLE_STEPS
    .filter((step) => alivePlayers.some((player) => step.match(player)))
    .map((step) => step.line)

  return [
    `现在进入第${snapshot?.currentDay || 1}天夜晚。天黑请闭眼。`,
    ...(roleLines.length ? roleLines : ['请有夜间技能的角色依次睁眼并完成操作。']),
    '夜晚流程播报结束，请法官确认玩家操作。',
  ]
}

function buildDayLines(snapshot) {
  return [
    `现在进入第${snapshot?.currentDay || 1}天白天。`,
    snapshot?.latestAnnouncement || '天亮了，请法官公布昨夜结果。',
    '请存活玩家按顺序开始发言。',
  ]
}

function buildVoteLines(snapshot) {
  return [
    `现在进入第${snapshot?.currentDay || 1}天放逐投票阶段。`,
    snapshot?.latestAnnouncement || '请存活玩家同时进行放逐投票。',
    '投票完成后，请法官进行结算。',
  ]
}

function buildResultLines(snapshot) {
  const winnerCamp = snapshot?.winnerCamp ? `${snapshot.winnerCamp}胜利。` : '本局对局已结束。'
  return [
    winnerCamp,
    snapshot?.latestAnnouncement || '请法官宣布最终结果。',
  ]
}

export function buildJudgeVoiceScript(snapshot) {
  if (!snapshot?.judgeViewer || snapshot?.roomStatus !== 'playing') {
    return {
      key: '',
      title: '法官语音',
      summary: '当前阶段没有可自动播报的法官语音。',
      lines: [],
    }
  }

  const phase = snapshot?.currentPhase || 'lobby'
  const baseKey = `${snapshot?.roomId || 'room'}:${snapshot?.currentDay || 1}:${phase}`

  if (phase === 'night_action') {
    return {
      key: `${baseKey}:night`,
      title: '夜晚语音',
      summary: '会按当前房间仍存活的夜间角色依次播报睁眼和技能提示。',
      lines: buildNightLines(snapshot),
    }
  }

  if (phase === 'day_speech') {
    return {
      key: `${baseKey}:day`,
      title: '白天语音',
      summary: '会播报白天结果和发言阶段提醒。',
      lines: buildDayLines(snapshot),
    }
  }

  if (phase === 'exile_vote') {
    return {
      key: `${baseKey}:vote`,
      title: '投票语音',
      summary: '会播报放逐投票提醒，方便法官统一控场。',
      lines: buildVoteLines(snapshot),
    }
  }

  if (phase === 'result') {
    return {
      key: `${baseKey}:result`,
      title: '结算语音',
      summary: '会播报胜负结果和本局结束提示。',
      lines: buildResultLines(snapshot),
    }
  }

  return {
    key: baseKey,
    title: '法官语音',
    summary: '当前阶段没有可自动播报的法官语音。',
    lines: [],
  }
}

export function createJudgeVoicePlayer() {
  const supported = typeof requirePlugin === 'function'
    && typeof uni !== 'undefined'
    && typeof uni.createInnerAudioContext === 'function'
  const plugin = supported ? requirePlugin('WechatSI') : null
  const audio = supported ? uni.createInnerAudioContext() : null
  let playToken = 0

  function textToSpeech(text) {
    return new Promise((resolve, reject) => {
      if (!plugin?.textToSpeech) {
        reject(new Error('当前环境不支持法官语音'))
        return
      }
      plugin.textToSpeech({
        lang: 'zh_CN',
        tts: true,
        content: text,
        success(res) {
          const filePath = res?.filename || res?.filePath || res?.tempFilePath || ''
          if (!filePath) {
            reject(new Error('语音合成失败'))
            return
          }
          resolve(filePath)
        },
        fail() {
          reject(new Error('语音合成失败'))
        },
      })
    })
  }

  function playFile(filePath) {
    return new Promise((resolve, reject) => {
      if (!audio) {
        reject(new Error('当前环境不支持法官语音'))
        return
      }

      const handleEnded = () => {
        audio.offEnded?.(handleEnded)
        audio.offError?.(handleError)
        resolve()
      }
      const handleError = () => {
        audio.offEnded?.(handleEnded)
        audio.offError?.(handleError)
        reject(new Error('语音播放失败'))
      }

      audio.offEnded?.(handleEnded)
      audio.offError?.(handleError)
      audio.onEnded(handleEnded)
      audio.onError(handleError)
      audio.src = filePath
      audio.play()
    })
  }

  return {
    supported,
    async playScript(script, handlers = {}) {
      if (!supported) {
        throw new Error('当前环境不支持法官语音')
      }
      const currentToken = ++playToken
      const lines = Array.isArray(script?.lines) ? script.lines.filter(Boolean) : []
      for (let index = 0; index < lines.length; index += 1) {
        if (currentToken !== playToken) {
          return false
        }
        const line = lines[index]
        handlers.onLineStart?.(line, index)
        const filePath = await textToSpeech(line)
        if (currentToken !== playToken) {
          return false
        }
        await playFile(filePath)
      }
      handlers.onFinish?.()
      return true
    },
    stop() {
      playToken += 1
      audio?.stop()
    },
    destroy() {
      playToken += 1
      audio?.stop()
      audio?.destroy?.()
    },
  }
}
