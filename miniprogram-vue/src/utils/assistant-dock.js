const DESIGN_VIEWPORT_RPX = 750
const MIN_TOP_PX = 88
const BOTTOM_CLEARANCE_PX = 140
const SIDE_OFFSET_RPX = 20

function clamp(value, min, max) {
  return Math.min(Math.max(value, min), max)
}

// 纵向范围按窗口高度限制，但 px → rpx 必须按窗口宽度换算（750rpx = 屏幕宽度）
export function resolveAssistantDockTopRpx({
  clientY,
  windowWidth,
  windowHeight,
  minTopPx = MIN_TOP_PX,
  bottomClearancePx = BOTTOM_CLEARANCE_PX,
  designViewportRpx = DESIGN_VIEWPORT_RPX,
}) {
  const safeWidth = Number(windowWidth) || 0
  const safeHeight = Number(windowHeight) || 0
  if (!safeWidth || !safeHeight) {
    return Math.round(minTopPx)
  }

  const rawTopPx = Number(clientY) - 40
  const maxTopPx = Math.max(minTopPx, safeHeight - bottomClearancePx)
  const clampedTopPx = clamp(rawTopPx, minTopPx, maxTopPx)

  return Math.round((clampedTopPx / safeWidth) * designViewportRpx)
}

export function buildAssistantDockStyle({ side, topRpx, offsetRpx = SIDE_OFFSET_RPX }) {
  return {
    top: `${Math.max(0, Number(topRpx) || 0)}rpx`,
    left: side === 'left' ? `${offsetRpx}rpx` : 'auto',
    right: side === 'right' ? `${offsetRpx}rpx` : 'auto',
  }
}
