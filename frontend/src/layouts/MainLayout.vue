<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessageBox } from 'element-plus';
import { House, ChatLineRound, DocumentChecked, Files, Notebook, Picture, SwitchButton, User, UserFilled, Folder } from '@element-plus/icons-vue';
import { useProjectStore } from '../stores/project';
import { useUserStore } from '../stores/user';

const route = useRoute();
const router = useRouter();
const projectStore = useProjectStore();
const userStore = useUserStore();

const roleLabels: Record<string, string> = { PLATFORM_ADMIN: '平台管理员', USER: '普通用户' };

const menus = [
  { path: '/dashboard', title: '首页工作台', icon: House },
  { path: '/knowledge', title: '知识库管理', icon: Notebook },
  { path: '/qa', title: '知识问答', icon: ChatLineRound },
  { path: '/review', title: '合规审查', icon: DocumentChecked },
  { path: '/report', title: '报告管理', icon: Files },
  { path: '/ocr', title: 'OCR识别', icon: Picture },
  { path: '/project/manage', title: '项目管理', icon: Folder, requireAdmin: true },
  { path: '/project/members', title: '项目成员', icon: UserFilled },
  { path: '/system/users', title: '用户管理', icon: User, requireAdmin: true },
];

const visibleMenus = computed(() => menus.filter((item) => !item.requireAdmin || userStore.isPlatformAdmin));
const activeMenu = computed(() => route.path.startsWith('/report') ? '/report' : route.path);
const currentProject = computed(() => projectStore.currentProject);

onMounted(async () => {
  if (!userStore.user && userStore.token) await userStore.fetchCurrentUser();
  if (!projectStore.projects.length) await projectStore.fetchProjects();
});

async function logout() {
  await ElMessageBox.confirm('确认退出当前账号？', '退出登录', { type: 'warning' });
  await userStore.logout();
  router.replace('/login');
}
</script>

<template>
  <el-container class="main-layout">
    <el-aside width="236px" class="sidebar">
      <div class="brand"><div class="brand-mark">AI</div><div><strong>智慧工地</strong><span>大模型应用系统</span></div></div>
      <el-menu :default-active="activeMenu" router class="side-menu">
        <el-menu-item v-for="item in visibleMenus" :key="item.path" :index="item.path"><el-icon><component :is="item.icon" /></el-icon><span>{{ item.title }}</span></el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="topbar" height="64px">
        <div>
          <div class="current-project">当前项目：{{ currentProject?.projectName || '暂无项目' }}</div>
          <div class="project-meta">{{ currentProject?.projectCode || '-' }} · {{ currentProject?.location || '-' }}</div>
        </div>
        <div class="top-actions">
          <el-select v-model="projectStore.currentProjectId" style="width: 240px" :loading="projectStore.loading" @change="projectStore.switchProject">
            <el-option v-for="project in projectStore.projects" :key="project.projectId" :label="project.projectName" :value="String(project.projectId)" :disabled="project.status !== 'ENABLED'" />
          </el-select>
          <el-dropdown><span class="user-chip">{{ userStore.displayName }} / {{ roleLabels[userStore.roles[0]] || '普通用户' }}</span><template #dropdown><el-dropdown-menu><el-dropdown-item :icon="SwitchButton" @click="logout">退出登录</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
        </div>
      </el-header>
      <el-main class="content"><router-view :key="projectStore.currentProjectId" /></el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.main-layout { min-height: 100vh; background: var(--sw-bg); }
.sidebar { background: linear-gradient(180deg, #0f2f63 0%, #133a74 58%, #0f766e 135%); color: #fff; }
.brand { height: 64px; display: flex; align-items: center; gap: 12px; padding: 0 18px; border-bottom: 1px solid rgba(255,255,255,0.12); }
.brand-mark { width: 38px; height: 38px; display: grid; place-items: center; border-radius: 12px; background: linear-gradient(135deg, #1e5eff, #0f766e); font-weight: 800; }
.brand span { display: block; margin-top: 3px; font-size: 12px; color: rgba(255,255,255,0.72); }
.side-menu { border-right: 0; background: transparent; }
.side-menu :deep(.el-menu-item) { color: rgba(255,255,255,0.82); margin: 6px 10px; border-radius: 10px; }
.side-menu :deep(.el-menu-item.is-active) { background: rgba(255,255,255,0.14); color: #fff; }
.topbar { display: flex; align-items: center; justify-content: space-between; background: #fff; border-bottom: 1px solid var(--sw-border); }
.current-project { font-weight: 700; }
.project-meta { margin-top: 4px; color: var(--sw-muted); font-size: 12px; }
.top-actions { display: flex; align-items: center; gap: 14px; }
.user-chip { cursor: pointer; padding: 8px 12px; border: 1px solid var(--sw-border); border-radius: 999px; }
.content { padding: 20px; }
</style>
