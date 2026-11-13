// 房主手机播报：用微信同声传译插件 WechatSI 把台词合成语音逐句播放；插件不可用时 supported = false，页面不出声。
export function createJudgeVoicePlayer() {
  const canLoadPlugin = typeof requirePlugin === 'function'
  const canCreateAudio = typeof uni !== 'undefined'
    && typeof uni.createInnerAudioContext === 'function'
  let plugin = null

  if (canLoadPlugin) {
    try {
      plugin = requirePlugin('WechatSI')
    } catch (error) {
      console.warn('WechatSI plugin unavailable, judge voice will be disabled', error)
    }
  }

  const supported = !!plugin?.textToSpeech && canCreateAudio
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
            console.warn('WechatSI textToSpeech 没有返回语音文件', res)
            reject(new Error('语音合成失败'))
            return
          }
          resolve(filePath)
        },
        fail(res) {
          // 开发者工具的 Console 里能看到插件原始的错误（retcode / msg），方便判断是不是插件没授权
          console.warn('WechatSI textToSpeech 失败', res)
          reject(new Error(res?.msg ? `语音合成失败：${res.msg}` : '语音合成失败'))
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
      const handleError = (error) => {
        audio.offEnded?.(handleEnded)
        audio.offError?.(handleError)
        console.warn('播报音频播放失败', error)
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
