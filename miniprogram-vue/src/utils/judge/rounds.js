// 胜负文案和结果页身份卡（纯函数，有单测）。对局记录在 game-log.js。
import { campOf } from './judge-bar.js'

export function verdictText(snapshot) {
  const camp = snapshot?.winVerdict?.camp || snapshot?.winnerCamp || ''
  if (!camp || camp === '流局') return { title: '流局', reason: snapshot?.winVerdict?.reason || '对局被提前结束，不计胜负' }
  return { title: `${camp}阵营胜利`, reason: snapshot?.winVerdict?.reason || '' }
}

/** 结果页的身份卡：按座位排，头像用板子里这个角色的头像（没有用立绘），胜方阵营的人标 winner（流局没有胜方）。 */
export function resultCards(snapshot, board) {
  const camp = snapshot?.winVerdict?.camp || snapshot?.winnerCamp || ''
  const roles = new Map((board?.roles || []).map((role) => [role?.name, role]))
  return (snapshot?.players || [])
    .filter((player) => player?.seatNo)
    .sort((a, b) => a.seatNo - b.seatNo)
    .map((player) => {
      const wolf = player.faction === '狼人'
      const role = roles.get(player.roleName)
      return {
        seatNo: player.seatNo,
        nickname: player.nickname || '',
        roleName: player.roleName || '—',
        portrait: role?.portrait || role?.fullIllustration || '',
        camp: campOf(player),
        alive: player.alive !== false,
        winner: camp === '狼人' ? wolf : camp === '好人' ? !wolf && player.faction === '好人' : false,
      }
    })
}
