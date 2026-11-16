// 笔记列表和新建（纯函数，有单测）：列表卡片上显示什么、点开去哪一页、新建时从板子取狼数和能跳的身份。
import { createNote, decodeNote, encodeNote, isNoteV2 } from './model.js'
import { wolfPit } from './board.js'

const LEGACY_LABELS = { seer: '查验记录', vote: '投票记录', speech: '发言记录', wolfPack: '狼坑分析' }

/** 列表里的一张卡：新版显示记到第几天和狼坑，旧版照旧显示最近一条记录。 */
export function noteCard(session) {
  const records = session?.records || []
  if (isNoteV2(session)) {
    const note = decodeNote(session)
    const pit = wolfPit(note).seats.map((seat) => seat.no)
    const marked = Object.keys(note.seats).length
    return {
      v2: true,
      url: `/pages/sessions/board?id=${session.sessionId}`,
      countText: `记到第 ${note.days} 天`,
      label: pit.length ? '我的狼坑' : '座位盘',
      preview: pit.length ? `${pit.join('、')} 号` : (marked ? `标了 ${marked} 个座位` : '还没标，点进去在座位上点一点。'),
    }
  }
  const latest = records.length ? records[records.length - 1] : null
  return {
    v2: false,
    url: `/pages/sessions/detail?id=${session?.sessionId || ''}`,
    countText: `${records.length} 条记录`,
    label: latest ? LEGACY_LABELS[latest.type] || '最新记录' : '旧版笔记',
    preview: latest ? latest.content : '旧版笔记，只能查看。',
  }
}

/** 板子阵容里狼人阵营有几个、好人能跳哪些身份（新建笔记时记下来）。 */
export function boardFacts(board) {
  const roles = (board?.roles || []).filter((role) => role?.name)
  const wolves = roles.filter((role) => role.faction === '狼人').reduce((sum, role) => sum + (Number(role.count) || 1), 0)
  const claims = [...new Set(roles.filter((role) => role.faction !== '狼人').map((role) => role.name))]
  return { wolves, claims }
}

/** 新建一条新版笔记，直接是接口要的会话（带 n2.meta）。 */
export function newNoteSession({ sessionId, base, board, now }) {
  return encodeNote(createNote({ sessionId, ...base, ...boardFacts(board), now }), now)
}
