<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import * as userApi from '../../api/user';
import * as roleApi from '../../api/role';
import type { UserItem, RoleItem } from '../../api/types';

const loading = ref(false);
const total = ref(0);
const users = ref<UserItem[]>([]);
const roles = ref<RoleItem[]>([]);
const query = reactive({ keyword: '', status: '', pageNo: 1, pageSize: 20 });

const dialogVisible = ref(false);
const dialogTitle = ref('');
const editingUserId = ref<number | string | null>(null);
const formRef = ref();
const form = reactive({
  username: '', password: '', displayName: '', phone: '', email: '', roleCodes: [] as string[]
});

const resetPwdVisible = ref(false);
const resetPwdUserId = ref<number | string | null>(null);
const newPassword = ref('');

async function fetchUsers() {
  loading.value = true;
  try {
    const res = await userApi.listUsers(query);
    users.value = res.records;
    total.value = res.total;
  } catch { /* handled by interceptor */ } finally { loading.value = false; }
}

async function fetchRoles() {
  try { roles.value = await roleApi.listRoles(); } catch { /* ignored */ }
}

onMounted(() => { fetchUsers(); fetchRoles(); });

function openCreate() {
  editingUserId.value = null;
  dialogTitle.value = '新建用户';
  Object.assign(form, { username: '', password: '', displayName: '', phone: '', email: '', roleCodes: [] });
  dialogVisible.value = true;
}

function openEdit(row: UserItem) {
  editingUserId.value = row.id;
  dialogTitle.value = '编辑用户';
  Object.assign(form, { username: row.username, password: '', displayName: row.displayName, phone: row.phone || '', email: row.email || '', roleCodes: [...row.roles] });
  dialogVisible.value = true;
}

async function submitForm() {
  await formRef.value?.validate();
  try {
    if (editingUserId.value == null) {
      await userApi.createUser({ username: form.username, password: form.password, displayName: form.displayName, phone: form.phone || undefined, email: form.email || undefined, roleCodes: form.roleCodes });
      ElMessage.success('创建成功');
    } else {
      await userApi.updateUser(editingUserId.value, { displayName: form.displayName, phone: form.phone || undefined, email: form.email || undefined, roleCodes: form.roleCodes });
      ElMessage.success('更新成功');
    }
    dialogVisible.value = false;
    fetchUsers();
  } catch { /* handled */ }
}

async function toggleStatus(row: UserItem) {
  const next = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
  const label = next === 'ENABLED' ? '启用' : '禁用';
  await ElMessageBox.confirm(`确认${label}用户 "${row.username}"？`, '提示', { type: 'warning' });
  try {
    await userApi.updateUserStatus(row.id, next);
    ElMessage.success(`${label}成功`);
    fetchUsers();
  } catch { /* handled */ }
}

function openResetPwd(row: UserItem) {
  resetPwdUserId.value = row.id;
  newPassword.value = '';
  resetPwdVisible.value = true;
}

async function submitResetPwd() {
  if (!newPassword.value || newPassword.value.length < 6) {
    ElMessage.error('密码不能少于6位'); return;
  }
  try {
    await userApi.resetPassword(resetPwdUserId.value!, newPassword.value);
    ElMessage.success('密码重置成功');
    resetPwdVisible.value = false;
  } catch { /* handled */ }
}

const formRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }, { min: 3, message: '至少3位', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }, { min: 6, message: '至少6位', trigger: 'blur' }],
  displayName: [{ required: true, message: '请输入显示名称', trigger: 'blur' }]
};

const roleMap: Record<string, string> = {
  PLATFORM_ADMIN: '平台管理员', USER: '普通用户'
};
</script>

<template>
  <div>
    <div class="page-header">
      <h2>用户管理</h2>
      <el-button type="primary" @click="openCreate">新建用户</el-button>
    </div>

    <div class="search-bar">
      <el-input v-model="query.keyword" placeholder="搜索用户名/姓名" clearable style="width:220px" @keyup.enter="fetchUsers" @clear="fetchUsers" />
      <el-select v-model="query.status" placeholder="状态筛选" clearable style="width:130px" @change="fetchUsers">
        <el-option label="启用" value="ENABLED" /><el-option label="禁用" value="DISABLED" />
      </el-select>
      <el-button type="primary" @click="fetchUsers">查询</el-button>
    </div>

    <el-table :data="users" v-loading="loading" stripe>
      <el-table-column prop="username" label="用户名" width="140" />
      <el-table-column prop="displayName" label="显示名称" width="140" />
      <el-table-column label="角色" min-width="180">
        <template #default="{ row }">
          <el-tag v-for="r in row.roles" :key="r" size="small" style="margin-right:4px">{{ roleMap[r] || r }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="phone" label="手机" width="130" />
      <el-table-column prop="email" label="邮箱" min-width="160" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ENABLED' ? 'success' : 'danger'" size="small">{{ row.status === 'ENABLED' ? '启用' : '禁用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="lastLoginAt" label="最后登录" width="160">
        <template #default="{ row }">{{ row.lastLoginAt ? row.lastLoginAt.replace('T',' ').slice(0,19) : '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link @click="toggleStatus(row)">{{ row.status === 'ENABLED' ? '禁用' : '启用' }}</el-button>
          <el-button link type="warning" @click="openResetPwd(row)">重置密码</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
      :total="total" layout="total, sizes, prev, pager, next"
      :page-sizes="[10,20,50]" style="margin-top:16px" @current-change="fetchUsers" @size-change="fetchUsers" />

    <!-- 新建/编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="500px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="100px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="editingUserId != null" />
        </el-form-item>
        <el-form-item v-if="editingUserId == null" label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password />
        </el-form-item>
        <el-form-item label="显示名称" prop="displayName">
          <el-input v-model="form.displayName" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.phone" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.roleCodes" multiple style="width:100%">
            <el-option v-for="r in roles" :key="r.roleCode" :label="roleMap[r.roleCode] || r.roleName" :value="r.roleCode" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码对话框 -->
    <el-dialog v-model="resetPwdVisible" title="重置密码" width="380px">
      <el-form label-width="100px">
        <el-form-item label="新密码">
          <el-input v-model="newPassword" type="password" show-password placeholder="至少6位" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetPwdVisible = false">取消</el-button>
        <el-button type="primary" @click="submitResetPwd">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-header h2 { margin: 0; font-size: 18px; }
.search-bar { display: flex; gap: 10px; margin-bottom: 16px; }
</style>
