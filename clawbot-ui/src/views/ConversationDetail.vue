<template>
  <div>
    <el-page-header @back="$router.push('/conversations')" style="margin-bottom: 20px">
      <template #content>
        <span style="font-weight: 600">对话详情</span>
      </template>
    </el-page-header>

    <el-card shadow="never" style="border-radius: 16px; border: none" v-loading="loading">
      <div v-if="messages.length === 0" style="text-align: center; color: #9ca3af; padding: 60px 0">
        暂无消息记录
      </div>

      <div v-for="(msg, i) in messages" :key="i" class="message-row" :class="msg.role">
        <div class="message-avatar">
          <el-icon :size="20" :color="msg.role === 'user' ? '#7c3aed' : '#10b981'">
            <component :is="msg.role === 'user' ? 'User' : 'ChatDotRound'" />
          </el-icon>
        </div>
        <div class="message-body">
          <div class="message-role">{{ msg.role === 'user' ? '用户' : 'AI 助手' }}</div>
          <div class="message-content">{{ msg.content }}</div>
          <div class="message-time">{{ formatTime(msg.created_at) }}</div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getMessages } from '../api/conversation'

const route = useRoute()
const messages = ref([])
const loading = ref(false)

const formatTime = (t) => t ? t.replace('T', ' ').replace(/\+.*/, '') : ''

onMounted(async () => {
  loading.value = true
  try { messages.value = (await getMessages(route.params.id)).data.data || [] }
  catch { ElMessage.error('加载消息失败') }
  finally { loading.value = false }
})
</script>

<style scoped>
.message-row {
  display: flex;
  gap: 12px;
  padding: 16px 0;
  border-bottom: 1px solid #f3f4f6;
}
.message-row:last-child { border-bottom: none; }
.message-avatar {
  width: 40px; height: 40px;
  border-radius: 10px;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.message-row.user .message-avatar { background: #ede9fe; }
.message-row.assistant .message-avatar { background: #d1fae5; }
.message-role { font-size: 12px; font-weight: 600; color: #6b7280; margin-bottom: 6px; }
.message-content {
  background: #f8f7ff;
  padding: 12px 16px;
  border-radius: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  color: #1e1b4b;
  font-size: 14px;
}
.message-row.user .message-content { background: #ede9fe; }
.message-time { font-size: 11px; color: #9ca3af; margin-top: 6px; }
</style>
