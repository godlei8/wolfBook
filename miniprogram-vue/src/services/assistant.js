import { BASE_URL } from './config'
import { request } from './request'
import storage from './storage'

function authHeader() {
  const token = storage.getAuthToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

function unwrapApiResponse(payload, hasAuthorization = false) {
  if (!payload || payload.code !== 0) {
    if (payload?.code === 4001 && hasAuthorization) {
      storage.clearAuthToken()
      storage.setUserProfile(null)
    }
    throw new Error(payload?.msg || 'Request failed')
  }
  return payload.data
}

function normalizeHeaderValue(headers, key) {
  if (!headers) {
    return ''
  }
  const normalizedKey = key.toLowerCase()
  const match = Object.keys(headers).find((headerKey) => headerKey.toLowerCase() === normalizedKey)
  return match ? headers[match] : ''
}

function decodeChunk(data, decoder) {
  if (typeof data === 'string') {
    return data
  }
  if (decoder) {
    return decoder.decode(data, { stream: true })
  }
  const bytes = new Uint8Array(data)
  let encoded = ''
  for (let index = 0; index < bytes.length; index += 1) {
    encoded += `%${bytes[index].toString(16).padStart(2, '0')}`
  }
  try {
    return decodeURIComponent(encoded)
  } catch (error) {
    return ''
  }
}

function createSseParser(handlers) {
  let buffer = ''

  function dispatchBlock(block) {
    if (!block.trim()) {
      return null
    }
    let eventName = 'message'
    const dataLines = []
    block.split('\n').forEach((line) => {
      if (!line || line.startsWith(':')) {
        return
      }
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
        return
      }
      if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trimStart())
      }
    })
    if (!dataLines.length) {
      return null
    }
    const rawData = dataLines.join('\n')
    let payload = rawData
    try {
      payload = JSON.parse(rawData)
    } catch (error) {
      payload = rawData
    }
    switch (eventName) {
      case 'start':
        handlers.onStart?.(payload)
        break
      case 'delta':
        handlers.onDelta?.(payload?.delta || '')
        break
      case 'done':
        handlers.onDone?.(payload)
        break
      case 'error':
        handlers.onError?.(payload)
        break
      default:
        break
    }
    return { eventName, payload }
  }

  return {
    push(chunk) {
      buffer += chunk.replace(/\r\n/g, '\n')
      const events = []
      let boundaryIndex = buffer.indexOf('\n\n')
      while (boundaryIndex >= 0) {
        const block = buffer.slice(0, boundaryIndex)
        buffer = buffer.slice(boundaryIndex + 2)
        const parsed = dispatchBlock(block)
        if (parsed) {
          events.push(parsed)
        }
        boundaryIndex = buffer.indexOf('\n\n')
      }
      return events
    },
    flush() {
      const last = dispatchBlock(buffer)
      buffer = ''
      return last ? [last] : []
    },
  }
}

async function fallbackAsk(payload, handlers) {
  const response = await request({
    url: '/api/ai/ask',
    method: 'POST',
    data: payload,
    header: authHeader(),
  })
  handlers.onStart?.({
    sessionId: response.sessionId,
    messageId: response.messageId,
    traceId: response.traceId,
    answerType: response.answerType,
    sources: response.sources || [],
    suggestedQuestions: response.suggestedQuestions || [],
    retrievalMeta: response.retrievalMeta || {},
  })
  if (response.answer) {
    handlers.onDelta?.(response.answer)
  }
  handlers.onDone?.(response)
  return response
}

export default {
  async bootstrap() {
    return request({
      url: '/api/ai/bootstrap',
      method: 'GET',
    })
  },
  async listSessions() {
    return request({
      url: '/api/ai/sessions',
      method: 'GET',
      header: authHeader(),
    })
  },
  async listMessages(sessionId) {
    return request({
      url: `/api/ai/sessions/${sessionId}/messages`,
      method: 'GET',
      header: authHeader(),
    })
  },
  async ask(payload) {
    return request({
      url: '/api/ai/ask',
      method: 'POST',
      data: payload,
      header: authHeader(),
    })
  },
  async askStream(payload, handlers = {}) {
    const header = {
      ...authHeader(),
      Accept: 'text/event-stream',
    }
    const hasAuthorization = !!(header.Authorization || header.authorization)

    return new Promise((resolve, reject) => {
      let completed = false
      let donePayload = null
      let sawStreamEvent = false
      const decoder = typeof TextDecoder !== 'undefined' ? new TextDecoder('utf-8') : null
      const parser = createSseParser({
        onStart: (event) => {
          sawStreamEvent = true
          handlers.onStart?.(event)
        },
        onDelta: (delta) => {
          sawStreamEvent = true
          handlers.onDelta?.(delta)
        },
        onDone: (event) => {
          sawStreamEvent = true
          donePayload = event
          handlers.onDone?.(event)
          if (!completed) {
            completed = true
            resolve(event)
          }
        },
        onError: (event) => {
          sawStreamEvent = true
          handlers.onError?.(event)
          if (!completed) {
            completed = true
            reject(new Error(event?.message || 'Stream request failed'))
          }
        },
      })

      const requestTask = uni.request({
        url: `${BASE_URL}/api/ai/ask/stream`,
        method: 'POST',
        data: payload,
        header,
        enableChunked: true,
        success: (response) => {
          if (completed) {
            return
          }
          const contentType = normalizeHeaderValue(response.header, 'content-type')
          if (!sawStreamEvent || !String(contentType).includes('text/event-stream')) {
            try {
              const body = typeof response.data === 'string' ? JSON.parse(response.data) : response.data
              const unwrapped = unwrapApiResponse(body, hasAuthorization)
              completed = true
              resolve(unwrapped)
            } catch (error) {
              completed = true
              reject(error)
            }
            return
          }
          parser.flush()
          if (!completed) {
            completed = true
            resolve(donePayload)
          }
        },
        fail: async (error) => {
          if (completed) {
            return
          }
          if (typeof requestTask?.onChunkReceived !== 'function') {
            try {
              const response = await fallbackAsk(payload, handlers)
              completed = true
              resolve(response)
            } catch (fallbackError) {
              completed = true
              reject(fallbackError)
            }
            return
          }
          completed = true
          reject(error)
        },
      })

      if (!requestTask || typeof requestTask.onChunkReceived !== 'function') {
        requestTask?.abort?.()
        fallbackAsk(payload, handlers).then(resolve).catch(reject)
        return
      }

      requestTask.onChunkReceived((chunk) => {
        if (completed) {
          return
        }
        const text = decodeChunk(chunk.data, decoder)
        parser.push(text)
      })
    })
  },
  async feedback(messageId, payload) {
    return request({
      url: `/api/ai/messages/${messageId}/feedback`,
      method: 'POST',
      data: payload,
      header: authHeader(),
    })
  },
}
