import test from 'node:test'
import assert from 'node:assert/strict'
import { recentRoomTags } from '../src/utils/judge/recent.js'

// 开局页「最近的房间」：先标有法官 / 无法官，再标我在这间房里是谁
// 有法官：房主就是法官（不入座），其他人是玩家；无法官：房主坐 1 号也拿牌，其他人是玩家
const room = (extra) => ({ roomStatus: 'lobby', judgeMode: 'observer', owned: false, joined: false, ...extra })

test('recentRoomTagsSayTheModeAndWhoIAm', () => {
  assert.deepEqual(recentRoomTags(room({ owned: true })), [
    { text: '有法官', tone: 'mode' }, { text: '法官 · 房主', tone: 'me' },
  ])
  assert.deepEqual(recentRoomTags(room({ joined: true })), [
    { text: '有法官', tone: 'mode' }, { text: '玩家', tone: 'player' },
  ])
  assert.deepEqual(recentRoomTags(room({ judgeMode: 'joined', owned: true, joined: true })), [
    { text: '无法官', tone: 'mode' }, { text: '房主 · 玩家', tone: 'me' },
  ])
  assert.deepEqual(recentRoomTags(room({ judgeMode: 'joined', joined: true })), [
    { text: '无法官', tone: 'mode' }, { text: '玩家', tone: 'player' },
  ])
  assert.deepEqual(recentRoomTags(room({ judgeMode: 'joined' })), [{ text: '无法官', tone: 'mode' }], '已经不在房里（离开了）就不标身份')
})
