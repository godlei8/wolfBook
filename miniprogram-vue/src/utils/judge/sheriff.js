// 上警和公布死讯（纯函数，有单测）。
// 报名阶段只给本人「上警 / 不上警」，不显示别人选了什么（快照 dayInfo 只有本人的）；
// 警上发言时警上的人能退水；警长投票只有没上警的人能投，只能投警上的人（PK 只能投平票的人）。

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
  return {
    stage,
    canSignUp: stage === 'signup' && alive,
    candidate: Boolean(info.sheriffCandidate),
    canWithdraw: stage === 'speech' && alive && candidates.includes(selfSeat),
    canVote: voting && alive && Boolean(info.canVote) && !candidates.includes(selfSeat),
    targets,
    candidates,
    speakerSeat: stage === 'speech' ? speakerOf(snapshot) : null,
    voted: Boolean(info.voted),
    myVote: info.myVote ?? null,
    progress: voting ? `已投 ${info.votedCount || 0} / ${info.voterCount || 0}` : '',
  }
}

/** 天亮公布：昨晚出局的人来自对局记录当天的 nightDeaths；警长本人选发言顺序（有人出局再多「死左 / 死右」）。 */
export function announcePanel(snapshot) {
  const day = snapshot?.currentDay || 1
  const round = (snapshot?.rounds || []).find((item) => item.dayNo === day)
  const deaths = round?.nightDeaths || []
  const sheriffSeat = snapshot?.sheriffSeatNo || null
  const self = snapshot?.selfPlayer
  const canChooseOrder = Boolean(
    sheriffSeat && seated(snapshot) && self.seatNo === sheriffSeat && snapshot?.currentPhase === 'day_announce',
  )
  const orderOptions = [
    { value: 'left', label: '警左' },
    { value: 'right', label: '警右' },
  ]
  if (deaths.length) {
    orderOptions.push({ value: 'dead_left', label: '死左' }, { value: 'dead_right', label: '死右' })
  }
  return {
    deaths,
    text: deaths.length ? `昨晚 ${deaths.join('、')} 号出局` : '昨晚是平安夜',
    announcement: snapshot?.latestAnnouncement || '',
    sheriffSeat,
    canChooseOrder,
    orderOptions,
  }
}
