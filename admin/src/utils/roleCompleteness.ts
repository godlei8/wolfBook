import type { Role, RoleRelation, WorldEvent } from '../types'

// 角色设定完整度：和 backend/src/main/resources/prompts/role-writing-guide.md 的写作规范对应。
// 必需项缺任何一项，图鉴卡片和总览就标「设定不完整」；建议项只做提示，不算缺。

export const MIN_BACKGROUND_LENGTH = 80
export const MIN_RELATIONS = 2

/** 判定只用到这几个字段，角色列表里的 Role 和编辑弹窗里的表单都能直接传进来。 */
export interface RoleDraftShape {
  id: number
  faction: string
  alias: string | null
  title: string | null
  background: string | null
  portrait: string | null
  fullIllustration: string | null
  relations: RoleRelation[]
  mechanic?: string | null
}

export interface CheckItem {
  key: string
  label: string
  ok: boolean
  /** 没过时的一句提示 */
  hint: string
}

export interface Completeness {
  required: CheckItem[]
  suggestions: CheckItem[]
  missing: CheckItem[]
  complete: boolean
}

function filled(value: string | null | undefined) {
  return Boolean(value && value.trim())
}

/** 世界观里出过场的角色 id。 */
export function eventRoleIds(events: WorldEvent[] | null | undefined) {
  const ids = new Set<number>()
  ;(events || []).forEach((event) => (event.roleIds || []).forEach((id) => ids.add(id)))
  return ids
}

export function checkRole(role: RoleDraftShape, rolesById: Map<number, Role>, castIds: Set<number>): Completeness {
  const backgroundLength = (role.background || '').trim().length
  const relations = (role.relations || []).filter((relation) => relation.roleId && filled(relation.label))
  const required: CheckItem[] = [
    { key: 'illustration', label: '立绘', ok: filled(role.fullIllustration), hint: '上传 3:4 竖版立绘' },
    { key: 'portrait', label: '头像', ok: filled(role.portrait), hint: '上传立绘会自动裁好头像' },
    { key: 'alias', label: '真名', ok: filled(role.alias), hint: '西式「名·姓」，比如塞拉菲娜·罗丝' },
    { key: 'title', label: '称号', ok: filled(role.title), hint: '2 到 6 个字，比如玫瑰夫人' },
    {
      key: 'background',
      label: `身世 ${MIN_BACKGROUND_LENGTH} 字以上`,
      ok: backgroundLength >= MIN_BACKGROUND_LENGTH,
      hint: backgroundLength ? `现在 ${backgroundLength} 字，再写一些` : '出身、转折、现在在镇上做什么',
    },
    {
      key: 'relations',
      label: `人物关系 ${MIN_RELATIONS} 条以上`,
      ok: relations.length >= MIN_RELATIONS,
      hint: `现在 ${relations.length} 条`,
    },
  ]
  const crossFaction = relations.some((relation) => {
    const other = rolesById.get(relation.roleId)
    return Boolean(other && other.faction !== role.faction)
  })
  const suggestions: CheckItem[] = [
    { key: 'cross', label: '有一条跨阵营的关系', ok: crossFaction, hint: '和另一个阵营的人有点恩怨，故事才有张力' },
    {
      key: 'mechanic',
      label: '设了技能机制',
      ok: Boolean(role.mechanic),
      hint: '不设置的话电子法官只播报不结算，带它的板子只能开有法官',
    },
    {
      key: 'chronicle',
      label: '在编年史里出场',
      ok: role.id ? castIds.has(role.id) : false,
      hint: role.id ? '去「世界观」把他挂进一段事件' : '保存后去「世界观」把他挂进一段事件',
    },
  ]
  const missing = required.filter((item) => !item.ok)
  return { required, suggestions, missing, complete: missing.length === 0 }
}
