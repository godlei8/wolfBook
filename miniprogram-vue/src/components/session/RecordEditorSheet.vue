<script setup>
import { computed, reactive, watch } from 'vue'
import { SESSION_PHASES, getPhaseLabel, getSceneLabel } from '../../utils/session/constants'
import {
  COMMON_ROLE_OPTIONS,
  DEATH_REASON_OPTIONS,
  IDENTITY_ACTION_OPTIONS,
  NIGHT_SUBTYPE_OPTIONS,
  NOTE_SUBTYPE_OPTIONS,
  SPEECH_TAG_OPTIONS,
  VOTE_SUBTYPE_OPTIONS,
  buildRecordFromForm,
  createDefaultForm,
  hydrateFormByRecord,
} from '../../utils/session/record-builder'
import { localizeSessionRecord } from '../../utils/session/display'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false,
  },
  scene: {
    type: String,
    default: 'night',
  },
  session: {
    type: Object,
    default: null,
  },
  players: {
    type: Array,
    default: () => [],
  },
  currentDay: {
    type: Number,
    default: 1,
  },
  currentPhase: {
    type: String,
    default: 'day_speech',
  },
  editingRecord: {
    type: Object,
    default: null,
  },
  preset: {
    type: Object,
    default: null,
  },
})

const emit = defineEmits(['close', 'submit', 'delete'])

const form = reactive({})

const commonQuickTagMap = {
  night: ['金水', '查杀', '刀口', '毒口', '守护'],
  speech: ['重点', '站边', '视角', '待验证'],
  vote: ['关键票', '冲票', '弃票', '归票'],
  identity: ['警长', '出局', '翻牌', '身份'],
  note: ['关键转折', '狼坑', '异常', '补充'],
}

function clonePreset(source) {
  if (!source || typeof source !== 'object') return {}
  return JSON.parse(JSON.stringify(source))
}

function assignForm(nextForm) {
  Object.keys(form).forEach((key) => {
    delete form[key]
  })
  Object.assign(form, nextForm)
}

function hydrateEditorForm() {
  const context = {
    currentDay: props.currentDay,
    currentPhase: props.currentPhase,
  }
  const nextForm = props.editingRecord
    ? hydrateFormByRecord(props.scene, props.editingRecord, context)
    : { ...createDefaultForm(props.scene, context), ...clonePreset(props.preset) }
  assignForm(nextForm)
}

watch(
  () => [props.visible, props.scene, props.editingRecord?.id, JSON.stringify(props.preset || {})],
  ([visible]) => {
    if (visible) {
      hydrateEditorForm()
    }
  },
  { immediate: true },
)

const dayOptions = computed(() => {
  const recordDays = (props.session?.records || []).map((item) => Number(item.day || item.round) || 1)
  const maxDay = Math.max(props.currentDay || 1, ...recordDays, 3)
  return Array.from({ length: Math.min(maxDay + 1, 12) }, (_, index) => index + 1)
})

const phaseOptions = computed(() => {
  if (props.scene === 'night') return ['night']
  if (props.scene === 'vote') return ['sheriff_race', 'exile_vote']
  if (props.scene === 'speech') return ['sheriff_race', 'day_speech', 'last_words']
  return SESSION_PHASES.filter((phase) => phase !== 'night' || props.scene === 'identity')
})

const seatOptions = computed(() => (props.players || []).map((item) => item.seatNo))
const quickTagOptions = computed(() => commonQuickTagMap[props.scene] || commonQuickTagMap.note)
const isEditing = computed(() => !!props.editingRecord)
const title = computed(() => `${isEditing.value ? '编辑' : '新增'}${getSceneLabel(props.scene)}记录`)

const previewRecord = computed(() => {
  try {
    const record = buildRecordFromForm(props.scene, form, {
      currentDay: props.currentDay,
      currentPhase: props.currentPhase,
      editingRecord: props.editingRecord,
    })
    return localizeSessionRecord(record)
  } catch (error) {
    return null
  }
})

