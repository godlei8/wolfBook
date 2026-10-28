<script setup>
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import BoardCard from '../../components/BoardCard.vue'
import NavBar from '../../components/NavBar.vue'
import api from '../../services/api'
import userData from '../../services/user-data'

const boards = ref([])
const favoriteIds = ref([])

function isFavorite(boardId) {
  return favoriteIds.value.includes(boardId)
}

async function loadLocalFavoriteBoards(ids) {
  const details = await Promise.all(
    ids.map(async (id) => {
      try {
        return await api.getBoardDetail(id)
      } catch (error) {
        return null
      }
    }),
  )
  boards.value = details.filter(Boolean)
}

async function loadFavorites() {
  try {
    const favoriteState = await userData.loadFavoriteBoards()
    favoriteIds.value = favoriteState.boardIds || []
    if (!favoriteIds.value.length) {
      boards.value = []
      return
    }

    if (favoriteState.cloud && favoriteState.boards?.length) {
      boards.value = favoriteState.boards
      return
    }

    await loadLocalFavoriteBoards(favoriteIds.value)
  } catch (error) {
    uni.showToast({ title: error?.message || '收藏加载失败', icon: 'none' })
  }
}

function openBoard(id) {
  uni.navigateTo({ url: `/pages/boards/detail?id=${id}` })
}

async function toggleFavorite(boardId) {
  try {
    const state = await userData.toggleFavorite(boardId)
    favoriteIds.value = state.boardIds || []
    boards.value = boards.value.filter((item) => favoriteIds.value.includes(item.id))
    uni.showToast({
      title: favoriteIds.value.includes(boardId) ? '已加入收藏' : '已取消收藏',
      icon: 'none',
    })
  } catch (error) {
    uni.showToast({ title: error?.message || '收藏更新失败', icon: 'none' })
  }
}

onShow(loadFavorites)
</script>

<template>
  <view class="nt-page fv-page">
    <NavBar back title="收藏的板子" />

    <view class="nt-gutter nt-stack fv-body">
      <view v-if="!boards.length" class="nt-empty">
        还没有收藏板子。去板子详情点底部的爱心，常用的配置就会留在这里。
      </view>
      <BoardCard
        v-for="board in boards"
        :key="board.id"
        :board="board"
        show-favorite
        :favorite="isFavorite(board.id)"
        @select="openBoard"
        @favorite-toggle="toggleFavorite"
      />
    </view>
  </view>
</template>

<style scoped lang="scss">
.fv-page {
  padding-bottom: calc(env(safe-area-inset-bottom) + 60rpx);
}

.fv-body {
  margin-top: 16rpx;
}
</style>
