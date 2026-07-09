<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import * as memberApi from '../../api/member';
import * as userApi from '../../api/user';
import type { ProjectMemberItem, UserItem } from '../../api/types';
import { useProjectStore } from '../../stores/project';

const projectStore = useProjectStore();
const projectId = ref<string | number>(projectStore.currentProjectId || '');
const members = ref<ProjectMemberItem[]>([]);
const allUsers = ref<UserItem[]>([]);
const loading = ref(false);

const dialogVisible = ref(false);
const editingUserId = ref<number | string | null>(null);
const form = ref({ userId: '' as string | number, projectRole: 'MEMBER' });

const PROJECT_ROLES = [
  { value: 'PROJECT_ADMIN', label: '项目管理员' },
  { value: 'MEMBER', label: '项目成员' },
  { value: 'VIEWER', label: '只读观察者' }
];

const roleLabel = (r: string) => PROJECT_ROLES.find(p => p.value === r)?.label || r;

async function fetchMembers() {
  if (!projectId.value) return;
  loading.value = true;
  try { members.value = await memberApi.listMembers(projectId.value); }
  catch { /* handled */ } finally { loading.value = false; }
}

async function fetchUsers() {
  try {
    const res = await userApi.listUsers({ pageSize: 100 });
    allUsers.value = res.records;
  } catch { /* handled */ }
}

onMounted(() => { fetchMembers(); fetchUsers(); });

const existingUserIds = () => new Set(members.value.map(m => String(m.userId)));

const availableUsers = () => allUsers.value.filter(u => !existingUserIds().has(String(u.id)));

function openAdd() {
  editingUserId.value = null;
  form.value = { userId: '', projectRole: 'MEMBER' };
  dialogVisible.value = true;
}

function openEdit(row: ProjectMemberItem) {
  editingUserId.value = row.userId;
  form.value = { userId: row.userId, projectRole: row.projectRole };
  dialogVisible.value = true;
}

async function submit() {
  if (!form.value.userId && editingUserId.value == null) {
    ElMessage.error('请选择用户'); return;
  }
  try {
    if (editingUserId.value == null) {
      await memberApi.addMember(projectId.value, { userId: form.value.userId, projectRole: form.value.projectRole });
      ElMessage.success('成员添加成功');
    } else {
      await memberApi.updateMember(projectId.value, editingUserId.value, { userId: editingUserId.value, projectRole: form.value.projectRole });
      ElMessage.success('角色更新成功');
    }
    dialogVisible.value = false;
    fetchMembers();
  } catch { /* handled */ }
}

async function remove(row: ProjectMemberItem) {
  await ElMessageBox.confirm(`确认移除成员 "${row.displayName || row.username}"？`, '提示', { type: 'warning' });
  try {
    await memberApi.removeMember(projectId.value, row.userId);
    ElMessage.success('已移除');
    fetchMembers();
  } catch { /* handled */ }
}
</script>

<template>
  <div>
    <div class="page-header">
      <h2>项目成员管理</h2>
      <el-button type="primary" @click="openAdd">添加成员</el-button>
    </div>

    <el-table :data="members" v-loading="loading" stripe>
      <el-table-column prop="username" label="用户名" width="140" />
      <el-table-column prop="displayName" label="显示名称" width="140" />
      <el-table-column label="项目角色" width="140">
        <template #default="{ row }">
          <el-tag size="small" :type="row.projectRole === 'PROJECT_ADMIN' ? 'primary' : row.projectRole === 'VIEWER' ? 'info' : 'success'">
            {{ roleLabel(row.projectRole) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ENABLED' ? 'success' : 'danger'" size="small">
            {{ row.status === 'ENABLED' ? '正常' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="加入时间" width="160">
        <template #default="{ row }">{{ row.createdAt?.replace('T', ' ').slice(0, 19) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">修改角色</el-button>
          <el-button link type="danger" @click="remove(row)">移除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editingUserId == null ? '添加成员' : '修改角色'" width="400px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="选择用户" v-if="editingUserId == null">
          <el-select v-model="form.userId" filterable style="width:100%">
            <el-option v-for="u in availableUsers()" :key="u.id" :label="`${u.displayName} (${u.username})`" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="项目角色">
          <el-select v-model="form.projectRole" style="width:100%">
            <el-option v-for="r in PROJECT_ROLES" :key="r.value" :label="r.label" :value="r.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-header { display:flex;justify-content:space-between;align-items:center;margin-bottom:16px }
.page-header h2 { margin:0;font-size:18px }
</style>
