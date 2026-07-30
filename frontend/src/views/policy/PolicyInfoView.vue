<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh, Search } from '@element-plus/icons-vue';
import AppTable from '../../components/common/AppTable.vue';
import EmptyState from '../../components/common/EmptyState.vue';
import StatusTag from '../../components/common/StatusTag.vue';
import { useRouter } from 'vue-router';
import { confirmPolicyArticles, createPolicyCrawlTask, createPolicySource, deletePolicySource, fetchPolicyArticles, fetchPolicyCrawlTasks, fetchPolicySources, updatePolicySource, updatePolicySourceStatus } from '../../api/policy';
import { fetchProjectSettings } from '../../api/project';
import { fetchKnowledgeBases } from '../../api/knowledge';
import { useProjectStore } from '../../stores/project';
import type { ID, PolicyArticle, PolicyCrawlTask, PolicySource, ProjectSettings } from '../../api/types';

const router = useRouter();
const projectStore = useProjectStore();
// 采集前置条件：项目已开启互联网政策资讯采集，且已配置默认知识库
const readiness = reactive({
  loading: false,
  crawlerEnabled: true,
  hasDefaultKnowledgeBase: true,
  knowledgeBaseCount: -1
});
const sourceLoading = ref(false);
const taskLoading = ref(false);
const articleLoading = ref(false);
const saving = ref(false);
const crawlingId = ref<ID | 'ALL' | ''>('');
const sourceError = ref('');
const taskError = ref('');
const articleError = ref('');
const sourceDialogVisible = ref(false);
const sources = ref<PolicySource[]>([]);
const tasks = ref<PolicyCrawlTask[]>([]);
const articles = ref<PolicyArticle[]>([]);
const articlePager = reactive({ pageNo: 1, pageSize: 10, total: 0, keyword: '', sourceId: '' as ID | '', indexStatus: '' });
const form = reactive({ sourceId: '' as ID | '', name: '', url: '', crawlFrequency: 'DAILY', autoIndex: true, description: '' });
const statusUpdatingId = ref<ID | ''>('');
const confirming = ref(false);
const selectedArticles = ref<PolicyArticle[]>([]);
const projectId = computed(() => projectStore.currentProject?.projectId || '');
const activeCrawlSourceIds = computed(() => new Set(tasks.value.filter((task) => ['QUEUED', 'RUNNING', 'RETRYING'].includes(String(task.status))).map((task) => task.sourceId || 'ALL')));
const confirmableArticles = computed(() => selectedArticles.value.filter((article) => article.indexStatus === 'PENDING_CONFIRM'));
const crawlReady = computed(() => readiness.crawlerEnabled && readiness.hasDefaultKnowledgeBase);
const readinessTitle = computed(() => {
  if (!readiness.crawlerEnabled) return '该项目尚未开启「互联网政策资讯采集」，暂时无法执行采集';
  if (!readiness.hasDefaultKnowledgeBase) return '该项目尚未配置「默认知识库」，采集结果无处入库';
  return '';
});
const readinessHint = computed(() => {
  if (!readiness.crawlerEnabled) {
    return '请到「项目管理 → 项目设置」打开「互联网政策资讯采集」开关，同时在「默认知识库」下拉框里选择一个知识库，保存后即可采集。';
  }
  if (!readiness.hasDefaultKnowledgeBase) {
    return readiness.knowledgeBaseCount === 0
      ? '该项目下还没有任何知识库。请先到「知识库管理」新建一个，再回到「项目管理 → 项目设置」的「默认知识库」下拉框里选中它。'
      : '请到「项目管理 → 项目设置」，在「默认知识库」下拉框里选择一个已启用的知识库，保存后即可采集。';
  }
  return '';
});

/**
 * 后端对采集前置条件已返回可操作的中文提示，这里只负责：
 * 识别出属于前置条件的提示时刷新页面顶部引导横幅，其余消息原样透传。
 */
