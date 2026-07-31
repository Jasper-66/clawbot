<template>
  <el-card shadow="never" style="border-radius: 16px; border: none">
    <template #header><span style="font-weight: 600">知识库检索测试</span></template>

    <el-form :inline="true" style="margin-bottom: 24px">
      <el-form-item label="查询问题" style="width: 400px">
        <el-input v-model="query" placeholder="输入问题，测试 RAG 检索效果" clearable @keyup.enter="handleSearch">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
      </el-form-item>
      <el-form-item label="Top K">
        <el-input-number v-model="topK" :min="1" :max="20" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="handleSearch" :loading="searching" :disabled="!query">
          <el-icon><Search /></el-icon> 检索
        </el-button>
      </el-form-item>
    </el-form>

    <div v-if="results.length > 0">
      <div style="font-weight: 600; margin-bottom: 16px; color: #1e1b4b">检索结果 ({{ results.length }} 条)</div>
      <div v-for="(item, index) in results" :key="index" class="result-card">
        <div style="display: flex; justify-content: space-between; margin-bottom: 10px">
          <el-tag effect="plain" size="small">来源: {{ item.title || '未知' }}</el-tag>
          <el-tag type="info" size="small">相似度: {{ item.score?.toFixed(4) || '-' }}</el-tag>
        </div>
        <div style="white-space: pre-wrap; line-height: 1.7; color: #374151; font-size: 14px">
          {{ item.content }}
        </div>
      </div>
    </div>
    <el-empty v-else-if="searched" description="未找到相关内容" />
  </el-card>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { search } from '../api/knowledge'

const query = ref('')
const topK = ref(5)
const results = ref([])
const searching = ref(false)
const searched = ref(false)

const handleSearch = async () => {
  if (!query.value) return
  searching.value = true
  searched.value = false
  try {
    const res = await search(query.value, topK.value)
    results.value = res.data.data || []
    searched.value = true
    if (results.value.length === 0) ElMessage.info('未检索到相关内容')
  } catch (e) { ElMessage.error('检索失败: ' + (e.response?.data?.message || e.message)) }
  finally { searching.value = false }
}
</script>

<style scoped>
.result-card {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  padding: 16px 20px;
  margin-bottom: 12px;
  transition: box-shadow 0.2s;
}
.result-card:hover {
  box-shadow: 0 4px 12px rgba(124, 58, 237, 0.1);
  border-color: #c4b5fd;
}
</style>
