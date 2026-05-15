<script setup lang="ts">
import { computed } from 'vue'
import type { AdminAiConfig } from '../../types'

const props = defineProps<{
  config: AdminAiConfig
  quickQuestionsText: string
  blockedKeywordsText: string
  savingConfig: boolean
}>()

const emit = defineEmits<{
  'update:quickQuestionsText': [value: string]
  'update:blockedKeywordsText': [value: string]
  save: []
}>()

const quickQuestionsModel = computed({
  get: () => props.quickQuestionsText,
  set: (value: string) => emit('update:quickQuestionsText', value),
})

const blockedKeywordsModel = computed({
  get: () => props.blockedKeywordsText,
  set: (value: string) => emit('update:blockedKeywordsText', value),
})
</script>

<template>
  <div class="form-grid">
    <el-card class="sub-card">
      <template #header>基础配置</template>
      <el-form label-position="top">
        <el-form-item label="启用 AI 助手">
          <el-switch v-model="config.base.enabled" />
        </el-form-item>
        <el-form-item label="欢迎语">
          <el-input v-model="config.base.welcomeMessage" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="推荐问题（逗号或换行分隔）">
          <el-input v-model="quickQuestionsModel" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="温度 / 推荐问题数">
          <div class="inline-grid">
            <el-input-number v-model="config.base.temperature" :min="0" :max="1.5" :step="0.05" />
            <el-input-number v-model="config.base.maxSuggestions" :min="1" :max="6" />
          </div>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="sub-card">
      <template #header>DeepSeek 配置</template>
      <el-form label-position="top">
        <el-form-item label="模型平台">
          <el-input :model-value="config.provider.platform" disabled />
        </el-form-item>
        <el-form-item label="固定模型">
          <el-input :model-value="config.provider.model" disabled />
        </el-form-item>
        <el-form-item label="API Base URL">
          <el-input v-model="config.provider.baseUrl" placeholder="https://api.deepseek.com" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input
            v-model="config.provider.apiKey"
            type="password"
            show-password
            placeholder="请输入 DeepSeek API Key"
          />
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="sub-card">
      <template #header>火山配置</template>
      <el-form label-position="top">
        <el-form-item label="Base URL">
          <el-input :model-value="config.volcengine.baseUrl" disabled />
        </el-form-item>
        <el-form-item label="Embedding 模型">
          <el-input :model-value="config.volcengine.embeddingModel" disabled />
        </el-form-item>
        <el-form-item label="Embedding Key">
          <el-input
            v-model="config.volcengine.embeddingApiKey"
            type="password"
            show-password
            placeholder="请输入火山向量模型 Key"
          />
        </el-form-item>
        <el-form-item label="联网搜索模型">
          <el-input :model-value="config.volcengine.searchModel" disabled />
        </el-form-item>
        <el-form-item label="联网搜索 Key">
          <el-input
            v-model="config.volcengine.searchApiKey"
            type="password"
            show-password
            placeholder="请输入火山联网搜索 Key"
          />
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="sub-card">
      <template #header>Prompt 与检索</template>
      <el-form label-position="top">
        <el-form-item label="系统 Prompt">
          <el-input v-model="config.prompt.systemPrompt" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item label="推荐 Prompt">
          <el-input v-model="config.prompt.recommendationPrompt" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item label="拒答 Prompt">
          <el-input v-model="config.prompt.refusalPrompt" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="TopK / 相似度 / 历史窗口">
          <div class="inline-grid inline-grid--triple">
            <el-input-number v-model="config.retrieval.topK" :min="1" :max="10" />
            <el-input-number v-model="config.retrieval.similarityThreshold" :min="0" :max="1" :step="0.05" />
            <el-input-number v-model="config.retrieval.historyWindow" :min="2" :max="20" />
          </div>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="sub-card">
      <template #header>安全与界面</template>
      <el-form label-position="top">
        <el-form-item label="联网搜索兜底">
          <el-switch v-model="config.search.webSearchEnabled" />
          <div class="helper-text">火山联网搜索的 Key 现在可以在后台配置，这里只控制是否启用兜底。</div>
        </el-form-item>
        <el-form-item label="边界提示语">
          <el-input v-model="config.safety.unsupportedMessage" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="拦截关键词（逗号或换行分隔）">
          <el-input v-model="blockedKeywordsModel" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="吉祥物">
          <el-input v-model="config.ui.mascot" />
        </el-form-item>
        <el-form-item label="悬浮标签">
          <el-input v-model="config.ui.dockLabel" />
        </el-form-item>
        <el-form-item label="强调色">
          <el-input v-model="config.ui.accentColor" />
        </el-form-item>
      </el-form>
    </el-card>
  </div>

  <div class="actions-row">
    <el-button type="primary" :loading="savingConfig" @click="emit('save')">保存 AI 配置</el-button>
  </div>
</template>

<style scoped>
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.sub-card {
  border-radius: 16px;
}

.inline-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  width: 100%;
}

.inline-grid--triple {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.actions-row {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

.helper-text {
  margin-top: 8px;
  color: rgba(255, 255, 255, 0.64);
  line-height: 1.5;
}

@media (max-width: 960px) {
  .form-grid {
    grid-template-columns: 1fr;
  }

  .inline-grid,
  .inline-grid--triple {
    grid-template-columns: 1fr;
  }
}
</style>
