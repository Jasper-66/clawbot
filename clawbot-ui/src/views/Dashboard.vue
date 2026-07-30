<template>
  <div>
    <!-- 统计卡片 -->
    <el-row :gutter="20" style="margin-bottom: 24px">
      <el-col :span="6" v-for="card in statCards" :key="card.label">
        <div class="stat-card" :style="{ background: card.bg }" @click="openDetail(card)">
          <div class="stat-icon">
            <el-icon :size="32" color="#fff"><component :is="card.icon" /></el-icon>
          </div>
          <div class="stat-info">
            <div class="stat-value">{{ card.value }}</div>
            <div class="stat-label">{{ card.label }}</div>
          </div>
          <el-icon class="stat-arrow" :size="16" color="rgba(255,255,255,0.6)"><ArrowRight /></el-icon>
        </div>
      </el-col>
    </el-row>

    <!-- 图表 + 最近消息 -->
    <el-row :gutter="20">
      <el-col :span="14">
        <el-card shadow="never" class="chart-card">
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center">
              <span style="font-weight: 600">Token 消耗趋势（近7天）</span>
              <el-tag effect="plain" size="small">总计 {{ stats.totalTokens?.toLocaleString() || 0 }} tokens</el-tag>
            </div>
          </template>
          <div ref="chartRef" style="height: 320px"></div>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card shadow="never" class="chart-card">
          <template #header><span style="font-weight: 600">最近对话</span></template>
          <div v-if="recentConversations.length === 0" style="text-align: center; color: #9ca3af; padding: 40px 0">
            暂无对话记录
          </div>
          <div v-for="conv in recentConversations" :key="conv.id" class="recent-item" @click="$router.push('/conversations/' + conv.id)">
            <div style="font-weight: 500; color: #1e1b4b">{{ conv.title || '新对话' }}</div>
            <div style="font-size: 12px; color: #9ca3af; margin-top: 4px">{{ formatTime(conv.updated_at) }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 详情抽屉 -->
    <el-drawer v-model="drawerVisible" :title="drawerTitle" size="550px">
      <div v-loading="drawerLoading">
        <div v-if="drawerData.length === 0" style="text-align: center; color: #9ca3af; padding: 40px 0">
          暂无数据
        </div>
        <div v-for="(item, i) in drawerData" :key="i" class="drawer-item">
          <div class="drawer-item-title">{{ item.title }}</div>
          <div class="drawer-item-sub">{{ item.sub }}</div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted, computed, nextTick } from 'vue'
import * as echarts from 'echarts'
import { getDashboardStats, getTokenUsage } from '../api/stats'
import { listConversations } from '../api/conversation'
import { listDocuments } from '../api/knowledge'
import { listReminders } from '../api/reminder'

const stats = ref({})
const tokenData = ref([])
const recentConversations = ref([])
const chartRef = ref()

// 抽屉相关
const drawerVisible = ref(false)
const drawerTitle = ref('')
const drawerData = ref([])
const drawerLoading = ref(false)

const statCards = computed(() => [
  { label: '知识文档', value: stats.value.documentCount || 0, icon: 'Document', bg: 'linear-gradient(135deg, #7c3aed, #a78bfa)', type: 'documents' },
  { label: '今日对话', value: stats.value.todayConversations || 0, icon: 'ChatDotRound', bg: 'linear-gradient(135deg, #6366f1, #818cf8)', type: 'todayConversations' },
  { label: '活跃提醒', value: stats.value.activeReminders || 0, icon: 'AlarmClock', bg: 'linear-gradient(135deg, #8b5cf6, #c084fc)', type: 'reminders' },
  { label: '总消息数', value: stats.value.totalMessages || 0, icon: 'Comment', bg: 'linear-gradient(135deg, #a855f7, #d8b4fe)', type: 'messages' }
])

const formatTime = (t) => t ? t.replace('T', ' ').replace(/\+.*/, '') : ''

