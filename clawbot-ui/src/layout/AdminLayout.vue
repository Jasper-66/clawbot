<template>
  <el-container style="height: 100vh">
    <!-- 左侧边栏 -->
    <el-aside :width="isCollapse ? '64px' : '220px'" class="sidebar">
      <div class="logo">
        <el-icon :size="28" color="#c4b5fd"><Cpu /></el-icon>
        <span v-show="!isCollapse" class="logo-text">ClawBot</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        :collapse="isCollapse"
        background-color="transparent"
        text-color="#e0d7fc"
        active-text-color="#ffffff"
        router
        class="sidebar-menu"
      >
        <el-menu-item index="/dashboard">
          <el-icon><DataBoard /></el-icon>
          <template #title>仪表盘</template>
        </el-menu-item>

        <el-sub-menu index="knowledge-group">
          <template #title>
            <el-icon><Collection /></el-icon>
            <span>知识库</span>
          </template>
          <el-menu-item index="/knowledge">文档管理</el-menu-item>
          <el-menu-item index="/knowledge/upload">上传文档</el-menu-item>
          <el-menu-item index="/knowledge/search">检索测试</el-menu-item>
        </el-sub-menu>

        <el-menu-item index="/conversations">
          <el-icon><ChatDotRound /></el-icon>
          <template #title>对话管理</template>
        </el-menu-item>

        <el-menu-item index="/reminders">
          <el-icon><AlarmClock /></el-icon>
          <template #title>提醒管理</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <!-- 右侧内容区 -->
    <el-container>
      <el-header class="topbar">
        <div style="display: flex; align-items: center; gap: 16px">
          <el-icon class="collapse-btn" @click="isCollapse = !isCollapse" :size="20">
            <component :is="isCollapse ? 'Expand' : 'Fold'" />
          </el-icon>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item>{{ currentTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div style="display: flex; align-items: center; gap: 12px">
          <el-tag type="info" effect="plain" size="small">v2.0</el-tag>
        </div>
      </el-header>

      <el-main class="main-content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const isCollapse = ref(false)
const activeMenu = computed(() => route.path)
const currentTitle = computed(() => route.meta?.title || '')
</script>

<style scoped>
.sidebar {
  background: linear-gradient(180deg, #6d28d9 0%, #4c1d95 100%);
  transition: width 0.3s;
  overflow: hidden;
}

.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.logo-text {
  color: #fff;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 2px;
  white-space: nowrap;
}

.sidebar-menu {
  border-right: none;
  margin-top: 8px;
}

.sidebar-menu .el-menu-item,
.sidebar-menu :deep(.el-sub-menu__title) {
  height: 48px;
  line-height: 48px;
  border-radius: 8px;
  margin: 2px 8px;
}

.sidebar-menu .el-menu-item:hover,
.sidebar-menu :deep(.el-sub-menu__title:hover) {
  background: rgba(255, 255, 255, 0.1) !important;
}

.sidebar-menu .el-menu-item.is-active {
  background: rgba(255, 255, 255, 0.2) !important;
  font-weight: 600;
}

.topbar {
  height: 60px;
  background: #fff;
  border-bottom: 1px solid #e5e7eb;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}

.collapse-btn {
  cursor: pointer;
  color: #6b7280;
  transition: color 0.2s;
}
.collapse-btn:hover {
  color: #7c3aed;
}

.main-content {
  background: #f8f7ff;
  padding: 24px;
  overflow-y: auto;
}
</style>