function toggleListField(field, value) {
  const list = Array.isArray(form[field]) ? form[field] : []
  form[field] = list.includes(value) ? list.filter((item) => item !== value) : list.concat(value)
}

function validateForm() {
  if (!Number(form.day)) return '请选择第几天'
  if (!form.phase) return '请选择阶段'

  if (props.scene === 'night') {
    if (form.subtype === 'seer_check') {
      if (!form.actorSeat || !form.targetSeat || !form.result) return '请补全查验人、目标和结果'
    } else if (['wolf_kill', 'witch_poison', 'guard_protect'].includes(form.subtype)) {
      if (!form.targetSeat) return '请先选择目标座位'
    } else if (form.subtype === 'night_note' && !String(form.remark || '').trim()) {
      return '请填写夜间备注'
    }
  }

  if (props.scene === 'speech') {
    const hasContent = (form.speechTags || []).length || (form.targetSeats || []).length || form.claimedRole || String(form.remark || '').trim()
    if (!form.actorSeat) return '请先选择发言玩家'
    if (!hasContent) return '请至少补充一个发言要点'
  }

  if (props.scene === 'vote') {
    if (form.subtype === 'normal' && (!(form.voters || []).length || !form.targetSeat)) return '请补全投票人和目标'
    if (form.subtype === 'tie' && (form.tieTargets || []).length < 2) return '平票至少需要两个对象'
    if (form.subtype === 'abstain' && !(form.abstainSeats || []).length) return '请先选择弃票玩家'
    if (form.subtype === 'sheriff' && (!form.sheriffSeat || !form.sheriffTargetSeat)) return '请补全警长归票信息'
  }

  if (props.scene === 'identity') {
    if (!form.targetSeat) return '请先选择目标玩家'
    if (['claim_role', 'set_real_role'].includes(form.action) && !String(form.roleName || '').trim()) return '请填写身份名称'
  }

  if (props.scene === 'note') {
    if (form.subtype === 'player_note' && (!(form.targetSeats || []).length || !String(form.remark || '').trim())) {
      return '请补全备注玩家与内容'
    }
    if (form.subtype === 'wolf_pack' && !(form.targetSeats || []).length) {
      return '请至少选择一名狼坑对象'
    }
    if (['general', 'turning_point'].includes(form.subtype) && !String(form.remark || '').trim()) {
      return '请填写备注内容'
    }
  }

  return ''
}

function submit() {
  const message = validateForm()
  if (message) {
    uni.showToast({ title: message, icon: 'none' })
    return
  }
  const record = buildRecordFromForm(props.scene, form, {
    currentDay: props.currentDay,
    currentPhase: props.currentPhase,
    editingRecord: props.editingRecord,
  })
  emit('submit', localizeSessionRecord(record))
}
</script>

