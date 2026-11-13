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
  // 警长选了哪边（实测问题 3）：快照 sheriffOrder 全场可见
  const chosenOrder = snapshot?.sheriffOrder || null
  const chosenLabel = [...orderOptions, { value: 'dead_left', label: '死左' }, { value: 'dead_right', label: '死右' }]
    .find((option) => option.value === chosenOrder)?.label || ''
  const chosenOrderText = !chosenOrder ? (canChooseOrder ? '选好后房主点「开始发言」' : '等警长选择')
    : canChooseOrder ? `你选了${chosenLabel}，开始发言前还能改` : `警长选了${chosenLabel}`
  return {
    deaths,
    text: deaths.length ? `昨晚 ${deaths.join('、')} 号出局` : '昨晚是平安夜',
    announcement: snapshot?.latestAnnouncement || '',
    sheriffSeat,
    canChooseOrder,
    orderOptions,
    chosenOrder,
    chosenOrderText,
    // 警上有人自爆的那天公布完死讯直接天黑
    hint: snapshot?.nightNext ? '有遗言的玩家发表遗言，房主点「天黑」进入黑夜。' : '房主点「开始发言」进入白天发言。',
  }
}
