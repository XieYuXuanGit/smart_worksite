package com.xd.smartworksite.policy.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.ai.application.AiApplicationService;
import com.xd.smartworksite.ai.dto.RagIndexRequest;
import com.xd.smartworksite.ai.dto.RagIndexResponse;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.security.UserPrincipal;
import com.xd.smartworksite.knowledge.domain.KnowledgeBase;
import com.xd.smartworksite.knowledge.domain.KnowledgeBaseStatus;
import com.xd.smartworksite.knowledge.repository.KnowledgeBaseRepository;
import com.xd.smartworksite.policy.domain.PolicyArticle;
import com.xd.smartworksite.policy.domain.PolicyCrawlTask;
import com.xd.smartworksite.policy.domain.PolicyIndexStatus;
import com.xd.smartworksite.policy.domain.PolicySource;
import com.xd.smartworksite.policy.domain.PolicySourceStatus;
import com.xd.smartworksite.policy.dto.PolicyArticleConfirmRequest;
import com.xd.smartworksite.policy.dto.PolicyArticleConfirmResponse;
import com.xd.smartworksite.policy.dto.PolicyCrawlTaskCreateRequest;
import com.xd.smartworksite.policy.dto.PolicySourceStatusRequest;
import com.xd.smartworksite.policy.infra.PolicyCrawlerArticle;
import com.xd.smartworksite.policy.infra.PolicyCrawlerClient;
import com.xd.smartworksite.policy.infra.PolicyCrawlerResponse;
import com.xd.smartworksite.policy.repository.PolicyRepository;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.project.domain.Project;
import com.xd.smartworksite.task.application.TaskOutboxApplicationService;
import com.xd.smartworksite.task.domain.GenerateTask;
import com.xd.smartworksite.task.repository.TaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PolicyApplicationServiceTest {

    private static final long PROJECT_ID = 7L;
    private static final long SOURCE_ID = 21L;
    private static final long TASK_ID = 300L;
    private static final long KNOWLEDGE_BASE_ID = 55L;

    private PolicyRepository policyRepository;
    private ProjectAccessApplicationService projectAccess;
    private TaskRepository taskRepository;
    private TaskOutboxApplicationService taskOutbox;
    private PolicyCrawlerClient crawlerClient;
    private AiApplicationService aiApplicationService;
    private KnowledgeBaseRepository knowledgeBaseRepository;
    private PolicyApplicationService service;

    @BeforeEach
    void setUp() {
        policyRepository = mock(PolicyRepository.class);
        projectAccess = mock(ProjectAccessApplicationService.class);
        taskRepository = mock(TaskRepository.class);
        taskOutbox = mock(TaskOutboxApplicationService.class);
        crawlerClient = mock(PolicyCrawlerClient.class);
        aiApplicationService = mock(AiApplicationService.class);
        knowledgeBaseRepository = mock(KnowledgeBaseRepository.class);
        service = new PolicyApplicationService(policyRepository, projectAccess, taskRepository, taskOutbox,
                crawlerClient, aiApplicationService, knowledgeBaseRepository, new ObjectMapper());
        authenticateAs(9L);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(Long userId) {
        UserPrincipal principal = new UserPrincipal(userId, "user-" + userId, List.of("PROJECT_ADMIN"), List.of("policy:manage"), PROJECT_ID);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    // ---------- CRAWLER-004 启用/停用采集任务 ----------

    @Test
    void disableSourceIsRejectedWhenCrawlTaskIsStillActive() {
        givenSource(PolicySourceStatus.ENABLED.name(), true);
        when(projectAccess.requireProjectWritableManage(PROJECT_ID)).thenReturn(project(true));
        when(policyRepository.countActiveCrawlTask(SOURCE_ID)).thenReturn(1);

        assertThatThrownBy(() -> service.updateSourceStatus(SOURCE_ID, statusRequest("DISABLED")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("active crawl task");

        verify(policyRepository, never()).updateSourceStatus(anyLong(), anyString(), anyLong());
    }

    @Test
    void disableSourceUpdatesStatusWhenNoActiveTask() {
        givenSource(PolicySourceStatus.ENABLED.name(), true);
        when(projectAccess.requireProjectWritableManage(PROJECT_ID)).thenReturn(project(true));
        when(policyRepository.countActiveCrawlTask(SOURCE_ID)).thenReturn(0);
        when(policyRepository.updateSourceStatus(eq(SOURCE_ID), eq("DISABLED"), any())).thenReturn(1);

        service.updateSourceStatus(SOURCE_ID, statusRequest("DISABLED"));

        verify(policyRepository).updateSourceStatus(eq(SOURCE_ID), eq("DISABLED"), any());
    }

    @Test
    void toggleSourceStatusRejectsSameStatus() {
        givenSource(PolicySourceStatus.ENABLED.name(), true);
        when(projectAccess.requireProjectWritableManage(PROJECT_ID)).thenReturn(project(true));

        assertThatThrownBy(() -> service.updateSourceStatus(SOURCE_ID, statusRequest("ENABLED")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already");
    }

    @Test
    void toggleSourceStatusRejectsUnknownStatus() {
        givenSource(PolicySourceStatus.ENABLED.name(), true);
        when(projectAccess.requireProjectWritableManage(PROJECT_ID)).thenReturn(project(true));

        assertThatThrownBy(() -> service.updateSourceStatus(SOURCE_ID, statusRequest("PAUSED")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ENABLED or DISABLED");
    }

    // ---------- PROJECT-017 项目级采集开关 ----------

    @Test
    void createCrawlTaskIsRejectedWhenProjectCrawlerDisabled() {
        when(projectAccess.requireProjectWritableManage(PROJECT_ID)).thenReturn(project(false));
        PolicyCrawlTaskCreateRequest request = new PolicyCrawlTaskCreateRequest();
        request.setProjectId(PROJECT_ID);

        assertThatThrownBy(() -> service.createCrawlTask(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未开启「互联网政策资讯采集」");

        verify(taskRepository, never()).insertTask(any());
    }

    @Test
    void createCrawlTaskIsRejectedWhenProjectSettingsMissing() {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setSettings(null);
        when(projectAccess.requireProjectWritableManage(PROJECT_ID)).thenReturn(project);
        PolicyCrawlTaskCreateRequest request = new PolicyCrawlTaskCreateRequest();
        request.setProjectId(PROJECT_ID);

        assertThatThrownBy(() -> service.createCrawlTask(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未开启「互联网政策资讯采集」");
    }

    @Test
    void scheduledCrawlSkipsProjectsWithCrawlerDisabled() {
        PolicySource source = source(PolicySourceStatus.ENABLED.name(), true);
        when(policyRepository.findDueSources()).thenReturn(List.of(source));
        when(policyRepository.countActiveCrawlTask(SOURCE_ID)).thenReturn(0);
        when(projectAccess.requireProjectWritableForSystem(PROJECT_ID)).thenReturn(project(false));

        int created = service.createDueScheduledCrawlTasks();

        assertThat(created).isZero();
        verify(taskRepository, never()).insertTask(any());
        // 项目开关关闭属于正常跳过，不能记为来源失败
        verify(policyRepository, never()).markSourceFailed(anyLong(), anyString(), anyLong());
    }

    // ---------- CRAWLER-013 人工确认后入库 ----------

    @Test
    void crawlWithAutoIndexDisabledLeavesArticlePendingConfirmAndSkipsRag() {
        givenExecutableTask(false);
        givenCrawlerReturnsArticle();
        when(policyRepository.findArticleByProjectAndHash(eq(PROJECT_ID), anyString())).thenReturn(Optional.empty());
        givenArticlePersistenceSucceeds();
        when(policyRepository.markArticlePendingConfirm(anyLong(), any())).thenReturn(1);

        service.executeCrawlTask(TASK_ID);

        verify(policyRepository).markArticlePendingConfirm(anyLong(), any());
        verify(aiApplicationService, never()).indexKnowledgeForSystem(any());
    }

    @Test
    void crawlWithAutoIndexEnabledIndexesArticleImmediately() {
        givenExecutableTask(true);
        givenCrawlerReturnsArticle();
        when(policyRepository.findArticleByProjectAndHash(eq(PROJECT_ID), anyString())).thenReturn(Optional.empty());
        givenArticlePersistenceSucceeds();
        givenRagIndexSucceeds();

        service.executeCrawlTask(TASK_ID);

        verify(aiApplicationService).indexKnowledgeForSystem(any(RagIndexRequest.class));
        verify(policyRepository, never()).markArticlePendingConfirm(anyLong(), any());
    }

    @Test
    void confirmArticlesIndexesOnlyPendingConfirmArticles() {
        when(projectAccess.requireProjectWritableManage(PROJECT_ID)).thenReturn(project(true));
        givenEnabledKnowledgeBase();
        PolicyArticle article = article(PolicyIndexStatus.PENDING_CONFIRM.name());
        when(policyRepository.findArticlesByIds(eq(PROJECT_ID), any())).thenReturn(List.of(article));
        when(policyRepository.findArticleById(article.getId())).thenReturn(Optional.of(article));
        givenRagIndexSucceeds();

        PolicyArticleConfirmResponse response = service.confirmArticles(confirmRequest(article.getId()));

        assertThat(response.getConfirmedCount()).isEqualTo(1);
        assertThat(response.getFailedCount()).isZero();
        verify(aiApplicationService).indexKnowledgeForSystem(any(RagIndexRequest.class));
    }

    @Test
    void confirmArticlesRejectsArticleNotWaitingForConfirmation() {
        when(projectAccess.requireProjectWritableManage(PROJECT_ID)).thenReturn(project(true));
        givenEnabledKnowledgeBase();
        PolicyArticle article = article(PolicyIndexStatus.SUCCESS.name());
        when(policyRepository.findArticlesByIds(eq(PROJECT_ID), any())).thenReturn(List.of(article));

        assertThatThrownBy(() -> service.confirmArticles(confirmRequest(article.getId())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not waiting for confirmation");

        verify(aiApplicationService, never()).indexKnowledgeForSystem(any());
    }

    @Test
    void confirmArticlesRejectsArticleOutsideProject() {
        when(projectAccess.requireProjectWritableManage(PROJECT_ID)).thenReturn(project(true));
        givenEnabledKnowledgeBase();
        when(policyRepository.findArticlesByIds(eq(PROJECT_ID), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.confirmArticles(confirmRequest(999L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("do not exist in this project");
    }

    @Test
    void confirmArticlesIsRejectedWhenProjectCrawlerDisabled() {
        when(projectAccess.requireProjectWritableManage(PROJECT_ID)).thenReturn(project(false));

        assertThatThrownBy(() -> service.confirmArticles(confirmRequest(1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未开启「互联网政策资讯采集」");
    }

    // ---------- CRAWLER-019 已入库文章不回退、不重复入 RAG ----------

    @Test
    void recrawlKeepsIndexedArticleAndDoesNotReindexWhenContentUnchanged() {
        givenExecutableTask(false);
        givenCrawlerReturnsArticle();
        PolicyArticle existing = article(PolicyIndexStatus.SUCCESS.name());
        existing.setContent("政策正文内容");
        when(policyRepository.findArticleByProjectAndHash(eq(PROJECT_ID), anyString())).thenReturn(Optional.of(existing));

        service.executeCrawlTask(TASK_ID);

        // 已入库且内容未变：既不回退成待确认，也不重复送 RAG
        verify(policyRepository, never()).markArticlePendingConfirm(anyLong(), any());
        verify(policyRepository, never()).updateArticle(any());
        verify(aiApplicationService, never()).indexKnowledgeForSystem(any());
    }

    @Test
    void recrawlReprocessesIndexedArticleWhenContentChanged() {
        givenExecutableTask(true);
        givenCrawlerReturnsArticle();
        PolicyArticle existing = article(PolicyIndexStatus.SUCCESS.name());
        existing.setContent("旧的政策正文内容");
        when(policyRepository.findArticleByProjectAndHash(eq(PROJECT_ID), anyString())).thenReturn(Optional.of(existing));
        when(policyRepository.updateArticle(any())).thenReturn(1);
        when(policyRepository.findArticleById(anyLong()))
                .thenAnswer(invocation -> Optional.of(article(PolicyIndexStatus.PENDING.name())));
        givenRagIndexSucceeds();

        service.executeCrawlTask(TASK_ID);

        verify(policyRepository).updateArticle(any());
        verify(aiApplicationService).indexKnowledgeForSystem(any(RagIndexRequest.class));
    }

    // ---------- fixtures ----------

    private void givenExecutableTask(boolean autoIndex) {
        PolicyCrawlTask crawlTask = new PolicyCrawlTask();
        crawlTask.setTaskId(TASK_ID);
        crawlTask.setProjectId(PROJECT_ID);
        crawlTask.setSourceId(SOURCE_ID);
        when(policyRepository.findCrawlTaskByTaskId(TASK_ID)).thenReturn(Optional.of(crawlTask));
        when(policyRepository.markCrawlTaskRunning(eq(TASK_ID), any())).thenReturn(1);
        when(policyRepository.updateCrawlTaskProgress(eq(TASK_ID), anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(policyRepository.markSourceCrawled(eq(SOURCE_ID), any(), any())).thenReturn(1);

        GenerateTask task = new GenerateTask();
        task.setId(TASK_ID);
        task.setProjectId(PROJECT_ID);
        task.setTaskType(PolicyApplicationService.TASK_TYPE_POLICY_CRAWL);
        task.setBizId(SOURCE_ID);
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));

        when(projectAccess.requireProjectWritableForSystem(PROJECT_ID)).thenReturn(project(true));
        givenSource(PolicySourceStatus.ENABLED.name(), autoIndex);
        givenEnabledKnowledgeBase();
    }

    private void givenSource(String status, boolean autoIndex) {
        when(policyRepository.findSourceById(SOURCE_ID)).thenReturn(Optional.of(source(status, autoIndex)));
    }

    private PolicySource source(String status, boolean autoIndex) {
        PolicySource source = new PolicySource();
        source.setId(SOURCE_ID);
        source.setProjectId(PROJECT_ID);
        source.setName("示例政策栏目");
        source.setUrl("https://example.gov.cn/zhengce/");
        source.setStatus(status);
        source.setAutoIndex(autoIndex);
        return source;
    }

    private void givenCrawlerReturnsArticle() {
        PolicyCrawlerArticle item = new PolicyCrawlerArticle();
        item.setTitle("关于印发建筑施工安全生产管理办法的通知");
        item.setUrl("https://example.gov.cn/zhengce/202607/t20260728_1001.html");
        item.setSummary("摘要");
        item.setContent("政策正文内容");
        PolicyCrawlerResponse response = new PolicyCrawlerResponse();
        response.setFetchedCount(1);
        response.setFailedCount(0);
        response.setSkippedCount(0);
        response.setArticles(List.of(item));
        when(crawlerClient.crawl(any())).thenReturn(response);
    }

    private void givenArticlePersistenceSucceeds() {
        when(policyRepository.insertArticle(any())).thenAnswer(invocation -> {
            PolicyArticle inserted = invocation.getArgument(0);
            inserted.setId(901L);
            return inserted;
        });
        when(policyRepository.findArticleById(anyLong()))
                .thenAnswer(invocation -> Optional.of(article(PolicyIndexStatus.PENDING.name())));
    }

    private void givenRagIndexSucceeds() {
        when(policyRepository.markArticleIndexing(anyLong(), any())).thenReturn(1);
        when(policyRepository.markArticleIndexSuccess(anyLong(), any())).thenReturn(1);
        RagIndexResponse ragResponse = new RagIndexResponse();
        ragResponse.setIndexedDocuments(1);
        when(aiApplicationService.indexKnowledgeForSystem(any(RagIndexRequest.class))).thenReturn(ragResponse);
    }

    private void givenEnabledKnowledgeBase() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(KNOWLEDGE_BASE_ID);
        knowledgeBase.setProjectId(PROJECT_ID);
        knowledgeBase.setStatus(KnowledgeBaseStatus.ENABLED.name());
        when(knowledgeBaseRepository.findById(KNOWLEDGE_BASE_ID)).thenReturn(Optional.of(knowledgeBase));
    }

    private PolicyArticle article(String indexStatus) {
        PolicyArticle article = new PolicyArticle();
        article.setId(901L);
        article.setProjectId(PROJECT_ID);
        article.setSourceId(SOURCE_ID);
        article.setTitle("关于印发建筑施工安全生产管理办法的通知");
        article.setUrl("https://example.gov.cn/zhengce/202607/t20260728_1001.html");
        article.setContent("政策正文内容");
        article.setIndexStatus(indexStatus);
        return article;
    }

    private Project project(boolean crawlerEnabled) {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setStatus("ENABLED");
        project.setSettings("{\"projectId\":" + PROJECT_ID
                + ",\"defaultKnowledgeBaseId\":" + KNOWLEDGE_BASE_ID
                + ",\"internetPolicyCrawlerEnabled\":" + crawlerEnabled + "}");
        return project;
    }

    private PolicySourceStatusRequest statusRequest(String status) {
        PolicySourceStatusRequest request = new PolicySourceStatusRequest();
        request.setStatus(status);
        return request;
    }

    private PolicyArticleConfirmRequest confirmRequest(Long articleId) {
        PolicyArticleConfirmRequest request = new PolicyArticleConfirmRequest();
        request.setProjectId(PROJECT_ID);
        request.setArticleIds(List.of(articleId));
        return request;
    }
}
