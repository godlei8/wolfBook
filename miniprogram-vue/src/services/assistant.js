import { BASE_URL } from './config'
import { request } from './request'
import storage from './storage'

function authHeader() {
  const token = storage.getAuthToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

function decodeChunkData(data, decoder, stream = false) {
  if (!data) return ''
  if (typeof data === 'string') return data

  let uint8Array = null
  if (data instanceof ArrayBuffer) {
    uint8Array = new Uint8Array(data)
  } else if (typeof ArrayBuffer !== 'undefined' && typeof ArrayBuffer.isView === 'function' && ArrayBuffer.isView(data)) {
    uint8Array = new Uint8Array(data.buffer, data.byteOffset || 0, data.byteLength || data.length || 0)
  } else if (data.buffer instanceof ArrayBuffer) {
    const byteOffset = Number.isFinite(data.byteOffset) ? data.byteOffset : 0
    const byteLength = Number.isFinite(data.byteLength)
      ? data.byteLength
      : Number.isFinite(data.length)
        ? data.length
        : data.buffer.byteLength - byteOffset
    uint8Array = new Uint8Array(data.buffer, byteOffset, byteLength)
  }

  if (!uint8Array) return ''
  if (typeof TextDecoder !== 'undefined') {
    if (decoder) {
      return decoder.decode(uint8Array, { stream })
    }
    return new TextDecoder('utf-8').decode(uint8Array)
  }

  let result = ''
  for (let index = 0; index < uint8Array.length; index += 1) {
    result += String.fromCharCode(uint8Array[index])
  }

  try {
    return decodeURIComponent(escape(result))
  } catch (error) {
    return result
  }
}

function parseEventBlock(block) {
  const lines = block.replace(/\r/g, '').split('\n')
  let event = 'message'
  const dataLines = []

  lines.forEach((line) => {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
      return
    }
    if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart())
    }
  })

  const rawData = dataLines.join('\n').replace(/\u0000/g, '')
  if (!rawData) {
    return null
  }

  try {
    return { event, data: JSON.parse(rawData) }
  } catch (error) {
    if (event === 'delta') {
      return { event, data: { delta: rawData } }
    }
    return { event, data: rawData }
  }
}

function supportsStreamingRequest() {
  return (
    (typeof wx !== 'undefined' && typeof wx.request === 'function')
    || (typeof uni !== 'undefined' && typeof uni.request === 'function')
  )
}

function resolveStreamingRequestApi() {
  if (typeof wx !== 'undefined' && typeof wx.request === 'function') {
    return wx
  }
  return uni
}

function shouldClearAuthFromMessage(message) {
  if (!message || typeof message !== 'string') {
    return false
  }
  return /token|authorization|auth|登录/i.test(message)
}

function clearAuthState() {
  storage.clearAuthToken()
  storage.setUserProfile(null)
}

function fallbackAsk(payload, handlers = {}) {
  return request({
    url: '/api/assistant/ask',
    method: 'POST',
    data: payload,
    header: authHeader(),
  }).then((response) => {
    handlers.onStarted?.({
      sessionId: response.sessionId,
      traceId: response.traceId,
      contentFormat: response.contentFormat || 'MARKDOWN',
    })
    handlers.onDelta?.({ delta: response.answer || '' })
    handlers.onDone?.(response)
    return response
  })
}

function shouldFallbackToNonStreaming(response, rawChunkText) {
  if (!response) return false
  if ([404, 405, 406, 415, 500, 501].includes(response.statusCode)) {
    return true
  }
  if (response.statusCode !== 200) {
    return false
  }
  if (!rawChunkText) {
    return false
  }
  return !rawChunkText.includes('event:') && !rawChunkText.includes('data:')
}

function findTextOverlap(left = '', right = '') {
  const maxLength = Math.min(left.length, right.length)
  for (let length = maxLength; length > 0; length -= 1) {
    if (left.slice(left.length - length) === right.slice(0, length)) {
      return length
    }
  }
  return 0
}

export function appendNonOverlappingText(base = '', next = '') {
  if (!next) return base || ''
  if (!base) return next
  if (next.startsWith(base)) return next
  if (base.endsWith(next)) return base
  const overlap = findTextOverlap(base, next)
  return `${base}${next.slice(overlap)}`
}

