import type { RouteRecordRaw } from 'vue-router';
import { createRouter, createWebHistory } from 'vue-router';
import MainLayout from '../layouts/MainLayout.vue';
import { useUserStore } from '../stores/user';

export const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: () => import('../views/login/LoginView.vue'), meta: { public: true } },
  { path: '/', component: MainLayout, redirect: '/dashboard', children: [
    { path: 'dashboard', name: 'dashboard', component: () => import('../views/dashboard/DashboardView.vue'), meta: { title: '首页工作台' } },
    { path: 'knowledge', name: 'knowledge', component: () => import('../views/knowledge/KnowledgeBaseView.vue'), meta: { title: '知识库管理' } },
    { path: 'qa', name: 'qa', component: () => import('../views/qa/QaView.vue'), meta: { title: '知识问答' } },
    { path: 'review', name: 'review', component: () => import('../views/review/ComplianceReviewView.vue'), meta: { title: '合规审查' } },
    { path: 'report', name: 'report', component: () => import('../views/report/ReportListView.vue'), meta: { title: '报告管理' } },
    { path: 'report/:id', name: 'reportDetail', component: () => import('../views/report/ReportDetailView.vue'), meta: { title: '报告详情' } },
    { path: 'ocr', name: 'ocr', component: () => import('../views/ocr/OcrView.vue'), meta: { title: 'OCR识别' } },
    { path: 'project/manage', name: 'projectManage', component: () => import('../views/project/ProjectManageView.vue'), meta: { title: '项目管理', requireAdmin: true } },
    { path: 'project/members', name: 'projectMembers', component: () => import('../views/project/ProjectMemberView.vue'), meta: { title: '项目成员' } },
    { path: 'system/users', name: 'systemUsers', component: () => import('../views/system/UserManageView.vue'), meta: { title: '用户管理', requireAdmin: true } },
    { path: 'system/roles', name: 'systemRoles', component: () => import('../views/system/RoleManageView.vue'), meta: { title: '角色权限', requireAdmin: true } },
    { path: '403', name: 'forbidden', component: () => import('../views/error/ForbiddenView.vue'), meta: { title: '无权限' } }
  ] },
  { path: '/:pathMatch(.*)*', name: 'notFound', component: () => import('../views/error/NotFoundView.vue'), meta: { public: true } }
];

export const router = createRouter({
  history: createWebHistory(),
  routes
});

router.beforeEach((to) => {
  const userStore = useUserStore();
  if (!to.meta.public && !userStore.isLoggedIn) return { path: '/login', query: { redirect: to.fullPath } };
  if (to.meta.requireAdmin && !userStore.isPlatformAdmin) return '/403';
  return true;
});
