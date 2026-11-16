// 法官房间里的发言快捷条（纯函数，有单测）：谁在发言，快捷条上有哪些按钮，点了之后记到他座位上。
import { sayOf, seatOf } from './model.js'
import { TARGET_TAGS, addTargetTag, toggleTag } from './board.js'

const SPEECH_PHASES = ['day_speech', 'sheriff_speech']
const IDLE_TAG = '划水'
const MAX_TARGETS = 3

/** 房间里现在谁在说话：白天发言、警上发言（含 PK 发言）的当前发言人，或者正在留遗言的人。只读公开字段。 */
export function roomSpeaker(snapshot) {
  if (!snapshot || snapshot.roomStatus !== 'playing') return 0
  if (snapshot.currentPhase === 'skill_pending') {
    return snapshot.currentPending?.kind === 'last_words' ? snapshot.currentPending.seatNo || 0 : 0
  }
  if (!SPEECH_PHASES.includes(snapshot.currentPhase)) return 0
  const order = snapshot.speech?.order || []
  return order[snapshot.speech?.index] || 0
}

/**
 * 发言快捷条。「站 X / 踩 X」里的 X 是我标了「他说自己是什么」的座位（一般就是对跳的那两个），最多三个；
 * 一个都没标时是「站边… / 踩人…」，点了再去点座位。没人发言返回 null。
 */
export function speechDock(note, day, speaker) {
  if (!speaker) return null
  const tags = sayOf(note, day, speaker).tags
  const targets = []
  for (let no = 1; no <= note.playerCount && targets.length < MAX_TARGETS; no += 1) {
    if (no !== speaker && seatOf(note, no).claim) targets.push(no)
  }
  const chips = []
  for (const kind of ['stand', 'step']) {
    if (!targets.length) {
      chips.push({ kind, target: 0, label: kind === 'stand' ? '站边…' : '踩人…', on: false })
    }
    for (const target of targets) {
      chips.push({ kind, target, label: `${TARGET_TAGS[kind]} ${target} 号`, on: tags.includes(`${TARGET_TAGS[kind]} ${target}`) })
    }
  }
  chips.push({ kind: 'claim', target: 0, label: '跳身份', on: false })
  chips.push({ kind: 'idle', target: 0, label: IDLE_TAG, on: tags.includes(IDLE_TAG) })
  chips.push({ kind: 'text', target: 0, label: '+ 写一句', on: false })
  return { no: speaker, title: `${speaker} 号正在发言`, chips }
}

/**
 * 点快捷条上的一个按钮。能直接记下的（站 X、踩 X、划水）返回新笔记；
 * 要再点座位或要打字的（站边…、踩人…、跳身份、写一句）返回 null，由页面打开发言人的面板继续。
 */
export function applyDockChip(note, day, speaker, chip) {
  if (chip.kind === 'idle') return toggleTag(note, day, speaker, IDLE_TAG)
  if ((chip.kind === 'stand' || chip.kind === 'step') && chip.target) return addTargetTag(note, day, speaker, chip.kind, chip.target)
  return null
}