function streamAsk(payload, handlers = {}) {
  return new Promise((resolve, reject) => {
    let eventBuffer = ''
    let finalResponse = null
    let settled = false
    let requestTask = null
    let streamWatchdog = null
    let receivedChunkData = false
    const textDecoder = typeof TextDecoder !== 'undefined' ? new TextDecoder('utf-8') : null
    const requestApi = resolveStreamingRequestApi()

    function clearWatchdog() {
      if (streamWatchdog) {
        clearTimeout(streamWatchdog)
        streamWatchdog = null
      }
    }

    function scheduleWatchdog() {
      clearWatchdog()
      streamWatchdog = setTimeout(() => {
        fallbackOnce()
      }, 12000)
    }

    function resolveOnce(value) {
      if (settled) return
      clearWatchdog()
      settled = true
      resolve(value)
    }

    function rejectOnce(error) {
      if (settled) return
      clearWatchdog()
      settled = true
      reject(error)
    }

    function fallbackOnce() {
      if (settled) return
      clearWatchdog()
      if (requestTask && typeof requestTask.abort === 'function') {
        requestTask.abort()
      }
      fallbackAsk(payload, handlers).then(resolveOnce).catch(rejectOnce)
    }

    function consumeEvents(flushAll) {
      const normalized = eventBuffer.replace(/\r/g, '')
      const blocks = normalized.split('\n\n')
      eventBuffer = flushAll ? '' : blocks.pop() || ''
      const readyBlocks = flushAll ? blocks.filter(Boolean) : blocks

      readyBlocks.forEach((block) => {
        const parsed = parseEventBlock(block)
        if (!parsed) return

        if (parsed.event === 'started') {
          handlers.onStarted?.(parsed.data)
          return
        }

        if (parsed.event === 'delta') {
          handlers.onDelta?.(parsed.data)
          return
        }

        if (parsed.event === 'done') {
          if (finalResponse) return
          finalResponse = parsed.data
          handlers.onDone?.(parsed.data)
          return
        }

        if (parsed.event === 'error') {
          const message = parsed.data?.message || 'stream response failed'
          if (shouldClearAuthFromMessage(message)) {
            clearAuthState()
          }
          rejectOnce(new Error(message))
        }
      })
    }

    scheduleWatchdog()

    requestTask = requestApi.request({
      url: `${BASE_URL}/api/assistant/ask/stream`,
      method: 'POST',
      data: payload,
      enableChunked: true,
      responseType: 'arraybuffer',
      header: {
        Accept: 'text/event-stream',
        ...authHeader(),
      },
      success: (response) => {
        if (settled) return
        clearWatchdog()
        const rawChunkText = receivedChunkData ? '' : decodeChunkData(response.data, textDecoder, false)
        const flushText = textDecoder ? textDecoder.decode() : ''

        if (shouldFallbackToNonStreaming(response, rawChunkText)) {
          fallbackOnce()
          return
        }

        if (rawChunkText) {
          eventBuffer += rawChunkText
        }
        if (flushText) {
          eventBuffer += flushText
        }
        if (rawChunkText || flushText || eventBuffer) {
          consumeEvents(true)
        }

        if (response.statusCode !== 200) {
          rejectOnce(new Error(`Request failed: ${response.statusCode}`))
          return
        }

        if (finalResponse) {
          resolveOnce(finalResponse)
          return
        }

        fallbackOnce()
      },
      fail: (error) => {
        if (settled) return
        clearWatchdog()
        const message = error?.errMsg || ''
        if (message.includes('404') || message.includes('fail')) {
          fallbackOnce()
          return
        }
        rejectOnce(error)
      },
    })

    if (!requestTask || typeof requestTask.onChunkReceived !== 'function') {
      fallbackOnce()
      return
    }

    requestTask.onChunkReceived((chunk) => {
      if (settled) return
      scheduleWatchdog()
      const chunkText = decodeChunkData(chunk.data, textDecoder, true)
      if (!chunkText) return
      receivedChunkData = true
      eventBuffer += chunkText
      consumeEvents(false)
    })
  })
}

export default {
  async bootstrap() {
    return request({ url: '/api/assistant/bootstrap', header: authHeader() })
  },
  async getSessions() {
    return request({ url: '/api/assistant/sessions', header: authHeader() })
  },
  async getMessages(sessionId) {
    return request({ url: `/api/assistant/sessions/${sessionId}/messages`, header: authHeader() })
  },
  async ask(payload) {
    return request({
      url: '/api/assistant/ask',
      method: 'POST',
      data: payload,
      header: authHeader(),
    })
  },
  async askStream(payload, handlers = {}) {
    if (!supportsStreamingRequest()) {
      return fallbackAsk(payload, handlers)
    }
    return streamAsk(payload, handlers)
  },
  async resetSession(sessionId) {
    return request({
      url: `/api/assistant/sessions/${sessionId}/reset`,
      method: 'POST',
      header: authHeader(),
    })
  },
}
