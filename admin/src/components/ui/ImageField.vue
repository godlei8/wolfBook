<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../services/api'
import { resolveMediaUrl } from '../../utils/media'

// 图片字段：有图时铺预览 + 更换 / 清空；没图时是上传框，可拖拽；也可以直接贴图片链接
const model = defineModel<string>({ default: '' })
const emit = defineEmits<{ uploaded: [file: File, url: string] }>()
const props = withDefaults(
  defineProps<{
    label: string
    hint?: string
    aspect?: string
    name: string
  }>(),
  { hint: '', aspect: '16 / 9' },
)

const fileInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
const dragging = ref(false)
const editingUrl = ref(false)
const preview = computed(() => resolveMediaUrl(model.value))
const meta = ref('')

function pick() {
  fileInput.value?.click()
}

async function upload(file?: File | null) {
  if (!file) return
  if (!/^image\/(jpeg|png|gif|webp)$/.test(file.type)) {
    ElMessage.error('只能上传 jpg / png / gif / webp 图片')
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.error('图片不能超过 5MB')
    return
  }
  uploading.value = true
  try {
    const result = await api.upload(file)
    model.value = result.url
    ElMessage.success(`${props.label}已上传`)
    emit('uploaded', file, result.url)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : `${props.label}上传失败`)
  } finally {
    uploading.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}

function onDrop(event: DragEvent) {
  dragging.value = false
  void upload(event.dataTransfer?.files?.[0])
}

function onLoad(event: Event) {
  const img = event.target as HTMLImageElement
  const ext = (model.value.split('?')[0].split('.').pop() || '').toLowerCase()
  meta.value = `${img.naturalWidth} × ${img.naturalHeight}${ext.length <= 4 ? ` · ${ext}` : ''}`
}
</script>

<template>
  <div class="nt-field">
    <span class="nt-label">{{ label }} <small v-if="hint">· {{ hint }}</small></span>
    <input ref="fileInput" class="nt-sr-only" type="file" accept="image/jpeg,image/png,image/gif,image/webp" :aria-label="`上传${label}`" @change="upload(($event.target as HTMLInputElement).files?.[0])" />

    <div v-if="preview" class="image-field__frame" :style="{ aspectRatio: aspect }">
      <img :src="preview" :alt="`当前${label}`" @load="onLoad" />
      <div class="image-field__bar">
        <button class="nt-btn nt-btn--gold nt-btn--small" type="button" :disabled="uploading" @click="pick">{{ uploading ? '上传中…' : '更换' }}</button>
        <button class="nt-btn nt-btn--ghost nt-btn--small" type="button" @click="model = ''">清空</button>
        <span v-if="meta" class="image-field__meta">{{ meta }}</span>
      </div>
    </div>

    <button
      v-else
      type="button"
      class="image-field__drop"
      :class="{ 'is-dragging': dragging }"
      :style="{ aspectRatio: aspect }"
      :disabled="uploading"
      @click="pick"
      @dragover.prevent="dragging = true"
      @dragleave="dragging = false"
      @drop.prevent="onDrop"
    >
      <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 16V4M7 9l5-5 5 5" /><path d="M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3" /></svg>
      <span>{{ uploading ? '上传中…' : '拖进来，或点这里选择图片' }}</span>
      <small>jpg / png / gif / webp，5MB 以内</small>
    </button>

    <div class="image-field__url">
      <button v-if="!editingUrl" type="button" class="image-field__link" @click="editingUrl = true">直接填图片链接</button>
      <input v-else v-model.trim="model" class="nt-input" :name="name" :aria-label="`${label}链接`" placeholder="https://…" />
    </div>
  </div>
</template>

<style scoped>
.image-field__frame {
  position: relative;
  border-radius: 16px;
  overflow: hidden;
  border: 2px solid var(--ink);
  box-shadow: 4px 4px 0 var(--ink);
  background: var(--bar-bg);
}

.image-field__frame img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.image-field__bar {
  position: absolute;
  left: 10px;
  right: 10px;
  bottom: 10px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.image-field__meta {
  margin-left: auto;
  padding: 4px 10px;
  border-radius: 10px;
  background: rgba(14, 12, 19, 0.82);
  font-size: 12px;
}

.image-field__drop {
  width: 100%;
  padding: 16px;
  border-radius: 16px;
  border: 2px dashed var(--line);
  background: transparent;
  color: var(--text-3);
  font-size: 14px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  text-align: center;
  cursor: pointer;
}

.image-field__drop:hover,
.image-field__drop.is-dragging {
  border-color: var(--moon);
  color: var(--text);
}

.image-field__drop small {
  font-size: 12px;
}

.image-field__link {
  padding: 0;
  border: 0;
  background: none;
  color: var(--moon);
  font-size: 13px;
  cursor: pointer;
}
</style>
