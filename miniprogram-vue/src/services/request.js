import { BASE_URL } from './config'
import storage from './storage'
import { createRequestError, isApiPayload, isAuthErrorCode } from './request-errors'

export function clearAuthState() {
  storage.clearAuthToken()
  storage.setUserProfile(null)
}

export function createAuthHeader(token = storage.getAuthToken()) {
  return token ? { Authorization: `Bearer ${token}` } : {}
}

function parseBody(data) {
  if (typeof data !== 'string') {
    return data
  }
  try {
    return JSON.parse(data)
  } catch (error) {
    return data
  }
}

function unwrap(response, options = {}) {
  const payload = parseBody(response.data)
  if (response.statusCode !== 200 || !isApiPayload(payload)) {
    throw createRequestError({ statusCode: response.statusCode, payload })
  }
  if (payload.code !== 0) {
    if (isAuthErrorCode(payload.code) && options.hasAuthorization) {
      clearAuthState()
    }
    throw createRequestError({ statusCode: response.statusCode, payload })
  }
  return payload.data
}

function toTransportError(error) {
  return createRequestError({ errMsg: error?.errMsg || error?.message || '' })
}

export function request({ url, method = 'GET', data, header = {} }) {
  return new Promise((resolve, reject) => {
    const hasAuthorization = !!(header.Authorization || header.authorization)
    uni.request({
      url: `${BASE_URL}${url}`,
      method,
      data,
      header,
      timeout: 10000,
      success: (response) => {
        try {
          resolve(unwrap(response, { hasAuthorization }))
        } catch (error) {
          reject(error)
        }
      },
      fail: (error) => reject(toTransportError(error)),
    })
  })
}

export function uploadFile(filePath, token) {
  return new Promise((resolve, reject) => {
    uni.uploadFile({
      url: `${BASE_URL}/api/upload`,
      filePath,
      name: 'file',
      header: createAuthHeader(token),
      timeout: 20000,
      success: (response) => {
        try {
          resolve(unwrap(response, { hasAuthorization: !!token }))
        } catch (error) {
          reject(error)
        }
      },
      fail: (error) => reject(toTransportError(error)),
    })
  })
}
