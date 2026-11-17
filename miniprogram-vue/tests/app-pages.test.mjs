import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'

// 个人主体的小程序审核不过社区（用户发帖评论），社区模块先下掉：页面不进包、哪里都不链过去。
// 底部导航少了社区之后换成角色图鉴，「开局」还在正中。
const root = new URL('../src/', import.meta.url)
const pages = JSON.parse(readFileSync(new URL('pages.json', root), 'utf8'))
const TABS = ['pages/boards/list', 'pages/sessions/index', 'pages/judge/index', 'pages/roles/list', 'pages/user/index']

function sources(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return name === 'community' ? [] : sources(path)
    return /\.(vue|js)$/.test(name) ? [path] : []
  })
}

test('theCommunityIsNotInThePackage', () => {
  const paths = pages.pages.map((page) => page.path)
  assert.equal(paths.some((path) => path.startsWith('pages/community/')), false, paths.join(', '))
})

test('theTabBarIsBoardsNotesStartRolesMe', () => {
  assert.deepEqual(pages.tabBar.list.map((item) => item.pagePath), TABS)
  const custom = readFileSync(new URL('custom-tab-bar/index.js', root), 'utf8')
  const customPaths = [...custom.matchAll(/pagePath: '\/([^']+)'/g)].map((match) => match[1])
  assert.deepEqual(customPaths, TABS)
  assert.match(custom, /pagePath: '\/pages\/judge\/index', text: '开局', center: true/, '开局还在正中')
})

test('nothingLinksToTheCommunityAndTheRolesPageIsATab', () => {
  for (const file of sources(new URL('.', root).pathname.replace(/^\/([A-Za-z]:)/, '$1'))) {
    const text = readFileSync(file, 'utf8')
    assert.equal(/pages\/community\//.test(text), false, `${file} 还在链社区`)
    // 图鉴现在是 tab 页，只能 switchTab 过去
    assert.equal(/navigateTo\(\{ url: '\/pages\/roles\/list'/.test(text), false, `${file} 用 navigateTo 去图鉴`)
  }
})