function toFriendlyMessage(err: unknown, fallback: string) {
  const raw = err instanceof Error ? err.message : '';
  if (raw.includes('项目设置') || raw.includes('知识库管理')) {
    void loadReadiness();
  }
  return raw || fallback;
}

async function loadReadiness() {
  if (!projectId.value) return;
  readiness.loading = true;
  try {
    const settings: ProjectSettings = await fetchProjectSettings(projectId.value);
    readiness.crawlerEnabled = settings.internetPolicyCrawlerEnabled === true;
    readiness.hasDefaultKnowledgeBase = Boolean(settings.defaultKnowledgeBaseId);
    if (!readiness.hasDefaultKnowledgeBase) {
      const bases = await fetchKnowledgeBases(projectId.value, { pageNo: 1, pageSize: 1 });
      readiness.knowledgeBaseCount = bases.length;
    }
  } catch {
    // 前置检查失败不阻塞页面：保持按钮可用，真正采集时后端仍会拦并给出引导
    readiness.crawlerEnabled = true;
    readiness.hasDefaultKnowledgeBase = true;
  } finally {
    readiness.loading = false;
  }
}

function goProjectSettings() {
  void router.push('/projects');
}

function resetForm() {
  Object.assign(form, { sourceId: '', name: '', url: '', crawlFrequency: 'DAILY', autoIndex: true, description: '' });
}

function openCreate() {
  resetForm();
  sourceDialogVisible.value = true;
}

function openEdit(row: PolicySource) {
  Object.assign(form, {
    sourceId: row.sourceId,
    name: row.name,
    url: row.url,
    crawlFrequency: row.crawlFrequency,
    autoIndex: row.autoIndex !== false,
    description: row.description || ''
  });
  sourceDialogVisible.value = true;
}

function validateForm() {
  if (!projectId.value) return '请先选择项目';
  if (!form.name.trim()) return '请输入政策源名称';
  if (!form.url.trim()) return '请输入政策源 URL';
  if (!/^https?:\/\//i.test(form.url.trim())) return '政策源 URL 必须以 http:// 或 https:// 开头';
  return '';
}

async function loadSources() {
  sourceLoading.value = true;
  sourceError.value = '';
  try {
    if (!projectStore.currentProject) await projectStore.fetchProjects();
    if (!projectId.value) throw new Error('请先选择项目');
    const page = await fetchPolicySources({ projectId: projectId.value, pageNo: 1, pageSize: 100 });
    sources.value = page.records;
  } catch (err) {
    sources.value = [];
    sourceError.value = err instanceof Error ? err.message : '政策源加载失败';
  } finally {
    sourceLoading.value = false;
  }
}

async function loadTasks() {
  taskLoading.value = true;
  taskError.value = '';
  try {
    if (!projectId.value) throw new Error('请先选择项目');
    const page = await fetchPolicyCrawlTasks({ projectId: projectId.value, pageNo: 1, pageSize: 5 });
    tasks.value = page.records;
  } catch (err) {
    tasks.value = [];
    taskError.value = err instanceof Error ? err.message : '爬取任务加载失败';
  } finally {
    taskLoading.value = false;
  }
}

async function loadArticles() {
  articleLoading.value = true;
  articleError.value = '';
  try {
    if (!projectId.value) throw new Error('请先选择项目');
    const page = await fetchPolicyArticles({
      projectId: projectId.value,
      pageNo: articlePager.pageNo,
      pageSize: articlePager.pageSize,
      keyword: articlePager.keyword,
      sourceId: articlePager.sourceId || undefined,
      indexStatus: articlePager.indexStatus || undefined
    });
    articles.value = page.records;
    articlePager.total = page.total;
  } catch (err) {
    articles.value = [];
    articlePager.total = 0;
    articleError.value = err instanceof Error ? err.message : '政策资讯加载失败';
  } finally {
    articleLoading.value = false;
  }
}

async function refreshAll() {
  await Promise.all([loadReadiness(), loadSources(), loadTasks(), loadArticles()]);
}

async function saveSource() {
  const message = validateForm();
  if (message) return ElMessage.warning(message);
  saving.value = true;
  try {
    const payload = { name: form.name.trim(), url: form.url.trim(), crawlFrequency: form.crawlFrequency, autoIndex: form.autoIndex, description: form.description.trim() || undefined };
    if (form.sourceId) await updatePolicySource(form.sourceId, payload);
    else await createPolicySource({ projectId: projectId.value, ...payload });
    ElMessage.success(form.sourceId ? '政策源已更新' : '政策源已创建');
    sourceDialogVisible.value = false;
    await loadSources();
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '政策源保存失败');
  } finally {
    saving.value = false;
  }
}

