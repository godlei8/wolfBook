// 玩家自己的身份牌（纯函数，有单测）。导航栏的「身份」入口每个入座的玩家都有，点开才显示，不会因人而异。
// 法官不拿牌（他在座位上看全场身份），开局前也没有身份。

export function identityCard(snapshot, board) {
  const self = snapshot?.selfPlayer
  if (!self?.seatNo || !self.roleName || snapshot.judgeViewer || snapshot.roomStatus === 'lobby') return null
  const role = (board?.roles || []).find((item) => item?.name === self.roleName)
  const camp = self.faction === '狼人' ? '狼人阵营' : self.faction && self.faction !== '好人' ? `${self.faction}阵营` : '好人阵营'
  return {
    seatNo: self.seatNo,
    roleName: self.roleName,
    camp,
    wolf: self.faction === '狼人',
    art: role?.fullIllustration || '',
    alive: self.alive !== false,
  }
}
