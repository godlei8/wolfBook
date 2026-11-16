import test from 'node:test'
import assert from 'node:assert/strict'
import { BGM_DUCKED, BGM_VOLUME, VOICE_VOLUME, createBgmPlayer, nightBgm } from '../src/utils/judge/bgm.js'

const URL = 'https://example.test/night.mp3'
const night = (extra = {}) => ({ host: true, roomStatus: 'playing', currentPhase: 'night_action', paused: false, ...extra })

test('theMusicOnlyPlaysOnTheHostsPhoneAtNight', () => {
  // 用户给定：背景音乐和播报一样，只在房主 / 法官的手机上放；只有夜里放
  assert.deepEqual(nightBgm(night(), { url: URL }), { play: true, volume: BGM_VOLUME })
  assert.equal(nightBgm(night({ host: false }), { url: URL }).play, false, '别的玩家手机不放')
  assert.equal(nightBgm(night({ currentPhase: 'day_speech' }), { url: URL }).play, false, '白天不放')
  assert.equal(nightBgm(night({ currentPhase: 'skill_pending' }), { url: URL }).play, false)
  assert.equal(nightBgm(night({ roomStatus: 'lobby' }), { url: URL }).play, false)
  assert.equal(nightBgm(night({ roomStatus: 'finished' }), { url: URL }).play, false)
  assert.equal(nightBgm(night({ paused: true }), { url: URL }).play, false, '暂停时不放')
  assert.equal(nightBgm(night(), { url: URL, on: false }).play, false, '声音关了不放')
  assert.equal(nightBgm(night(), { url: '' }).play, false, '没配音乐地址就没有这个功能')
  assert.equal(nightBgm(null, { url: URL }).play, false)
})

test('theMusicStepsBackWhileTheHostIsAnnouncing', () => {
  // 播报的时候音乐压低，免得盖住「狼人请睁眼」
  assert.deepEqual(nightBgm(night(), { url: URL, speaking: true }), { play: true, volume: BGM_DUCKED })
  assert.ok(BGM_DUCKED < BGM_VOLUME)
  // 用户给定：背景音乐的声音要低于播报——平时不到播报的一半，播报时不到十分之一
  assert.ok(BGM_VOLUME <= VOICE_VOLUME * 0.4)
  assert.ok(BGM_DUCKED <= VOICE_VOLUME * 0.1)
  assert.equal(nightBgm(night({ currentPhase: 'day_speech' }), { url: URL, speaking: true }).volume, 0)
})

// 假的音频和定时器：记下播放器做了什么
function rig() {
  const log = []
  const audio = {
    src: '', loop: false, volume: 1,
    play: () => log.push('play'),
    stop: () => log.push('stop'),
    destroy: () => log.push('destroy'),
    onError(handler) { audio.fail = handler },
  }
  let created = 0
  let tick = null
  const player = createBgmPlayer({
    url: URL,
    createAudio: () => { created += 1; return audio },
    setTimer: (fn) => { tick = fn; return 1 },
    clearTimer: () => { tick = null },
  })
  // 把正在进行的渐变走完
  const settle = () => { for (let i = 0; i < 20 && tick; i += 1) tick() }
  return { log, audio, player, settle, created: () => created, fading: () => Boolean(tick) }
}

test('thePlayerStartsOnceLoopsAndFadesIn', () => {
  const { log, audio, player, settle, created } = rig()
  player.sync({ play: false, volume: 0 })
  assert.deepEqual([created(), log], [0, []], '白天什么都不做，连音频都不建')

  player.sync({ play: true, volume: BGM_VOLUME })
  assert.deepEqual([audio.src, audio.loop, log], [URL, true, ['play']], '循环播放')
  assert.equal(audio.volume, 0, '从没声开始渐入')
  settle()
  assert.equal(audio.volume, BGM_VOLUME)

  player.sync({ play: true, volume: BGM_VOLUME })
  player.sync({ play: true, volume: BGM_VOLUME })
  assert.deepEqual([created(), log], [1, ['play']], '每秒都会来问一次，已经在放就不重新开始')
})

test('thePlayerDucksAndComesBack', () => {
  const { audio, player, settle } = rig()
  player.sync({ play: true, volume: BGM_VOLUME })
  settle()
  player.sync({ play: true, volume: BGM_DUCKED })
  settle()
  assert.equal(audio.volume, BGM_DUCKED)
  player.sync({ play: true, volume: BGM_VOLUME })
  settle()
  assert.equal(audio.volume, BGM_VOLUME)
})

test('thePlayerFadesOutAtDawnAndRestartsNextNight', () => {
  const { log, audio, player, settle } = rig()
  player.sync({ play: true, volume: BGM_VOLUME })
  settle()
  player.sync({ play: false, volume: 0 })
  assert.deepEqual(log, ['play'], '先渐出，不是一下子掐掉')
  settle()
  assert.deepEqual([log, audio.volume], [['play', 'stop'], 0])
  player.sync({ play: false, volume: 0 })
  assert.deepEqual(log, ['play', 'stop'], '已经停了就不再停')

  player.sync({ play: true, volume: BGM_VOLUME })
  assert.deepEqual(log, ['play', 'stop', 'play'], '下一夜从头再放')
})

test('aNightThatStartsAgainDuringTheFadeOutKeepsPlaying', () => {
  // 天亮渐出到一半又天黑了（比如警上自爆直接天黑）：接着放，不会被前一次的渐出停掉
  const { log, audio, player, settle } = rig()
  player.sync({ play: true, volume: BGM_VOLUME })
  settle()
  player.sync({ play: false, volume: 0 })
  player.sync({ play: true, volume: BGM_VOLUME })
  settle()
  assert.deepEqual([log, audio.volume], [['play'], BGM_VOLUME])
})

test('stopAndBrokenAudio', () => {
  const { log, audio, player, settle, fading } = rig()
  player.sync({ play: true, volume: BGM_VOLUME })
  player.stop()
  assert.deepEqual([log, fading()], [['play', 'stop'], false], '切后台：马上停，不留定时器')
  player.sync({ play: true, volume: BGM_VOLUME })
  settle()
  audio.fail({ errMsg: 'load fail' })
  player.sync({ play: true, volume: BGM_VOLUME })
  player.sync({ play: true, volume: BGM_VOLUME })
  assert.deepEqual(log, ['play', 'stop', 'play', 'stop'], '音乐放不出来（地址错了、没网）就停掉，不再反复重试')
  player.destroy()
  assert.equal(log.at(-1), 'destroy')

  const none = createBgmPlayer({ url: '', createAudio: () => { throw new Error('不该建音频') } })
  none.sync({ play: true, volume: BGM_VOLUME })
  none.stop()
  none.destroy()
})
