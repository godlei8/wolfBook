// 纯函数：把网络层/HTTP 层的错误翻译成给用户看的中文文案。
// 这里不能引用 uni，tests/ 下的 Node 测试会直接 import。

export const AUTH_ERROR_CODE = 4001

export function isAuthErrorCode(code) {
  return Number(code) === AUTH_ERROR_CODE
}

export function isApiPayload(payload) {
  return !!payload && typeof payload === 'object' && typeof payload.code === 'number'
}

export function describeRequestError({ statusCode, errMsg, payload } = {}) {
  if (isApiPayload(payload) && payload.code !== 0) {
    return payload.msg || '请求失败，请稍后重试'
  }

  const status = Number(statusCode)
  if (Number.isFinite(status) && status > 0) {
    if (status >= 500) {
      return '服务器开小差了，请稍后重试'
    }
    if (status >= 400) {
      return `请求失败（${status}）`
    }
    if (status === 200) {
      // 200 但 body 不是合法的 ApiResponse（例如网关返回 HTML）
      return '服务器开小差了，请稍后重试'
    }
  }

  const message = typeof errMsg === 'string' ? errMsg.toLowerCase() : ''
  if (message.includes('timeout') || message.includes('time out')) {
    return '网络超时，请重试'
  }
  if (message.includes('abort')) {
    return '请求已取消'
  }
  if (message.includes('fail') || message.includes('network') || message.includes('err_')) {
    return '网络不可用，请检查网络'
  }

  return '请求失败，请稍后重试'
}

export function createRequestError(details = {}) {
  const error = new Error(describeRequestError(details))
  error.statusCode = details.statusCode
  error.errMsg = details.errMsg
  if (isApiPayload(details.payload)) {
    error.code = details.payload.code
  }
  return error
}
