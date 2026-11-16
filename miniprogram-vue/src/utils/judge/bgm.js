// 夜晚背景音乐（有单测）：和播报一样只在房主 / 法官的手机上放，只有夜里放。
// nightBgm 是纯判断（现在该不该放、放多大声）；createBgmPlayer 是一层很薄的播放器：循环、渐入渐出、播报时压低。
// 音乐文件不打进小程序包，按地址播（services/config.js 的 NIGHT_BGM_URL，没配就没有这个功能）。
// 用户给定：背景音乐的声音要低于播报。播报按满音量（1）放，音乐平时只到这么大
export const VOICE_VOLUME = 1
export const BGM_VOLUME = 0.3
// 房主播报台词的时候再压到这么低，免得盖住「狼人请睁眼」
export const BGM_DUCKED = 0.08
const FADE_MS = 1200
const FADE_STEPS = 8

/**
 * 现在该不该放、放多大声。on 是房主的声音开关，speaking 是这台手机正在播报。
 * 只读快照里全场都一样的字段（阶段、暂停）和 host 标记，不看任何身份或夜间信息——
 * 音乐整夜一个样，不会因为轮到谁而变。
 */
export function nightBgm(snapshot, { on = true, url = '', speaking = false } = {}) {
  const play = Boolean(url) && on
    && Boolean(snapshot?.host)
    && snapshot.roomStatus === 'playing'
    && snapshot.currentPhase === 'night_action'
    && !snapshot.paused
  return { play, volume: play ? (speaking ? BGM_DUCKED : BGM_VOLUME) : 0 }
}

/**
 * 播放器。每秒把 nightBgm 的结果交给 sync：该放就循环播放并渐入，不该放就渐出再停，音量变了就滑过去。
 * createAudio 返回一个 uni.createInnerAudioContext()；setTimer / clearTimer 默认是 setInterval / clearInterval（测试里换掉）。
 */
export function createBgmPlayer({ url, createAudio, setTimer = setInterval, clearTimer = clearInterval }) {
  let audio = null
  let playing = false
  let broken = false
  let target = 0
  let level = 0
  let fade = null

  function ensure() {
    if (!audio) {
      audio = createAudio()
      audio.src = url
      audio.loop = true
      // 放不出来（地址错了、没网）：这次进房间就不再试了，不反复打扰
      audio.onError?.(() => {
        broken = true
        halt()
      })
    }
    return audio
  }

  function cancelFade() {
    if (fade !== null) clearTimer(fade)
    fade = null
  }

  function setLevel(value) {
    level = Math.round(value * 1000) / 1000
    if (audio) audio.volume = level
  }

  // 音量从现在滑到 to；滑完之后做 done
  function ramp(to, done) {
    cancelFade()
    const from = level
    let step = 0
    fade = setTimer(() => {
      step += 1
      setLevel(step >= FADE_STEPS ? to : from + ((to - from) * step) / FADE_STEPS)
      if (step >= FADE_STEPS) {
        cancelFade()
        done?.()
      }
    }, FADE_MS / FADE_STEPS)
  }

  // 马上停，下次从头放
  function halt() {
    cancelFade()
    if (audio && (playing || level > 0)) audio.stop()
    playing = false
    target = 0
    setLevel(0)
  }

  return {
    sync({ play, volume }) {
      if (!url || broken) return
      if (play && !playing) {
        // 天黑了。上一次的渐出还没走完也没关系：接着放，滑到该有的音量
        const fresh = level === 0
        ensure()
        playing = true
        target = volume
        if (fresh) {
          setLevel(0)
          audio.play()
        }
        ramp(volume)
      } else if (play && volume !== target) {
        target = volume
        ramp(volume)
      } else if (!play && playing) {
        // 天亮了：渐出，出完再停
        playing = false
        target = 0
        ramp(0, () => audio.stop())
      }
    },
    /** 切后台、关声音：马上停。 */
    stop() {
      halt()
    },
    destroy() {
      halt()
      audio?.destroy?.()
      audio = null
    },
  }
}