const openDetail = async (card) => {
  drawerTitle.value = card.label + ' 详情'
  drawerVisible.value = true
  drawerLoading.value = true
  drawerData.value = []

  try {
    if (card.type === 'documents') {
      const res = await listDocuments()
      drawerData.value = (res.data.data || []).map(d => ({
        title: d.title,
        sub: `分类: ${d.category || 'default'} | 更新: ${formatTime(d.updatedAt)}`
      }))
    } else if (card.type === 'todayConversations') {
      const res = await listConversations()
      const today = new Date().toISOString().slice(0, 10)
      drawerData.value = (res.data.data || [])
        .filter(c => c.updated_at?.startsWith(today))
        .map(c => ({
          title: c.title || '新对话',
          sub: `用户: ${c.user_id} | 活跃: ${formatTime(c.updated_at)}`
        }))
    } else if (card.type === 'reminders') {
      const res = await listReminders('pending')
      drawerData.value = (res.data.data || []).map(r => ({
        title: r.content,
        sub: `用户: ${r.user_id} | 触发: ${formatTime(r.trigger_at)} | ${r.periodic === 1 ? '周期' : '单次'}`
      }))
    } else if (card.type === 'messages') {
      const res = await listConversations()
      drawerData.value = (res.data.data || []).map(c => ({
        title: c.title || '新对话',
        sub: `用户: ${c.user_id} | 创建: ${formatTime(c.created_at)}`
      }))
    }
  } catch (e) {
    console.error('加载详情失败', e)
  } finally {
    drawerLoading.value = false
  }
}

const initChart = () => {
  if (!chartRef.value || tokenData.value.length === 0) return
  const chart = echarts.init(chartRef.value)
  chart.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        let s = params[0].axisValue + '<br/>'
        params.forEach(p => {
          s += `${p.marker} ${p.seriesName}: ${p.value.toLocaleString()} tokens<br/>`
        })
        return s
      }
    },
    legend: { data: ['输入 Tokens', '输出 Tokens'], right: 10, top: 0 },
    grid: { left: '3%', right: '4%', bottom: '3%', top: '15%', containLabel: true },
    xAxis: {
      type: 'category',
      data: tokenData.value.map(d => d.date),
      axisLine: { lineStyle: { color: '#e5e7eb' } },
      axisLabel: { color: '#6b7280' }
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      splitLine: { lineStyle: { color: '#f3f4f6' } },
      axisLabel: { color: '#6b7280', formatter: (v) => v >= 1000 ? (v / 1000).toFixed(1) + 'k' : v }
    },
    series: [
      {
        name: '输入 Tokens',
        type: 'bar',
        stack: 'total',
        data: tokenData.value.map(d => d.promptTokens),
        itemStyle: { color: '#a78bfa', borderRadius: [0, 0, 0, 0] }
      },
      {
        name: '输出 Tokens',
        type: 'bar',
        stack: 'total',
        data: tokenData.value.map(d => d.completionTokens),
        itemStyle: { color: '#7c3aed', borderRadius: [4, 4, 0, 0] }
      }
    ]
  })
  window.addEventListener('resize', () => chart.resize())
}

onMounted(async () => {
  try {
    const [statsRes, tokenRes, convRes] = await Promise.all([
      getDashboardStats(),
      getTokenUsage(),
      listConversations()
    ])
    stats.value = statsRes.data.data || {}
    tokenData.value = tokenRes.data.data || []
    recentConversations.value = (convRes.data.data || []).slice(0, 5)
    await nextTick()
    initChart()
  } catch (e) {
    console.error('加载仪表盘数据失败', e)
  }
})
</script>

<style scoped>
.stat-card {
  border-radius: 16px;
  padding: 24px;
  display: flex;
  align-items: center;
  gap: 16px;
  box-shadow: 0 4px 12px rgba(124, 58, 237, 0.15);
  transition: transform 0.2s, box-shadow 0.2s;
  cursor: pointer;
  position: relative;
}
.stat-card:hover { transform: translateY(-2px); box-shadow: 0 6px 16px rgba(124, 58, 237, 0.25); }
.stat-icon {
  width: 56px; height: 56px;
  background: rgba(255,255,255,0.2);
  border-radius: 14px;
  display: flex; align-items: center; justify-content: center;
}
.stat-info { flex: 1; }
.stat-value { font-size: 28px; font-weight: 700; color: #fff; }
.stat-label { font-size: 13px; color: rgba(255,255,255,0.8); margin-top: 4px; }
.stat-arrow { position: absolute; right: 16px; top: 50%; transform: translateY(-50%); }
.chart-card { border-radius: 16px; border: none; }
.recent-item {
  padding: 12px 0;
  border-bottom: 1px solid #f3f4f6;
  cursor: pointer;
  transition: background 0.2s;
}
.recent-item:hover { background: #f8f7ff; border-radius: 8px; }
.drawer-item {
  padding: 12px 16px;
  border-bottom: 1px solid #f3f4f6;
  transition: background 0.2s;
}
.drawer-item:hover { background: #f8f7ff; }
.drawer-item-title { font-weight: 500; color: #1e1b4b; margin-bottom: 4px; }
.drawer-item-sub { font-size: 12px; color: #9ca3af; }
</style>
