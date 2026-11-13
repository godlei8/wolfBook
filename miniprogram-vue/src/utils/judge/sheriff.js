// 上警和公布死讯（纯函数，有单测）。
// 报名阶段只给本人「上警 / 不上警」，不显示别人选了什么（快照 dayInfo 只有本人的）；
// 警上发言时警上的人能退水；警长投票只有没上警的人能投，只能投警上的人（PK 只能投平票的人）。
import { countdown } from './room-view.js'

const STAGES = { sheriff_signup: 'signup', sheriff_speech: 'speech', sheriff_vote: 'vote', sheriff_pk: 'pk' }

function seated(snapshot) {
  const self = snapshot?.selfPlayer
  return Boolean(!snapshot?.judgeViewer && self?.seatNo && self.alive !== false)
}

export function speakerOf(snapshot) {
  const speech = snapshot?.speech
  if (!speech?.order?.length) return null
  return speech.order[speech.index] ?? null
}

export function sheriffPanel(snapshot) {
  const stage = STAGES[snapshot?.currentPhase] || 'signup'
  const info = snapshot?.dayInfo || {}
  const selfSeat = snapshot?.selfPlayer?.seatNo || null
  const candidates = snapshot?.sheriffCandidates || []
  const alive = seated(snapshot)
  const voting = stage === 'vote' || stage === 'pk'
  const targets = stage === 'pk' ? snapshot?.pkSeats || [] : voting ? candidates : []
  // 报名：null 是还没选，false 是选了不上警（实测问题 2）
  const choice = info.sheriffCandidate === true ? 'up' : info.sheriffCandidate === false ? 'down' : null
  return {
    stage,
    canSignUp: stage === 'signup' && alive,
    candidate: Boolean(info.sheriffCandidate),
    choice,
    choiceText: choice === 'up' ? '你报名上警了，截止前还能改'
      : choice === 'down' ? '你选了不上警，截止前还能改' : '报名只有你自己看得到，到点自动截止。',
    canWithdraw: stage === 'speech' && alive && candidates.includes(selfSeat),
    // 第一轮只有警下投；PK 时还在警上、不在 PK 的人也能投（后端 dayInfo.canVote 已经算好，这里只挡掉 PK 的人）
    canVote: voting && alive && Boolean(info.canVote) && !(stage === 'pk' ? targets : candidates).includes(selfSeat),
    reason: snapshot?.judgeViewer ? '法官不投票'
      : stage === 'pk' && targets.includes(selfSeat) ? '你在 PK，不能投票'
        : candidates.includes(selfSeat) ? '你在警上，不能投票' : '你这一轮不能投票',
    targets,
    candidates,
    speakerSeat: stage === 'speech' ? speakerOf(snapshot) : null,
    // 正在警上发言的本人可以「结束发言」（和白天发言一样走 next_speaker）
    isSpeaker: stage === 'speech' && alive && Boolean(selfSeat) && speakerOf(snapshot) === selfSeat,
    voted: Boolean(info.voted),
    myVote: info.myVote ?? null,
    progress: voting ? `已投 ${info.votedCount || 0} / ${info.voterCount || 0}` : '',
  }
}

/** 天亮公布：昨晚出局的人来自对局记录当天的 nightDeaths；警长本人选发言顺序（有人出局再多「死左 / 死右」）。 */
const ORDER_LABELS = { left: '警左', right: '警右', dead_left: '死左', dead_right: '死右' }

/**
 * 天亮公布（用户给定）：有警长时警长选警左 / 警右（有死者还能选死左 / 死右），限时内没选系统随机警左或警右，到点开始发言；
 * 没警长停 5 秒自动开始发言（系统随机起点和方向）；警上自爆那天公布完死讯自动天黑。now 是校准过的当前时间。
 */
export function announcePanel(snapshot, now = Date.now()) {
  const day = snapshot?.currentDay || 1
  const round = (snapshot?.rounds || []).find((item) => item.dayNo === day)
  const deaths = round?.nightDeaths || []
  const sheriffSeat = snapshot?.sheriffSeatNo || null
  const self = snapshot?.selfPlayer
  const canChooseOrder = Boolean(
    sheriffSeat && !snapshot?.nightNext && seated(snapshot) && self.seatNo === sheriffSeat && snapshot?.currentPhase === 'day_announce',
  )
  const orderOptions = [
    { value: 'left', label: '警左' },
    { value: 'right', label: '警右' },
  ]
  if (deaths.length) {
    orderOptions.push({ value: 'dead_left', label: '死左' }, { value: 'dead_right', label: '死右' })
  }
  const seconds = countdown(snapshot?.phaseDeadline, now, false).seconds
  // 警长选了哪边（实测问题 3）：快照 sheriffOrder 全场可见
  const chosenOrder = snapshot?.sheriffOrder || null
  const chosenLabel = ORDER_LABELS[chosenOrder] || ''
  let chosenOrderText = ''
  if (sheriffSeat && !snapshot?.nightNext) {
    if (chosenOrder) chosenOrderText = canChooseOrder ? `你选了${chosenLabel}，开始发言前还能改` : `警长选了${chosenLabel}`
    else chosenOrderText = canChooseOrder ? `${seconds} 秒内选好，没选系统随机警左或警右` : '等警长选择，没选系统随机警左或警右'
  }
  const next = snapshot?.nightNext ? '天黑' : '开始发言'
  let hint = seconds > 0 ? `${seconds} 秒后自动${next}` : `马上${next}`
  if (!snapshot?.nightNext && sheriffSeat) hint += '，警长最后发言'
  return {
    deaths,
    text: deaths.length ? `昨晚 ${deaths.join('、')} 号出局` : '昨晚是平安夜',
    announcement: snapshot?.latestAnnouncement || '',
    sheriffSeat,
    canChooseOrder,
    orderOptions,
    chosenOrder,
    chosenOrderText,
    hint,
  }
}
