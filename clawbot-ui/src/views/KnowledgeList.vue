<template>
  <el-card shadow="never" style="border-radius: 16px; border: none">
    <template #header>
      <div style="display: flex; justify-content: space-between; align-items: center">
        <div style="display: flex; align-items: center; gap: 12px">
          <el-input v-model="searchText" placeholder="搜索文档标题..." clearable style="width: 240px" @clear="loadDocs">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-select v-model="category" placeholder="全部分类" clearable style="width: 140px" @change="loadDocs">
            <el-option label="默认" value="default" />
            <el-option label="产品" value="product" />
            <el-option label="FAQ" value="faq" />
            <el-option label="教程" value="tutorial" />
          </el-select>
        </div>
        <el-button type="primary" @click="$router.push('/knowledge/upload')">
          <el-icon><Upload /></el-icon> 上传文档
        </el-button>
      </div>
    </template>

    <el-table :data="filteredDocs" v-loading="loading" stripe style="width: 100%">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
      <el-table-column prop="category" label="分类" width="100">
        <template #default="{ row }">
          <el-tag :type="categoryTagType(row.category)" size="small" effect="plain">{{ row.category || 'default' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="source" label="来源" width="140" show-overflow-tooltip />
      <el-table-column prop="updatedAt" label="更新时间" width="170">
        <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link @click="viewDetail(row)">查看</el-button>
          <el-popconfirm title="确定删除该文档？" @confirm="handleDelete(row.id)">
            <template #reference><el-button type="danger" link>删除</el-button></template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <!-- 详情抽屉 -->
  <el-drawer v-model="drawerVisible" :title="currentDoc?.title" size="500px">
    <div style="margin-bottom: 12px">
      <el-tag size="small" effect="plain">{{ currentDoc?.category || 'default' }}</el-tag>
      <span style="margin-left: 8px; font-size: 12px; color: #9ca3af">来源: {{ currentDoc?.source || '-' }}</span>
    </div>
    <el-divider />
    <div style="white-space: pre-wrap; line-height: 1.8; color: #374151">{{ currentDoc?.content }}</div>
  </el-drawer>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { listDocuments, getDocument, deleteDocument } from '../api/knowledge'

const documents = ref([])
const loading = ref(false)
const category = ref('')
const searchText = ref('')
const drawerVisible = ref(false)
const currentDoc = ref(null)

const filteredDocs = computed(() => {
  if (!searchText.value) return documents.value
  return documents.value.filter(d => d.title?.toLowerCase().includes(searchText.value.toLowerCase()))
})

const categoryTagType = (cat) => {
  const map = { product: 'success', faq: 'warning', tutorial: 'primary' }
  return map[cat] || 'info'
}

const loadDocs = async () => {
  loading.value = true
  try {
    const res = await listDocuments(category.value || undefined)
    documents.value = res.data.data || []
  } catch { ElMessage.error('加载失败') }
  finally { loading.value = false }
}

const viewDetail = async (row) => {
  try {
    const res = await getDocument(row.id)
    currentDoc.value = res.data.data
    drawerVisible.value = true
  } catch { ElMessage.error('获取详情失败') }
}

const handleDelete = async (id) => {
  try { await deleteDocument(id); ElMessage.success('已删除'); loadDocs() }
  catch { ElMessage.error('删除失败') }
}

const formatTime = (t) => t ? t.replace('T', ' ').replace(/\+.*/, '') : ''
onMounted(loadDocs)
</script>
