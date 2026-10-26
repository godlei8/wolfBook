import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'
import { rewriteMpWeixinAppConfig } from './build/mp-weixin-app-config.mjs'

const projectRoot = path.dirname(fileURLToPath(import.meta.url))

function mpWeixinDevPluginPolicy() {
  let mode = 'production'

  return {
    name: 'mp-weixin-dev-plugin-policy',
    configResolved(resolvedConfig) {
      mode = resolvedConfig.mode
    },
    writeBundle() {
      if (process.env.UNI_PLATFORM !== 'mp-weixin') {
        return
      }

      const outputStage = mode === 'development' ? 'dev' : 'build'
      const appConfigPath = path.resolve(projectRoot, 'dist', outputStage, 'mp-weixin', 'app.json')
      rewriteMpWeixinAppConfig(appConfigPath, { mode })
    },
  }
}

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    uni(),
    mpWeixinDevPluginPolicy(),
  ],
})
