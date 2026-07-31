import { createRouter, createWebHistory } from 'vue-router'
import AdminLayout from '../layout/AdminLayout.vue'

const routes = [
  {
    path: '/',
    component: AdminLayout,
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'Dashboard', component: () => import('../views/Dashboard.vue'), meta: { title: '仪表盘' } },
      { path: 'knowledge', name: 'KnowledgeList', component: () => import('../views/KnowledgeList.vue'), meta: { title: '知识库管理' } },
      { path: 'knowledge/upload', name: 'KnowledgeUpload', component: () => import('../views/KnowledgeUpload.vue'), meta: { title: '上传文档' } },
      { path: 'knowledge/search', name: 'SearchTest', component: () => import('../views/SearchTest.vue'), meta: { title: '检索测试' } },
      { path: 'conversations', name: 'Conversations', component: () => import('../views/ConversationList.vue'), meta: { title: '对话管理' } },
      { path: 'conversations/:id', name: 'ConversationDetail', component: () => import('../views/ConversationDetail.vue'), meta: { title: '对话详情' } },
      { path: 'reminders', name: 'Reminders', component: () => import('../views/ReminderList.vue'), meta: { title: '提醒管理' } }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
