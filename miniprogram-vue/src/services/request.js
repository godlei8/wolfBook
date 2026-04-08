import { BASE_URL } from './config'

function unwrap(response) {
  if (response.statusCode !== 200) {
    throw new Error(`Request failed: ${response.statusCode}`)
  }
  const payload = response.data
  if (!payload || payload.code !== 0) {
    throw new Error(payload?.msg || 'Request failed')
  }
  return payload.data
}

export function request({ url, method = 'GET', data, header = {} }) {
  return new Promise((resolve, reject) => {
    uni.request({
      url: `${BASE_URL}${url}`,
      method,
      data,
      header,
      success: (response) => {
        try {
          resolve(unwrap(response))
        } catch (error) {
          reject(error)
        }
      },
      fail: reject,
    })
  })
}

export function uploadFile(filePath, token) {
  return new Promise((resolve, reject) => {
    uni.uploadFile({
      url: `${BASE_URL}/api/upload`,
      filePath,
      name: 'file',
      header: token ? { Authorization: `Bearer ${token}` } : {},
      success: (response) => {
        try {
          resolve(unwrap({ statusCode: response.statusCode, data: JSON.parse(response.data) }))
        } catch (error) {
          reject(error)
        }
      },
      fail: reject,
    })
  })
}
