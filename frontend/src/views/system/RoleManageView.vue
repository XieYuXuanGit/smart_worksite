<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import { ElMessage } from 'element-plus';
import * as roleApi from '../../api/role';
import type { RoleItem, PermissionItem } from '../../api/types';

const roles = ref<RoleItem[]>([]);
const permissions = ref<PermissionItem[]>([]);
const loading = ref(false);
const saving = ref(false);

const selectedRole = ref<RoleItem | null>(null);
const checkedPermissionIds = ref<(string | number)[]>([]);

const groupedPermissions = computed(() => {
  const groups: Record<string, PermissionItem[]> = {};
  for (const p of permissions.value) {
    const group = p.permissionCode.split(':')[0] || 'other';
    if (!groups[group]) groups[group] = [];
    groups[group].push(p);
  }
  return groups;
});

const groupLabels: Record<string, string> = {
  dashboard: '工作台', knowledge: '知识库', qa: '知识问答', review: '合规审查',
  report: '报告管理', ocr: 'OCR识别', system: '系统管理', project: '项目管理', file: '文件管理'
};

async function fetchData() {
  loading.value = true;
  try {
    [roles.value, permissions.value] = await Promise.all([roleApi.listRoles(), roleApi.listPermissions()]);
    if (roles.value.length) selectRole(roles.value[0]);
  } catch { /* handled */ } finally { loading.value = false; }
}

function selectRole(role: RoleItem) {
  selectedRole.value = role;
  checkedPermissionIds.value = [...role.permissionIds];
}

async function savePermissions() {
  if (!selectedRole.value) return;
  saving.value = true;
  try {
    const updated = await roleApi.assignPermissions(selectedRole.value.id, checkedPermissionIds.value);
    const idx = roles.value.findIndex(r => r.id === updated.id);
    if (idx >= 0) roles.value[idx] = updated;
    selectedRole.value = updated;
    checkedPermissionIds.value = [...updated.permissionIds];
    ElMessage.success('权限保存成功');
  } catch { /* handled */ } finally { saving.value = false; }
}

onMounted(fetchData);

const roleMap: Record<string, string> = {
  PLATFORM_ADMIN: '平台管理员', USER: '普通用户'
};
</script>

<template>
  <div>
    <div class="page-header"><h2>角色权限管理</h2></div>
    <div v-loading="loading" class="role-layout">
      <!-- 角色列表 -->
      <el-card class="role-list" shadow="never">
        <template #header><span>角色列表</span></template>
        <div v-for="role in roles" :key="role.id"
             :class="['role-item', { active: selectedRole?.id === role.id }]"
             @click="selectRole(role)">
          <div class="role-name">{{ roleMap[role.roleCode] || role.roleName }}</div>
          <div class="role-code">{{ role.roleCode }}</div>
        </div>
      </el-card>

      <!-- 权限配置 -->
      <el-card class="perm-panel" shadow="never">
        <template #header>
          <div style="display:flex;justify-content:space-between;align-items:center">
            <span>权限配置{{ selectedRole ? ` — ${roleMap[selectedRole.roleCode] || selectedRole.roleName}` : '' }}</span>
            <el-button type="primary" size="small" :loading="saving" :disabled="!selectedRole" @click="savePermissions">保存</el-button>
          </div>
        </template>
        <div v-if="!selectedRole" class="empty-tip">请选择角色</div>
        <div v-else>
          <div v-for="(perms, group) in groupedPermissions" :key="group" class="perm-group">
            <div class="group-title">{{ groupLabels[group] || group }}</div>
            <el-checkbox-group v-model="checkedPermissionIds" class="perm-checks">
              <el-checkbox v-for="p in perms" :key="p.id" :value="p.id" :label="p.id">
                {{ p.permissionName }}<span class="perm-code">{{ p.permissionCode }}</span>
              </el-checkbox>
            </el-checkbox-group>
          </div>
        </div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.page-header { display:flex;justify-content:space-between;align-items:center;margin-bottom:16px }
.page-header h2 { margin:0;font-size:18px }
.role-layout { display:flex;gap:16px;align-items:flex-start }
.role-list { width:220px;flex-shrink:0 }
.perm-panel { flex:1 }
.role-item { padding:10px 12px;border-radius:8px;cursor:pointer;margin-bottom:4px;transition:background .15s }
.role-item:hover { background:#f5f7fa }
.role-item.active { background:#ecf5ff }
.role-name { font-weight:600;font-size:14px }
.role-code { font-size:12px;color:#999;margin-top:2px }
.perm-group { margin-bottom:18px }
.group-title { font-weight:600;font-size:13px;color:#333;margin-bottom:8px;padding-bottom:4px;border-bottom:1px solid #eee }
.perm-checks { display:flex;flex-wrap:wrap;gap:8px }
.perm-checks :deep(.el-checkbox) { margin-right:0 }
.perm-code { margin-left:6px;font-size:11px;color:#aaa }
.empty-tip { color:#aaa;text-align:center;padding:40px }
</style>
