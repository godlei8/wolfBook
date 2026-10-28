// 从立绘裁头像：和 design/prompts/crop_roles.py 人工标的框取平均值——
// 脸大约在宽度正中、高度三分之一处，正方形边长约为宽度的 61%。超出画面时整体平移回来。
const CENTER_X = 0.5
const CENTER_Y = 0.33
const SIDE_RATIO = 0.61
const PORTRAIT_SIZE = 512

function loadImage(file: Blob) {
  return new Promise<HTMLImageElement>((resolve, reject) => {
    const url = URL.createObjectURL(file)
    const image = new Image()
    image.onload = () => {
      URL.revokeObjectURL(url)
      resolve(image)
    }
    image.onerror = () => {
      URL.revokeObjectURL(url)
      reject(new Error('立绘读取失败'))
    }
    image.src = url
  })
}

/** 把立绘文件裁成 512×512 的 webp 头像文件。 */
export async function cropPortrait(illustration: File): Promise<File> {
  const image = await loadImage(illustration)
  const width = image.naturalWidth
  const height = image.naturalHeight
  const side = Math.min(Math.round(width * SIDE_RATIO), width, height)
  const x = Math.min(Math.max(Math.round(width * CENTER_X - side / 2), 0), width - side)
  const y = Math.min(Math.max(Math.round(height * CENTER_Y - side / 2), 0), height - side)

  const canvas = document.createElement('canvas')
  canvas.width = PORTRAIT_SIZE
  canvas.height = PORTRAIT_SIZE
  const context = canvas.getContext('2d')
  if (!context) throw new Error('浏览器不支持裁图')
  context.imageSmoothingQuality = 'high'
  context.drawImage(image, x, y, side, side, 0, 0, PORTRAIT_SIZE, PORTRAIT_SIZE)

  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/webp', 0.9))
  if (!blob) throw new Error('头像生成失败')
  const type = blob.type || 'image/webp'
  return new File([blob], `portrait.${type === 'image/png' ? 'png' : 'webp'}`, { type })
}
