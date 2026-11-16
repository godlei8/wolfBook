<script setup>
// 新版对局笔记（合并版）：座位盘 + 票型，主体在 components/NotesBoard.vue。这一页只管读笔记、每次改动后存。
// 存法：先改本页的状态（画面立刻变），0.8 秒内没有新改动再整份保存；离开页面时把没存的存掉。
import { computed, ref } from 'vue'
import { onHide, onLoad, onPageScroll, onUnload } from '@dcloudio/uni-app'
import NavBar from '../../components/NavBar.vue'
import NotesBoard from '../../components/NotesBoard.vue'
import userData from '../../services/user-data'
import { decodeNote, encodeNote, isNoteV2 } from '../../utils/notes/model.js'
import { createNoteSaver } from '../../utils/notes/saver.js'

const note = ref(null)
const loadError = ref('')
const saveState = ref('')
const saver = createNoteSaver({
  save: (value) => userData.saveSession(encodeNote(value, new Date().toISOString())),
  onState: (state) => { saveState.value = state },
})

const title = computed(() => note.value?.boardName || '对局笔记')
const subtitle = computed(() => {
  if (!note.value) return ''
  const state = { saving: '正在保存…', saved: '已保存', failed: '没存上，会再试' }[saveState.value] || ''
  return [`${note.value.playerCount} 人`, state].filter(Boolean).join(' · ')
})

function onChange(next) {
  note.value = next
  saver.touch(next)
}

function flush() {
  saver.flush()
}

// 点开的座位被面板挡住时，把页面往上滚一点，让那一排停在面板上方
let pageTop = 0
onPageScroll((event) => {
  pageTop = event.scrollTop
})
function onReveal(delta) {
  uni.pageScrollTo({ scrollTop: pageTop + delta, duration: 200 })
}

onLoad(async (options) => {
  try {
    const session = await userData.getSessionById(options?.id || '')
    if (!session) {
      loadError.value = '这条笔记不在了'
    } else if (!isNoteV2(session)) {
      uni.redirectTo({ url: `/pages/sessions/detail?id=${session.sessionId}` })
    } else {
      note.value = decodeNote(session)
    }
  } catch (error) {
    loadError.value = error?.message || '笔记加载失败'
  }
})

onHide(flush)
onUnload(flush)
</script>

<template>
  <view class="nt-page sb-page">
    <NavBar back>
      <view class="sb-nav">
        <text class="sb-nav__title serif">{{ title }}</text>
        <text class="sb-nav__sub">{{ subtitle }}</text>
      </view>
    </NavBar>

    <view v-if="loadError" class="nt-gutter"><view class="nt-empty">{{ loadError }}</view></view>
    <view v-else-if="!note" class="nt-gutter"><view class="nt-empty">正在打开笔记…</view></view>
    <view v-else class="nt-gutter sb-body">
      <NotesBoard :note="note" @change="onChange" @reveal="onReveal" />
    </view>
  </view>
</template>

<style scoped lang="scss">
.sb-page {
  padding-bottom: calc(env(safe-area-inset-bottom) + 48rpx);
}

.sb-nav {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2rpx;
}

.sb-nav__title {
  font-size: 32rpx;
  font-weight: 900;
  max-width: 360rpx;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sb-nav__sub {
  font-size: 22rpx;
  color: var(--text-2);
}

.sb-body {
  padding-top: 12rpx;
}
</style>
