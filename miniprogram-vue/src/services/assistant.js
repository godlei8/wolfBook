import { BASE_URL } from './config'
import { clearAuthState, createAuthHeader, request } from './request'

function decodeChunkData(data) {
  if (!data) return ''
  if (typeof data === 'string') return data

  let buffer = null
  if (data instanceof ArrayBuffer) {
    buffer = data
  } else if (data.buffer instanceof ArrayBuffer) {
    buffer = data.buffer
  }

  if (!buffer) return ''

  const uint8Array = new Uint8Array(buffer)
  if (typeof TextDecoder !== 'undefined') {
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

  const rawData = dataLines.join('\n')
  if (!rawData) {
    return null
  }

  try {
    return { event, data: JSON.parse(rawData) }
  } catch (error) {
    return { event, data: rawData }
  }
}

function supportsStreamingRequest() {
  return typeof wx !== 'undefined' && typeof uni.request === 'function'
}

function shouldClearAuthFromMessage(message) {
  if (!message || typeof message !== 'string') {
    return false
  }
  return /token|authorization|auth|登录/i.test(message)
}

function fallbackAsk(payload, handlers = {}) {
  return request({
    url: '/api/assistant/ask',
    method: 'POST',
    data: payload,
    header: createAuthHeader(),
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

function streamAsk(payload, handlers = {}) {
  return new Promise((resolve, reject) => {
    let eventBuffer = ''
    let finalResponse = null
    let settled = false
    let requestTask = null

    function resolveOnce(value) {
      if (settled) return
      settled = true
      resolve(value)
    }

    function rejectOnce(error) {
      if (settled) return
      settled = true
      reject(error)
    }

    function fallbackOnce() {
      if (settled) return
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

    requestTask = uni.request({
      url: `${BASE_URL}/api/assistant/ask/stream`,
      method: 'POST',
      data: payload,
      enableChunked: true,
      responseType: 'arraybuffer',
      header: {
        Accept: 'text/event-stream',
        ...createAuthHeader(),
      },
      success: (response) => {
        if (settled) return
        const rawChunkText = decodeChunkData(response.data)

        if (shouldFallbackToNonStreaming(response, rawChunkText)) {
          fallbackOnce()
          return
        }

        if (rawChunkText) {
          eventBuffer += rawChunkText
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
      eventBuffer += decodeChunkData(chunk.data)
      consumeEvents(false)
    })
  })
}

export default {
  async bootstrap() {
    return request({ url: '/api/assistant/bootstrap', header: createAuthHeader() })
  },
  async getSessions() {
    return request({ url: '/api/assistant/sessions', header: createAuthHeader() })
  },
  async getMessages(sessionId) {
    return request({ url: `/api/assistant/sessions/${sessionId}/messages`, header: createAuthHeader() })
  },
  async ask(payload) {
    return request({
      url: '/api/assistant/ask',
      method: 'POST',
      data: payload,
      header: createAuthHeader(),
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
      header: createAuthHeader(),
    })
  },
}
