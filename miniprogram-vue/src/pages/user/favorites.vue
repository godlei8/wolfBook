<script setup>
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import BoardCard from '../../components/BoardCard.vue'
import api from '../../services/api'
import storage from '../../services/storage'

const boards = ref([])
const favoriteIds = ref([])

function syncFavorites() {
  favoriteIds.value = storage.getFavorites()
}

function isFavorite(boardId) {
  return favoriteIds.value.includes(boardId)
}

async function loadFavorites() {
  syncFavorites()
  if (!favoriteIds.value.length) {
    boards.value = []
    return
  }
  try {
    const data = await api.getBoards({ page: 1, size: 100 })
    const boardMap = new Map((data.list || []).map((board) => [board.id, board]))
    boards.value = favoriteIds.value.map((id) => boardMap.get(id)).filter(Boolean)
  } catch (error) {
    uni.showToast({ title: '收藏加载失败', icon: 'none' })
  }
}

function openBoard(id) {
  uni.navigateTo({ url: `/pages/boards/detail?id=${id}` })
}

function toggleFavorite(boardId) {
  favoriteIds.value = storage.toggleFavorite(boardId)
  boards.value = boards.value.filter((item) => favoriteIds.value.includes(item.id))
  uni.showToast({
    title: favoriteIds.value.includes(boardId) ? '已加入收藏' : '已取消收藏',
    icon: 'none',
  })
}

onShow(loadFavorites)
</script>

<template>
  <view class="page-shell">
    <view class="hero-title">我的收藏</view>
    <view class="hero-subtitle">高频板型会一直留在这里，方便你快速回到熟悉的配置。</view>

    <view v-if="!boards.length" class="empty-state glass-card section-card">
      还没有收藏板子，去板子列表点亮右上角爱心吧。
    </view>

    <BoardCard
      v-for="board in boards"
      :key="board.id"
      :board-id="board.id"
      :cover-image="board.coverImage"
      :difficulty="board.difficulty"
      :meta-text="`共 ${boards.length} 个收藏`"
      :name="board.name"
      :player-count="board.playerCount"
      :card-roles="board.cardRoles || []"
      :description="board.cardDescription || board.summary || ''"
      :favorite="isFavorite(board.id)"
      @select="openBoard"
      @favorite-toggle="toggleFavorite"
    />
  </view>
</template>