<template>
  <view v-if="visible" class="sheet-mask" @tap="emit('close')">
    <view class="sheet-panel glass-card" @tap.stop>
      <view class="sheet-head">
        <view>
          <view class="section-title">{{ title }}</view>
          <view class="section-desc">优先点选座位、标签和模板，系统会自动生成结构化内容。</view>
        </view>
        <view class="sheet-close" @tap="emit('close')">关闭</view>
      </view>

      <view class="sheet-block">
        <view class="section-meta">第几天</view>
        <view class="chip-grid">
          <view
            v-for="day in dayOptions"
            :key="day"
            class="chip"
            :class="{ active: form.day === day }"
            @tap="form.day = day"
          >
            第{{ day }}天
          </view>
        </view>
      </view>

      <view class="sheet-block">
        <view class="section-meta">当前阶段</view>
        <view class="chip-grid">
          <view
            v-for="phase in phaseOptions"
            :key="phase"
            class="chip"
            :class="{ active: form.phase === phase }"
            @tap="form.phase = phase"
          >
            {{ getPhaseLabel(phase) }}
          </view>
        </view>
      </view>

      <template v-if="scene === 'night'">
        <view class="sheet-block">
          <view class="section-meta">夜间类型</view>
          <view class="chip-grid">
            <view
              v-for="item in NIGHT_SUBTYPE_OPTIONS"
              :key="item.value"
              class="chip"
              :class="{ active: form.subtype === item.value }"
              @tap="form.subtype = item.value"
            >
              {{ item.label }}
            </view>
          </view>
        </view>

        <view v-if="form.subtype !== 'night_note'" class="double-grid">
          <view class="sheet-block">
            <view class="section-meta">发起座位</view>
            <view class="seat-grid">
              <view
                v-for="seat in seatOptions"
                :key="`night_actor_${seat}`"
                class="chip"
                :class="{ active: form.actorSeat === seat }"
                @tap="form.actorSeat = seat"
              >
                {{ seat }}号
              </view>
            </view>
          </view>
          <view class="sheet-block">
            <view class="section-meta">目标座位</view>
            <view class="seat-grid">
              <view
                v-for="seat in seatOptions"
                :key="`night_target_${seat}`"
                class="chip"
                :class="{ active: form.targetSeat === seat }"
                @tap="form.targetSeat = seat"
              >
                {{ seat }}号
              </view>
            </view>
          </view>
        </view>

        <view v-if="form.subtype === 'seer_check'" class="sheet-block">
          <view class="section-meta">查验结果</view>
          <view class="chip-grid">
            <view class="chip" :class="{ active: form.result === 'gold' }" @tap="form.result = 'gold'">金水</view>
            <view class="chip" :class="{ active: form.result === 'black' }" @tap="form.result = 'black'">查杀</view>
          </view>
        </view>
      </template>

      <template v-if="scene === 'speech'">
        <view class="sheet-block">
          <view class="section-meta">发言玩家</view>
          <view class="seat-grid">
            <view
              v-for="seat in seatOptions"
              :key="`speech_actor_${seat}`"
              class="chip"
              :class="{ active: form.actorSeat === seat }"
              @tap="form.actorSeat = seat"
            >
              {{ seat }}号
            </view>
          </view>
        </view>

        <view class="sheet-block">
          <view class="section-meta">发言标签</view>
          <view class="chip-grid">
            <view
              v-for="tag in SPEECH_TAG_OPTIONS"
              :key="tag"
              class="chip"
              :class="{ active: (form.speechTags || []).includes(tag) }"
              @tap="toggleListField('speechTags', tag)"
            >
              {{ tag }}
            </view>
          </view>
        </view>

        <view class="sheet-block">
          <view class="section-meta">指向座位</view>
          <view class="seat-grid">
            <view
              v-for="seat in seatOptions"
              :key="`speech_target_${seat}`"
              class="chip"
              :class="{ active: (form.targetSeats || []).includes(seat) }"
              @tap="toggleListField('targetSeats', seat)"
            >
              {{ seat }}号
            </view>
          </view>
        </view>

        <view class="sheet-block">
          <view class="section-meta">跳明身份</view>
          <scroll-view scroll-x class="chip-row">
            <view
              v-for="role in COMMON_ROLE_OPTIONS"
              :key="role"
              class="chip"
              :class="{ active: form.claimedRole === role }"
              @tap="form.claimedRole = form.claimedRole === role ? '' : role"
            >
              {{ role }}
            </view>
          </scroll-view>
        </view>
      </template>

      <template v-if="scene === 'vote'">
        <view class="sheet-block">
          <view class="section-meta">投票模板</view>
          <view class="chip-grid">
            <view
              v-for="item in VOTE_SUBTYPE_OPTIONS"
              :key="item.value"
              class="chip"
              :class="{ active: form.subtype === item.value }"
              @tap="form.subtype = item.value"
            >
              {{ item.label }}
            </view>
          </view>
        </view>

        <template v-if="form.subtype === 'normal'">
          <view class="sheet-block">
            <view class="section-meta">投票人</view>
            <view class="seat-grid">
              <view
                v-for="seat in seatOptions"
                :key="`vote_voter_${seat}`"
                class="chip"
                :class="{ active: (form.voters || []).includes(seat) }"
                @tap="toggleListField('voters', seat)"
              >
                {{ seat }}号
              </view>
            </view>
          </view>
          <view class="sheet-block">
            <view class="section-meta">投票目标</view>
            <view class="seat-grid">
              <view
                v-for="seat in seatOptions"
                :key="`vote_target_${seat}`"
                class="chip"
                :class="{ active: form.targetSeat === seat }"
                @tap="form.targetSeat = seat"
              >
                {{ seat }}号
              </view>
            </view>
          </view>
        </template>

        <template v-if="form.subtype === 'tie'">
          <view class="sheet-block">
            <view class="section-meta">平票对象</view>
            <view class="seat-grid">
              <view
                v-for="seat in seatOptions"
                :key="`vote_tie_${seat}`"
                class="chip"
                :class="{ active: (form.tieTargets || []).includes(seat) }"
                @tap="toggleListField('tieTargets', seat)"
              >
                {{ seat }}号
              </view>
            </view>
          </view>
        </template>

        <template v-if="form.subtype === 'abstain'">
          <view class="sheet-block">
            <view class="section-meta">弃票玩家</view>
            <view class="seat-grid">
              <view
                v-for="seat in seatOptions"
                :key="`vote_abstain_${seat}`"
                class="chip"
                :class="{ active: (form.abstainSeats || []).includes(seat) }"
                @tap="toggleListField('abstainSeats', seat)"
              >
                {{ seat }}号
              </view>
            </view>
          </view>
        </template>

        <template v-if="form.subtype === 'sheriff'">
          <view class="sheet-block">
            <view class="section-meta">警长座位</view>
            <view class="seat-grid">
              <view
                v-for="seat in seatOptions"
                :key="`vote_sheriff_${seat}`"
                class="chip"
                :class="{ active: form.sheriffSeat === seat }"
                @tap="form.sheriffSeat = seat"
              >
                {{ seat }}号
              </view>
            </view>
          </view>
          <view class="sheet-block">
            <view class="section-meta">归票目标</view>
            <view class="seat-grid">
              <view
                v-for="seat in seatOptions"
                :key="`vote_sheriff_target_${seat}`"
                class="chip"
                :class="{ active: form.sheriffTargetSeat === seat }"
                @tap="form.sheriffTargetSeat = seat"
              >
                {{ seat }}号
              </view>
            </view>
          </view>
          <view class="sheet-block">
            <view class="section-meta">跟票玩家</view>
            <view class="seat-grid">
              <view
                v-for="seat in seatOptions"
                :key="`vote_follow_${seat}`"
                class="chip"
                :class="{ active: (form.sheriffFollowers || []).includes(seat) }"
                @tap="toggleListField('sheriffFollowers', seat)"
              >
                {{ seat }}号
              </view>
            </view>
          </view>
        </template>
      </template>

      <template v-if="scene === 'identity'">
        <view class="sheet-block">
          <view class="section-meta">身份动作</view>
          <view class="chip-grid">
            <view
              v-for="item in IDENTITY_ACTION_OPTIONS"
              :key="item.value"
              class="chip"
              :class="{ active: form.action === item.value }"
              @tap="form.action = item.value"
            >
              {{ item.label }}
            </view>
          </view>
        </view>

        <view class="sheet-block">
          <view class="section-meta">目标玩家</view>
          <view class="seat-grid">
            <view
              v-for="seat in seatOptions"
              :key="`identity_target_${seat}`"
              class="chip"
              :class="{ active: form.targetSeat === seat }"
              @tap="form.targetSeat = seat"
            >
              {{ seat }}号
            </view>
          </view>
        </view>

        <view v-if="['claim_role', 'set_real_role'].includes(form.action)" class="sheet-block">
          <view class="section-meta">身份名称</view>
          <scroll-view scroll-x class="chip-row">
            <view
              v-for="role in COMMON_ROLE_OPTIONS"
              :key="role"
              class="chip"
              :class="{ active: form.roleName === role }"
              @tap="form.roleName = role"
            >
              {{ role }}
            </view>
          </scroll-view>
        </view>

        <view v-if="form.action === 'mark_dead'" class="sheet-block">
          <view class="section-meta">出局原因</view>
          <view class="chip-grid">
            <view
              v-for="item in DEATH_REASON_OPTIONS"
              :key="item.value"
              class="chip"
              :class="{ active: form.deathReason === item.value }"
              @tap="form.deathReason = item.value"
            >
              {{ item.label }}
            </view>
          </view>
        </view>
      </template>

      <template v-if="scene === 'note'">
        <view class="sheet-block">
          <view class="section-meta">备注类型</view>
          <view class="chip-grid">
            <view
              v-for="item in NOTE_SUBTYPE_OPTIONS"
              :key="item.value"
              class="chip"
              :class="{ active: form.subtype === item.value }"
              @tap="form.subtype = item.value"
            >
              {{ item.label }}
            </view>
          </view>
        </view>

        <view v-if="form.subtype !== 'general' && form.subtype !== 'turning_point'" class="sheet-block">
          <view class="section-meta">关联座位</view>
          <view class="seat-grid">
            <view
              v-for="seat in seatOptions"
              :key="`note_target_${seat}`"
              class="chip"
              :class="{ active: (form.targetSeats || []).includes(seat) }"
              @tap="toggleListField('targetSeats', seat)"
            >
              {{ seat }}号
            </view>
          </view>
        </view>
      </template>

      <view class="sheet-block">
        <view class="section-meta">快捷标签</view>
        <view class="chip-grid">
          <view
            v-for="tag in quickTagOptions"
            :key="tag"
            class="chip"
            :class="{ active: (form.quickTags || []).includes(tag) }"
            @tap="toggleListField('quickTags', tag)"
          >
            {{ tag }}
          </view>
        </view>
      </view>

      <view class="sheet-block">
        <view class="section-meta">备注补充</view>
        <textarea
          v-model="form.remark"
          class="field-textarea editor-textarea"
          auto-height
          placeholder="补充一句备注，系统会自动拼接成可读内容"
        />
      </view>

      <view class="sheet-block">
        <view class="section-meta">预览</view>
        <view class="preview-card">{{ previewRecord?.content || '补全表单后会在这里生成预览。' }}</view>
      </view>

      <button v-if="isEditing" class="button-danger secondary-btn" @tap="emit('delete', editingRecord?.id)">删除这条记录</button>
      <button class="button-primary submit-btn" @tap="submit">{{ isEditing ? '保存修改' : '保存记录' }}</button>
    </view>
  </view>
