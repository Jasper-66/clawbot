<template>
  <el-card shadow="never" style="border-radius: 16px; border: none; max-width: 700px">
    <template #header><span style="font-weight: 600">添加知识库文档</span></template>

    <el-tabs v-model="mode">
      <el-tab-pane label="文件上传" name="file">
        <el-form label-width="80px" style="margin-top: 16px">
          <el-form-item label="分类">
            <el-select v-model="fileForm.category" placeholder="选择分类">
              <el-option label="默认" value="default" />
              <el-option label="产品" value="product" />
              <el-option label="FAQ" value="faq" />
              <el-option label="教程" value="tutorial" />
            </el-select>
          </el-form-item>
          <el-form-item label="文件">
            <el-upload
              ref="uploadRef"
              :auto-upload="false"
              :limit="1"
              accept=".pdf,.doc,.docx,.txt,.md,.html,.csv,.xls,.xlsx,.ppt,.pptx"
              :on-change="onFileChange"
              drag
              style="width: 100%"
            >
              <el-icon :size="48" style="color: #a78bfa"><Upload /></el-icon>
              <div style="margin-top: 8px">拖拽文件到此处，或 <em style="color: #7c3aed">点击选择</em></div>
              <template #tip>
                <div style="font-size: 12px; color: #9ca3af; margin-top: 4px">支持 PDF、Word、TXT、Markdown、HTML 等格式</div>
              </template>
            </el-upload>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="handleUpload" :loading="uploading" :disabled="!selectedFile">
              <el-icon><Upload /></el-icon> 上传并解析
            </el-button>
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <el-tab-pane label="手动输入" name="text">
        <el-form label-width="80px" style="margin-top: 16px">
          <el-form-item label="标题">
            <el-input v-model="textForm.title" placeholder="文档标题" />
          </el-form-item>
          <el-form-item label="分类">
            <el-select v-model="textForm.category" placeholder="选择分类">
              <el-option label="默认" value="default" />
              <el-option label="产品" value="product" />
              <el-option label="FAQ" value="faq" />
              <el-option label="教程" value="tutorial" />
            </el-select>
          </el-form-item>
          <el-form-item label="来源">
            <el-input v-model="textForm.source" placeholder="来源标识（可选）" />
          </el-form-item>
          <el-form-item label="内容">
            <el-input v-model="textForm.content" type="textarea" :rows="10" placeholder="输入文档内容..." />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="handleAddText" :loading="adding" :disabled="!textForm.title || !textForm.content">
              <el-icon><Plus /></el-icon> 添加到知识库
            </el-button>
          </el-form-item>
        </el-form>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { uploadFile, addDocument } from '../api/knowledge'

const mode = ref('file')
const selectedFile = ref(null)
const uploading = ref(false)
const adding = ref(false)
const fileForm = ref({ category: 'default' })
const textForm = ref({ title: '', content: '', source: '', category: 'default' })

const onFileChange = (file) => { selectedFile.value = file.raw }

const handleUpload = async () => {
  if (!selectedFile.value) return
  uploading.value = true
  try {
    await uploadFile(selectedFile.value, fileForm.value.category)
    ElMessage.success('上传成功，已解析并添加到知识库')
    selectedFile.value = null
  } catch (e) { ElMessage.error('上传失败: ' + (e.response?.data?.message || e.message)) }
  finally { uploading.value = false }
}

const handleAddText = async () => {
  adding.value = true
  try {
    await addDocument(textForm.value)
    ElMessage.success('已添加到知识库')
    textForm.value = { title: '', content: '', source: '', category: 'default' }
  } catch (e) { ElMessage.error('添加失败: ' + (e.response?.data?.message || e.message)) }
  finally { adding.value = false }
}
</script>
