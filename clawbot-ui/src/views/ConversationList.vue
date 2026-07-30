<template>
  <el-card shadow="never" style="border-radius: 16px; border: none">
    <template #header><span style="font-weight: 600">对话管理</span></template>

    <el-table :data="conversations" v-loading="loading" stripe style="width: 100%">
      <el-table-column prop="user_id" label="用户ID" width="160" show-overflow-tooltip />
      <el-table-column prop="title" label="会话标题" min-width="250" show-overflow-tooltip />
      <el-table-column prop="created_at" label="创建时间" width="170">
        <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
      </el-table-column>
      <el-table-column prop="updated_at" label="最后活跃" width="170">
        <template #default="{ row }">{{ formatTime(row.updated_at) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link @click="$router.push('/conversations/' + row.id)">查看消息</el-button>
          <el-popconfirm title="确定删除该会话及所有消息？" @confirm="handleDelete(row.id)">
            <template #reference><el-button type="danger" link>删除</el-button></template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { listConversations, deleteConversation } from '../api/conversation'

const conversations = ref([])
const loading = ref(false)

const load = async () => {
  loading.value = true
  try { conversations.value = (await listConversations()).data.data || [] }
  catch { ElMessage.error('加载失败') }
  finally { loading.value = false }
}

const handleDelete = async (id) => {
  try { await deleteConversation(id); ElMessage.success('已删除'); load() }
  catch { ElMessage.error('删除失败') }
}

const formatTime = (t) => t ? t.replace('T', ' ').replace(/\+.*/, '') : ''
onMounted(load)
</script>