</template>

<style scoped lang="scss">
.sheet-mask {
  position: fixed;
  inset: 0;
  z-index: 33;
  background: rgba(0, 0, 0, 0.64);
  display: flex;
  align-items: flex-end;
}

.sheet-panel {
  width: 100%;
  max-height: 88vh;
  padding: 28rpx 24rpx calc(env(safe-area-inset-bottom) + 26rpx);
  border-radius: 28rpx 28rpx 0 0;
  overflow-y: auto;
}

.sheet-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 18rpx;
}

.sheet-close {
  padding: 10rpx 18rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.06);
  color: #d8d8d8;
  font-size: 22rpx;
}

.sheet-block {
  margin-top: 20rpx;
}

.double-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14rpx;
}

.chip-grid,
.seat-grid {
  margin-top: 12rpx;
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}

.editor-textarea {
  min-height: 160rpx;
}

.preview-card {
  margin-top: 12rpx;
  padding: 18rpx 20rpx;
  border-radius: 18rpx;
  background: rgba(255, 192, 0, 0.08);
  color: #f3d27a;
  font-size: 24rpx;
  line-height: 1.7;
}

.secondary-btn {
  margin-top: 20rpx;
  height: 76rpx;
  line-height: 76rpx;
  border-radius: 16rpx;
  font-size: 24rpx;
  font-weight: 700;
}

.submit-btn {
  margin-top: 16rpx;
}
</style>
