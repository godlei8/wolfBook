<script setup>
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import api from '../../services/api'

const groups = ref([])

async function loadRoles() {
  try {
    const roles = await api.getRoles()
    groups.value = [
      { title: '好人', items: roles.filter((role) => role.faction === '好人') },
      { title: '狼人', items: roles.filter((role) => role.faction === '狼人') },
      { title: '第三方', items: roles.filter((role) => role.faction === '第三方') },
    ].filter((group) => group.items.length)
  } catch (error) {
    uni.showToast({ title: '角色加载失败', icon: 'none' })
  }
}

function openRole(id) {
  uni.navigateTo({ url: `/pages/roles/detail?id=${id}` })
}

onLoad(loadRoles)
</script>

<template>
  <view class="page-shell">
    <view class="hero-title">角色图鉴</view>
    <view class="hero-subtitle">按阵营查看角色技能、定位和对应板型。</view>

    <view v-for="group in groups" :key="group.title" class="glass-card section-card">
      <view class="section-title">{{ group.title }}</view>
      <view v-for="item in group.items" :key="item.id" class="role-row" @tap="openRole(item.id)">
        <image class="role-avatar" :src="item.portrait" mode="aspectFill" />
        <view class="role-content">
          <view class="role-name">{{ item.name }}</view>
          <view class="section-meta">{{ item.camp }}</view>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.role-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-top: 18rpx;
  padding: 12rpx 0;
}

.role-avatar {
  width: 88rpx;
  height: 88rpx;
  border-radius: 999rpx;
}

.role-name {
  font-size: 28rpx;
  font-weight: 700;
}
</style>
