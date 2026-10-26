import { existsSync, readFileSync, writeFileSync } from 'node:fs'

export function applyMpWeixinPluginPolicy(appConfig, options = {}) {
  const nextConfig = JSON.parse(JSON.stringify(appConfig || {}))

  if (options.mode === 'development' && nextConfig.plugins?.WechatSI) {
    delete nextConfig.plugins.WechatSI
    if (!Object.keys(nextConfig.plugins).length) {
      delete nextConfig.plugins
    }
  }

  return nextConfig
}

export function rewriteMpWeixinAppConfig(appConfigPath, options = {}) {
  if (!existsSync(appConfigPath)) {
    return false
  }

  const currentConfig = JSON.parse(readFileSync(appConfigPath, 'utf8'))
  const nextConfig = applyMpWeixinPluginPolicy(currentConfig, options)

  if (JSON.stringify(currentConfig) === JSON.stringify(nextConfig)) {
    return false
  }

  writeFileSync(appConfigPath, `${JSON.stringify(nextConfig, null, 2)}\n`, 'utf8')
  return true
}
