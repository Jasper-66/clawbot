<template>
  <el-card shadow="never" style="border-radius: 16px; border: none">
    <template #header>
      <div style="display: flex; justify-content: space-between; align-items: center">
        <span style="font-weight: 600">提醒管理</span>
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px" @change="load">
          <el-option label="待发送" value="pending" />
          <el-option label="已发送" value="sent" />
          <el-option label="已取消" value="cancelled" />
        </el-select>
      </div>
    </template>

    <el-table :data="reminders" v-loading="loading" stripe style="width: 100%">
      <el-table-column prop="user_id" label="用户ID" width="140" show-overflow-tooltip />
      <el-table-column prop="content" label="提醒内容" min-width="220" show-overflow-tooltip />
      <el-table-column prop="reminder_type" label="类型" width="80">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ row.reminder_type }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="periodic" label="周期" width="80">
        <template #default="{ row }">
          <el-tag v-if="row.periodic === 1" type="success" size="small" effect="plain">周期</el-tag>
          <el-tag v-else size="small" effect="plain">单次</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="trigger_at" label="触发时间" width="180">
        <template #default="{ row }">{{ formatTime(row.trigger_at) }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80" fixed="right">
        <template #default="{ row }">
          <el-popconfirm title="确定取消该提醒？" @confirm="handleDelete(row.id)">
            <template #reference><el-button type="danger" link>取消</el-button></template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { listReminders, deleteReminder } from '../api/reminder'

const reminders = ref([])
const loading = ref(false)
const statusFilter = ref('')

const statusType = (s) => ({ pending: 'warning', sent: 'success', cancelled: 'info' }[s] || 'info')
const statusLabel = (s) => ({ pending: '待发送', sent: '已发送', cancelled: '已取消' }[s] || s)
const formatTime = (t) => t ? t.replace('T', ' ').replace('Z', '').replace(/\+.*/, '') : ''

const load = async () => {
  loading.value = true
  try { reminders.value = (await listReminders(statusFilter.value || undefined)).data.data || [] }
  catch { ElMessage.error('加载失败') }
  finally { loading.value = false }
}

const handleDelete = async (id) => {
  try { await deleteReminder(id); ElMessage.success('已取消'); load() }
  catch { ElMessage.error('操作失败') }
}

onMounted(load)
</script>
