// 开局页「最近的房间」卡片上的小标签（纯函数，有单测）：先标有法官 / 无法官，再标我在这间房里是谁。
// 有法官：房主就是法官（不入座），其他人是玩家；无法官：房主坐 1 号也拿牌，其他人是玩家。

/** item 是 /api/judge/rooms/recent 的一行（judgeMode、owned、joined）。 */
export function recentRoomTags(item) {
  const judged = item?.judgeMode !== 'joined'
  const tags = [{ text: judged ? '有法官' : '无法官', tone: 'mode' }]
  if (item?.owned) tags.push({ text: judged ? '法官 · 房主' : '房主 · 玩家', tone: 'me' })
  else if (item?.joined) tags.push({ text: '玩家', tone: 'player' })
  return tags
}