async function removeSource(row: PolicySource) {
  try {
    await ElMessageBox.confirm(`确认删除政策源「${row.name}」？`, '删除政策源', { type: 'warning' });
    await deletePolicySource(row.sourceId);
    ElMessage.success('政策源已删除');
    await refreshAll();
  } catch (err) {
    if (err === 'cancel' || err === 'close') return;
    ElMessage.error(err instanceof Error ? err.message : '政策源删除失败');
  }
}

async function crawl(sourceId?: ID) {
  if (!projectId.value) return ElMessage.warning('请先选择项目');
  if (!crawlReady.value) return ElMessage.warning(readinessHint.value);
  if (activeCrawlSourceIds.value.has(sourceId || 'ALL')) return ElMessage.warning('该政策源已有进行中的爬取任务');
  crawlingId.value = sourceId || 'ALL';
  try {
    await createPolicyCrawlTask({ projectId: projectId.value, sourceId });
    ElMessage.success('政策资讯爬取任务已提交');
    await refreshAll();
  } catch (err) {
    ElMessage.warning(toFriendlyMessage(err, '政策资讯爬取任务提交失败'));
  } finally {
    crawlingId.value = '';
  }
}

function isCrawlingSource(sourceId: ID) {
  return crawlingId.value === sourceId || activeCrawlSourceIds.value.has(sourceId);
}

async function toggleSourceStatus(row: PolicySource) {
  const nextStatus = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
  statusUpdatingId.value = row.sourceId;
  try {
    await updatePolicySourceStatus(row.sourceId, nextStatus);
    ElMessage.success(nextStatus === 'ENABLED' ? '政策源已启用' : '政策源已停用');
    await loadSources();
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '政策源状态更新失败');
  } finally {
    statusUpdatingId.value = '';
  }
}

async function confirmSelectedArticles() {
  if (!projectId.value) return ElMessage.warning('请先选择项目');
  if (!confirmableArticles.value.length) return ElMessage.warning('请先勾选待确认的政策资讯');
  confirming.value = true;
  try {
    const result = await confirmPolicyArticles({ projectId: projectId.value, articleIds: confirmableArticles.value.map((item) => item.articleId) });
    if (result.failedCount) ElMessage.warning(`确认入库完成：成功 ${result.confirmedCount} 条，失败 ${result.failedCount} 条`);
    else ElMessage.success(`已确认入库 ${result.confirmedCount} 条`);
    selectedArticles.value = [];
    await loadArticles();
  } catch (err) {
    ElMessage.warning(toFriendlyMessage(err, '确认入库失败'));
  } finally {
    confirming.value = false;
  }
}

function searchArticles() {
  articlePager.pageNo = 1;
  void loadArticles();
}

