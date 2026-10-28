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
      <template #header>对话模型</template>
      <div class="helper-text">
        对话模型改到「模型管理」标签页里配了：可以登记多个模型（DeepSeek 官方、学校的模型服务……），测试连接后选一个设为当前，AI 助手问答和角色起草马上改用它。
      </div>
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
      <template #header>人设与检索</template>
      <div class="helper-text helper-text--top">
        这里只写助手的人设和语气。回答格式（先给结论、不套固定段落）、只用站内资料、不替玩家站边判狼这些规矩已经内置，不用写进来。改完到「调试台」试几题再保存。
      </div>
      <el-form label-position="top">
        <el-form-item label="人设（系统 Prompt）">
          <el-input v-model="config.prompt.systemPrompt" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item label="推荐板子时的补充要求">
          <el-input v-model="config.prompt.recommendationPrompt" type="textarea" :rows="3" />
          <div class="helper-text">候选板子由程序按人数、难度、标签和阵容先挑好，模型只负责介绍，不会推荐候选以外的板子。</div>
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
      <template #header>边界</template>
      <el-form label-position="top">
        <el-form-item label="联网搜索兜底">
          <el-switch v-model="config.search.webSearchEnabled" />
          <div class="helper-text">火山联网搜索的 Key 现在可以在后台配置，这里只控制是否启用兜底。</div>
        </el-form-item>
        <el-form-item label="拦截关键词（逗号或换行分隔）">
          <el-input v-model="blockedKeywordsModel" type="textarea" :rows="3" />
          <div class="helper-text">问题里带这些词就不走模型，直接用下面的话婉拒。</div>
        </el-form-item>
        <el-form-item label="婉拒话术">
          <el-input v-model="config.safety.unsupportedMessage" type="textarea" :rows="3" />
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

.helper-text--top {
  margin: 0 0 12px;
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
