<script setup>
import { computed, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import userData from '../../services/user-data'
import { normalizeSession, buildLatestRecords, buildTimelineGroups, buildSessionKeyPlayers } from '../../utils/session/normalizer'
import { localizeDeathReason, localizeRecordContent, localizeSessionRecord } from '../../utils/session/display'
import { inferSceneFromRecord } from '../../utils/session/record-builder'
import SessionHeaderCard from '../../components/session/SessionHeaderCard.vue'
import SessionBattleSummary from '../../components/session/SessionBattleSummary.vue'
import SessionTabSwitcher from '../../components/session/SessionTabSwitcher.vue'
import SessionOverviewPanel from '../../components/session/SessionOverviewPanel.vue'
import SessionSeatPanel from '../../components/session/SessionSeatPanel.vue'
import SessionTimelinePanel from '../../components/session/SessionTimelinePanel.vue'
import SessionReviewPanel from '../../components/session/SessionReviewPanel.vue'
import RecordEntryBar from '../../components/session/RecordEntryBar.vue'
import RecordEditorSheet from '../../components/session/RecordEditorSheet.vue'
import SessionBaseInfoSheet from '../../components/session/SessionBaseInfoSheet.vue'
import SeatQuickActionSheet from '../../components/session/SeatQuickActionSheet.vue'

const sessionId = ref('')
const loading = ref(false)
const session = ref(null)
const activeTab = ref('overview')

const editorVisible = ref(false)
const editorScene = ref('night')
const editingRecord = ref(null)
const editorPreset = ref(null)

const baseInfoVisible = ref(false)
const seatActionVisible = ref(false)
const activeSeatPlayer = ref(null)

const sessionPlayers = computed(() => session.value?.players || [])
const sessionRecords = computed(() => session.value?.records || [])
const timelineGroups = computed(() => buildTimelineGroups(sessionRecords.value))
const recentRecords = computed(() => buildLatestRecords(sessionRecords.value, 3))
const keyPlayers = computed(() => buildSessionKeyPlayers(session.value))

function syncSessionView(rawSession) {
  const normalized = rawSession ? normalizeSession(rawSession) : null
  if (!normalized) {
    session.value = null
    return
  }
  const records = (normalized.records || []).map((item) => localizeSessionRecord(item))
  const latestRecord = records[records.length - 1] || null
  const latestPreview = normalized.summary?.latestRecordPreview
    ? localizeRecordContent({
        type: normalized.summary?.latestRecordType,
        content: normalized.summary.latestRecordPreview,
        payload: latestRecord?.payload || {},
        actorSeats: latestRecord?.actorSeats || [],
        targetSeats: latestRecord?.targetSeats || [],
      })
    : localizeRecordContent(latestRecord)

  session.value = {
    ...normalized,
    players: (normalized.players || []).map((item) => ({
      ...item,
      deathReason: localizeDeathReason(item.deathReason),
    })),
    records,
    summary: {
      ...(normalized.summary || {}),
      latestRecordPreview: latestPreview,
    },
  }
}

async function refreshSession(showLoading = true) {
  if (!sessionId.value) return
  if (showLoading) {
    loading.value = true
  }
  try {
    const current = await userData.getSessionById(sessionId.value)
    if (!current) {
      session.value = null
      uni.showToast({ title: '对局不存在', icon: 'none' })
      return
    }
    syncSessionView(current)
  } catch (error) {
    uni.showToast({ title: error?.message || '笔记加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

async function saveSessionPatch(patch, successTitle = '已保存') {
  if (!session.value) return null
  const saved = await userData.saveSession({
    ...session.value,
    ...patch,
    updateTime: new Date().toISOString(),
  })
  syncSessionView(saved)
  if (successTitle) {
    uni.showToast({ title: successTitle, icon: 'success' })
  }
  return saved
}

function openEditor(scene, options = {}) {
  editorScene.value = scene
  editingRecord.value = options.record || null
  editorPreset.value = options.preset || null
  editorVisible.value = true
}

function closeEditor() {
  editorVisible.value = false
  editingRecord.value = null
  editorPreset.value = null
}

async function submitRecord(record) {
  if (!session.value || !record) return
  const localizedRecord = localizeSessionRecord(record)
  const records = sessionRecords.value.slice()
  const currentIndex = records.findIndex((item) => item.id === localizedRecord.id)
  if (currentIndex >= 0) {
    records[currentIndex] = localizedRecord
  } else {
    records.push(localizedRecord)
  }

  try {
    await saveSessionPatch(
      {
        records,
        currentDay: Math.max(session.value.currentDay || 1, localizedRecord.day || 1),
        currentPhase: localizedRecord.phase || session.value.currentPhase,
      },
      currentIndex >= 0 ? '记录已更新' : '记录已保存',
    )
    closeEditor()
  } catch (error) {
    uni.showToast({ title: error?.message || '记录保存失败', icon: 'none' })
  }
}

function confirmDeleteRecord(recordOrId) {
  const recordId = typeof recordOrId === 'string' ? recordOrId : recordOrId?.id
  if (!recordId || !session.value) return

  uni.showModal({
    title: '删除记录',
    content: '确认删除这条结构化记录吗？',
    success: async (res) => {
      if (!res.confirm || !session.value) return
      try {
        await saveSessionPatch(
          {
            records: sessionRecords.value.filter((item) => item.id !== recordId),
          },
          '记录已删除',
        )
      } catch (error) {
        uni.showToast({ title: error?.message || '删除失败', icon: 'none' })
      }
    },
  })
}

function editRecord(record) {
  openEditor(inferSceneFromRecord(record), { record })
}

function openBaseInfo() {
  baseInfoVisible.value = true
}

function closeBaseInfo() {
  baseInfoVisible.value = false
}

function getMaxSeatInRecords(records = []) {
  return records.reduce((maxSeat, record) => {
    const seats = [...(record.actorSeats || []), ...(record.targetSeats || [])]
    const currentMax = seats.reduce((innerMax, seat) => Math.max(innerMax, Number(seat) || 0), 0)
    return Math.max(maxSeat, currentMax)
  }, 0)
}

async function saveBaseInfo(form) {
  if (!session.value) return
  const maxSeatInRecords = getMaxSeatInRecords(sessionRecords.value)
  let playerCount = Math.max(1, Number(form.playerCount) || session.value.playerCount || 12)
  if (playerCount < maxSeatInRecords) {
    playerCount = maxSeatInRecords
    uni.showToast({ title: `已有记录涉及 ${maxSeatInRecords} 号位，人数已自动调整`, icon: 'none' })
  }

  let nextPlayers = sessionPlayers.value.slice()
  if (playerCount < nextPlayers.length) {
    nextPlayers = nextPlayers.slice(0, playerCount)
  }

  try {
    await saveSessionPatch(
      {
        boardName: form.boardName,
        playerCount,
        currentDay: Math.max(1, Number(form.currentDay) || 1),
        currentPhase: form.currentPhase,
        status: form.status,
        resultCamp: form.resultCamp,
        players: nextPlayers,
      },
      '基础信息已更新',
    )
    closeBaseInfo()
  } catch (error) {
    uni.showToast({ title: error?.message || '基础信息保存失败', icon: 'none' })
  }
}

function finishSession() {
  if (!session.value) return
  const shouldOpenBaseInfo = !session.value.resultCamp
  uni.showModal({
    title: '结束本局',
    content: '确认将本局标记为已结束吗？你仍然可以继续补充复盘信息。',
    success: async (res) => {
      if (!res.confirm) return
      try {
        await saveSessionPatch(
          {
            status: 'finished',
            currentPhase: 'result',
          },
          '已标记为结束',
        )
        if (shouldOpenBaseInfo) {
          baseInfoVisible.value = true
        }
      } catch (error) {
        uni.showToast({ title: error?.message || '结束对局失败', icon: 'none' })
      }
    },
  })
}

function openSeatActions(player) {
  if (!player) return
  activeSeatPlayer.value = player
  seatActionVisible.value = true
}

function closeSeatActions() {
  seatActionVisible.value = false
  activeSeatPlayer.value = null
}

function handleSeatAction(action) {
  const player = activeSeatPlayer.value
  const seatNo = player?.seatNo
  closeSeatActions()
  if (!seatNo) return

  if (action === 'set_sheriff') {
    openEditor('identity', { preset: { action: 'set_sheriff', targetSeat: seatNo } })
    return
  }
  if (action === 'remove_sheriff') {
    openEditor('identity', { preset: { action: 'remove_sheriff', targetSeat: seatNo } })
    return
  }
  if (action === 'mark_dead') {
    openEditor('identity', {
      preset: {
        action: 'mark_dead',
        targetSeat: seatNo,
        deathReason: session.value?.currentPhase === 'exile_vote' ? 'vote' : '',
      },
    })
    return
  }
  if (action === 'revive') {
    openEditor('identity', { preset: { action: 'revive', targetSeat: seatNo } })
    return
  }
  if (action === 'claim_role') {
    openEditor('identity', {
      preset: {
        action: 'claim_role',
        targetSeat: seatNo,
        roleName: player?.claimedRole || '',
      },
    })
    return
  }
  if (action === 'note') {
    openEditor('note', {
      preset: {
        subtype: 'player_note',
        targetSeats: [seatNo],
        remark: player?.note || '',
      },
    })
    return
  }
  if (action === 'wolf_pack') {
    openEditor('note', {
      preset: {
        subtype: 'wolf_pack',
        targetSeats: [seatNo],
      },
    })
  }
}

function jumpToSeat(seatNo) {
  if (!seatNo) return
  activeTab.value = 'seat'
  const player = sessionPlayers.value.find((item) => item.seatNo === seatNo)
  if (player) {
    openSeatActions(player)
  }
}

function openTurningPointEditor() {
  openEditor('note', {
    preset: {
      subtype: 'turning_point',
      quickTags: ['关键转折'],
      remark: '',
    },
  })
}

onLoad((options) => {
  sessionId.value = options?.id || ''
  refreshSession()
})

onShow(() => {
  if (sessionId.value) {
    refreshSession(false)
  }
})
</script>

<template>
  <view class="page-shell detail-page">
    <view v-if="loading && !session" class="empty-state glass-card section-card">对局加载中...</view>

    <template v-else-if="session">
      <SessionHeaderCard :session="session" @edit-base="openBaseInfo" @finish-session="finishSession" />
      <SessionBattleSummary
        :session="session"
        @jump-seat="jumpToSeat"
        @open-wolfpack-history="activeTab = 'timeline'"
      />

      <view class="tab-sticky">
        <SessionTabSwitcher :active-tab="activeTab" @change="activeTab = $event" />
      </view>

      <SessionOverviewPanel
        v-if="activeTab === 'overview'"
        :session="session"
        :recent-records="recentRecords"
        :key-players="keyPlayers"
        :summary="session.summary"
        @jump-seat="jumpToSeat"
        @open-record="editRecord"
      />

      <SessionSeatPanel
        v-else-if="activeTab === 'seat'"
        :players="sessionPlayers"
        @seat-click="openSeatActions"
      />

      <SessionTimelinePanel
        v-else-if="activeTab === 'timeline'"
        :groups="timelineGroups"
        @edit-record="editRecord"
        @delete-record="confirmDeleteRecord"
      />

      <SessionReviewPanel
        v-else
        :session="session"
        @edit-base="openBaseInfo"
        @add-turning-point="openTurningPointEditor"
      />

      <RecordEntryBar @open-scene="openEditor" />

      <RecordEditorSheet
        :visible="editorVisible"
        :scene="editorScene"
        :session="session"
        :players="sessionPlayers"
        :current-day="session.currentDay"
        :current-phase="session.currentPhase"
        :editing-record="editingRecord"
        :preset="editorPreset"
        @close="closeEditor"
        @submit="submitRecord"
        @delete="confirmDeleteRecord"
      />

      <SessionBaseInfoSheet
        :visible="baseInfoVisible"
        :session="session"
        @close="closeBaseInfo"
        @save="saveBaseInfo"
      />

      <SeatQuickActionSheet
        :visible="seatActionVisible"
        :player="activeSeatPlayer"
        @close="closeSeatActions"
        @action="handleSeatAction"
      />
    </template>

    <view v-else class="empty-state glass-card section-card">未找到这个对局，返回列表后可以重新进入。</view>
  </view>
</template>

<style scoped lang="scss">
.detail-page {
  padding-bottom: 240rpx;
}

.tab-sticky {
  position: sticky;
  top: 0;
  z-index: 10;
  margin-top: 8rpx;
  padding: 8rpx 0 0;
  background: linear-gradient(180deg, rgba(6, 6, 6, 0.98), rgba(6, 6, 6, 0.84) 78%, rgba(6, 6, 6, 0));
}
</style>