onMounted(refreshAll);
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">政策资讯</h2>
        <p class="page-desc">配置互联网政策资讯来源，模拟爬取任务和知识更新状态，支撑知识问答的政策来源演示。</p>
      </div>
      <div class="header-actions">
        <el-button :icon="Refresh" :loading="crawlingId === 'ALL'" :disabled="activeCrawlSourceIds.has('ALL') || !crawlReady" @click="crawl()">爬取全部</el-button>
        <el-button type="primary" @click="openCreate">新增政策源</el-button>
      </div>
    </div>

    <el-alert v-if="!readiness.loading && !crawlReady" :title="readinessTitle" type="warning" show-icon :closable="false" class="readiness-alert">
      <template #default>
        <p class="readiness-hint">{{ readinessHint }}</p>
        <el-button type="primary" size="small" @click="goProjectSettings">前往项目设置</el-button>
      </template>
    </el-alert>

    <div class="two-col policy-grid">
      <el-card class="work-card">
        <template #header><strong>政策源配置</strong></template>
        <AppTable :loading="sourceLoading" :error="sourceError" :data="sources" :columns="[{ prop: 'name', label: '来源名称', slot: 'sourceName' }, { prop: 'crawlFrequency', label: '频率', width: 80 }, { prop: 'status', label: '状态', slot: 'status', width: 90 }, { prop: 'autoIndex', label: '入库方式', slot: 'autoIndex', width: 100 }]">
          <template #empty><EmptyState description="暂无政策源，可先新增一个互联网政策栏目地址。" /></template>
          <template #sourceName="{ row }"><div><strong>{{ row.name }}</strong><p class="muted url-text">{{ row.url }}</p><p v-if="row.lastError" class="muted error-text">最近失败：{{ row.lastError }}</p></div></template>
          <template #status="{ row }"><StatusTag :status="row.status" /></template>
          <template #autoIndex="{ row }"><el-tag size="small" :type="row.autoIndex === false ? 'warning' : 'success'">{{ row.autoIndex === false ? '人工确认' : '自动' }}</el-tag></template>
          <el-table-column label="操作" width="260">
            <template #default="{ row }">
              <el-button link type="primary" :loading="crawlingId === row.sourceId" :disabled="isCrawlingSource(row.sourceId) || row.status !== 'ENABLED' || !crawlReady" @click="crawl(row.sourceId)">爬取</el-button>
              <el-button link :loading="statusUpdatingId === row.sourceId" @click="toggleSourceStatus(row)">{{ row.status === 'ENABLED' ? '停用' : '启用' }}</el-button>
              <el-button link @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="removeSource(row)">删除</el-button>
            </template>
          </el-table-column>
        </AppTable>
      </el-card>

      <el-card class="work-card">
        <template #header><strong>最近爬取任务</strong></template>
        <el-alert v-if="taskError" :title="taskError" type="error" show-icon :closable="false" style="margin-bottom:12px" />
        <EmptyState v-if="!taskLoading && !tasks.length" description="暂无爬取任务" />
        <div v-for="task in tasks" :key="task.taskId" class="task-card">
          <div class="task-head"><strong>{{ task.sourceName || '全部政策源' }}</strong><StatusTag :status="task.status" /></div>
          <el-progress :percentage="task.progress || 0" :status="task.status === 'FAILED' ? 'exception' : 'success'" />
          <p class="muted">抓取 {{ task.fetchedCount }} 条 / 入库 {{ task.indexedCount }} 条，{{ task.message || '等待执行' }}</p>
        </div>
      </el-card>
    </div>

    <el-card class="work-card">
      <template #header>
        <div class="table-head">
          <strong>政策资讯列表</strong>
          <div class="filters">
            <el-input v-model="articlePager.keyword" clearable placeholder="搜索标题/摘要" style="width:220px" @keyup.enter="searchArticles" @clear="searchArticles" />
            <el-select v-model="articlePager.sourceId" clearable placeholder="政策源" style="width:180px" @change="searchArticles">
              <el-option v-for="source in sources" :key="source.sourceId" :label="source.name" :value="source.sourceId" />
            </el-select>
            <el-select v-model="articlePager.indexStatus" clearable placeholder="入库状态" style="width:140px" @change="searchArticles">
              <el-option label="待确认" value="PENDING_CONFIRM" />
              <el-option label="待入库" value="PENDING" />
              <el-option label="入库中" value="INDEXING" />
              <el-option label="已入库" value="SUCCESS" />
              <el-option label="失败" value="FAILED" />
            </el-select>
            <el-button type="primary" :icon="Search" @click="searchArticles">查询</el-button>
            <el-button type="success" :loading="confirming" :disabled="!confirmableArticles.length" @click="confirmSelectedArticles">确认入库{{ confirmableArticles.length ? `（${confirmableArticles.length}）` : '' }}</el-button>
          </div>
        </div>
      </template>
      <AppTable :loading="articleLoading" :error="articleError" :data="articles" :total="articlePager.total" :page-no="articlePager.pageNo" :page-size="articlePager.pageSize" :columns="[{ prop: 'title', label: '标题', slot: 'title' }, { prop: 'policyNo', label: '政策编号', width: 150 }, { prop: 'publishDate', label: '发布日期', width: 120 }, { prop: 'indexStatus', label: '入库状态', slot: 'indexStatus', width: 110 }]" selectable :selectable-filter="(row: PolicyArticle) => row.indexStatus === 'PENDING_CONFIRM'" @page-change="(p, s) => { articlePager.pageNo = p; articlePager.pageSize = s; loadArticles(); }" @selection-change="(rows: PolicyArticle[]) => (selectedArticles = rows)">
        <template #empty><EmptyState description="暂无政策资讯，请先触发政策源爬取。" /></template>
        <template #title="{ row }"><div><strong>{{ row.title }}</strong><p class="muted">{{ row.summary }}</p><p class="muted url-text">{{ row.url }}</p><p v-if="row.errorMessage" class="muted error-text">{{ row.errorMessage }}</p></div></template>
        <template #indexStatus="{ row }"><StatusTag :status="row.indexStatus" /></template>
      </AppTable>
    </el-card>

    <el-dialog v-model="sourceDialogVisible" title="政策源配置" width="640px">
      <el-form label-width="96px">
        <el-form-item label="来源名称" required><el-input v-model="form.name" placeholder="例如：住建部政策公开栏目" /></el-form-item>
        <el-form-item label="栏目地址" required><el-input v-model="form.url" placeholder="https://example.gov.cn/policy" /></el-form-item>
        <el-form-item label="爬取频率" required>
          <el-select v-model="form.crawlFrequency" style="width:100%">
            <el-option label="手动" value="MANUAL" />
            <el-option label="每日" value="DAILY" />
            <el-option label="每周" value="WEEKLY" />
          </el-select>
        </el-form-item>
        <el-form-item label="入库方式">
          <el-switch v-model="form.autoIndex" active-text="自动入库" inactive-text="人工确认后入库" />
          <p class="muted">关闭后，采集到的政策资讯先进入「待确认」，需人工勾选确认才写入项目知识库。</p>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" type="textarea" placeholder="记录采集范围、栏目说明或更新要求" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="sourceDialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveSource">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.header-actions, .filters, .table-head, .task-head { display: flex; align-items: center; gap: 10px; }
.header-actions { justify-content: flex-end; }
.table-head { justify-content: space-between; flex-wrap: wrap; }
.filters { flex-wrap: wrap; justify-content: flex-end; }
.policy-grid { align-items: start; }
.task-card { padding: 12px 0; border-bottom: 1px solid var(--sw-border); }
.task-card:last-child { border-bottom: 0; }
.muted { color: var(--sw-muted); font-size: 13px; line-height: 1.6; }
.url-text { word-break: break-all; margin: 4px 0 0; }
.error-text { color: var(--el-color-danger); word-break: break-all; margin: 4px 0 0; }
.readiness-alert { margin-bottom: 16px; }
.readiness-hint { margin: 4px 0 10px; line-height: 1.7; }
@media (max-width: 960px) { .table-head, .filters, .header-actions { align-items: stretch; flex-direction: column; } }
</style>
