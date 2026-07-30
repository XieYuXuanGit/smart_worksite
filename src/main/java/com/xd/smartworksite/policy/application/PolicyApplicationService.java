package com.xd.smartworksite.policy.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.xd.smartworksite.ai.application.AiApplicationService;
import com.xd.smartworksite.ai.dto.RagDocumentRequest;
import com.xd.smartworksite.ai.dto.RagIndexRequest;
import com.xd.smartworksite.ai.dto.RagIndexResponse;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.common.result.PageResult;
import com.xd.smartworksite.common.security.SecurityUtils;
import com.xd.smartworksite.knowledge.domain.KnowledgeBase;
import com.xd.smartworksite.knowledge.domain.KnowledgeBaseStatus;
import com.xd.smartworksite.knowledge.repository.KnowledgeBaseRepository;
import com.xd.smartworksite.policy.domain.*;
import com.xd.smartworksite.policy.dto.*;
import com.xd.smartworksite.policy.infra.PolicyCrawlerArticle;
import com.xd.smartworksite.policy.infra.PolicyCrawlerClient;
import com.xd.smartworksite.policy.infra.PolicyCrawlerRequest;
import com.xd.smartworksite.policy.infra.PolicyCrawlerResponse;
import com.xd.smartworksite.policy.repository.PolicyRepository;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.project.domain.Project;
import com.xd.smartworksite.project.dto.ProjectSettingsResponse;
import com.xd.smartworksite.task.application.TaskOutboxApplicationService;
import com.xd.smartworksite.task.domain.GenerateTask;
import com.xd.smartworksite.task.domain.TaskStatus;
import com.xd.smartworksite.task.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PolicyApplicationService {
    public static final String TASK_TYPE_POLICY_CRAWL = "POLICY_CRAWL";
    private static final String BIZ_TYPE_POLICY_SOURCE = "POLICY_SOURCE";
    private static final String STAGE_POLICY_CRAWL_QUEUED = "POLICY_CRAWL_QUEUED";
    private static final Long SYSTEM_USER_ID = 1L;
    private static final int MAX_ERROR_LENGTH = 2000;

    private final PolicyRepository policyRepository;
    private final ProjectAccessApplicationService projectAccessApplicationService;
    private final TaskRepository taskRepository;
    private final TaskOutboxApplicationService taskOutboxApplicationService;
    private final PolicyCrawlerClient policyCrawlerClient;
    private final AiApplicationService aiApplicationService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final ObjectMapper objectMapper;

    public PolicyApplicationService(PolicyRepository policyRepository,
                                    ProjectAccessApplicationService projectAccessApplicationService,
                                    TaskRepository taskRepository,
                                    TaskOutboxApplicationService taskOutboxApplicationService,
                                    PolicyCrawlerClient policyCrawlerClient,
                                    AiApplicationService aiApplicationService,
                                    KnowledgeBaseRepository knowledgeBaseRepository,
                                    ObjectMapper objectMapper) {
        this.policyRepository = policyRepository;
        this.projectAccessApplicationService = projectAccessApplicationService;
        this.taskRepository = taskRepository;
        this.taskOutboxApplicationService = taskOutboxApplicationService;
        this.policyCrawlerClient = policyCrawlerClient;
        this.aiApplicationService = aiApplicationService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.objectMapper = objectMapper;
    }

    public PageResult<PolicySourceResponse> querySources(PolicySourceQueryRequest request) {
        if (request.getProjectId() != null) {
            projectAccessApplicationService.requireProjectAccess(request.getProjectId());
        }
        List<Long> accessibleProjectIds = accessibleProjectIdsWhenNoProject(request.getProjectId());
        if (request.getProjectId() == null && accessibleProjectIds != null && accessibleProjectIds.isEmpty()) {
            return new PageResult<>(request.getPageNo(), request.getPageSize(), 0, List.of());
        }
        String status = normalizeOptionalSourceStatus(request.getStatus());
        Page<PolicySource> page = PageHelper.startPage(request.getPageNo(), request.getPageSize())
                .doSelectPage(() -> policyRepository.findSources(request.getProjectId(), accessibleProjectIds, trimToNull(request.getKeyword()), status));
        return new PageResult<>(request.getPageNo(), request.getPageSize(), page.getTotal(), page.getResult().stream().map(this::toSourceResponse).toList());
    }

    @Transactional
    public PolicySourceResponse createSource(PolicySourceRequest request) {
        projectAccessApplicationService.requireProjectWritableManage(request.getProjectId());
        String url = normalizeUrl(request.getUrl());
        String urlHash = sha256(url);
        policyRepository.findSourceByProjectAndHash(request.getProjectId(), urlHash).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.CONFLICT, "policy source url already exists in this project");
        });
        PolicySource source = new PolicySource();
        source.setProjectId(request.getProjectId());
        source.setName(normalizeRequired(request.getName(), "name is required"));
        source.setUrl(url);
        source.setUrlHash(urlHash);
        source.setCrawlFrequency(normalizeFrequency(request.getCrawlFrequency()));
        source.setStatus(PolicySourceStatus.ENABLED.name());
        source.setAutoIndex(!Boolean.FALSE.equals(request.getAutoIndex()));
        source.setDescription(trimToNull(request.getDescription()));
        source.setCreatedBy(SecurityUtils.getCurrentUserId());
        source.setUpdatedBy(SecurityUtils.getCurrentUserId());
        policyRepository.insertSource(source);
        if (source.getId() == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "policy source id was not generated");
        }
        return toSourceResponse(requireSource(source.getId()));
    }

    @Transactional
    public PolicySourceResponse updateSource(Long sourceId, PolicySourceUpdateRequest request) {
        PolicySource source = requireSourceManage(sourceId);
        String url = normalizeUrl(request.getUrl());
        String urlHash = sha256(url);
        policyRepository.findSourceByProjectAndHash(source.getProjectId(), urlHash)
                .filter(existing -> !existing.getId().equals(sourceId))
                .ifPresent(existing -> { throw new BusinessException(ErrorCode.CONFLICT, "policy source url already exists in this project"); });
        source.setName(normalizeRequired(request.getName(), "name is required"));
        source.setUrl(url);
        source.setUrlHash(urlHash);
        source.setCrawlFrequency(normalizeFrequency(request.getCrawlFrequency()));
        source.setAutoIndex(request.getAutoIndex() == null ? !Boolean.FALSE.equals(source.getAutoIndex()) : request.getAutoIndex());
        source.setDescription(trimToNull(request.getDescription()));
        source.setUpdatedBy(SecurityUtils.getCurrentUserId());
        requireUpdated(policyRepository.updateSource(source), "policy source update failed");
        return toSourceResponse(requireSource(sourceId));
    }

    /**
     * CRAWLER-004：启用或停用采集任务。停用前拦截进行中的采集任务，避免任务执行到一半来源被关闭。
     */
    @Transactional
    public PolicySourceResponse updateSourceStatus(Long sourceId, PolicySourceStatusRequest request) {
        PolicySource source = requireSourceManage(sourceId);
        String status = normalizeRequiredSourceStatus(request.getStatus());
        if (status.equals(source.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "policy source status is already " + status);
        }
        if (PolicySourceStatus.DISABLED.name().equals(status) && policyRepository.countActiveCrawlTask(sourceId) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "policy source has active crawl task and cannot be disabled");
        }
        requireUpdated(policyRepository.updateSourceStatus(sourceId, status, SecurityUtils.getCurrentUserId()),
                "policy source status update failed");
        return toSourceResponse(requireSource(sourceId));
    }

    @Transactional
    public void deleteSource(Long sourceId) {
        requireSourceManage(sourceId);
        requireUpdated(policyRepository.softDeleteSource(sourceId, SecurityUtils.getCurrentUserId()), "policy source delete failed");
    }

    @Transactional
    public PolicyCrawlTaskResponse createCrawlTask(PolicyCrawlTaskCreateRequest request) {
        Project project = projectAccessApplicationService.requireProjectWritableManage(request.getProjectId());
        requirePolicyCrawlerEnabled(project);
        ensureDefaultKnowledgeBase(project);
        PolicySource source = null;
        if (request.getSourceId() != null) {
            source = requireSourceManage(request.getSourceId());
            if (!source.getProjectId().equals(request.getProjectId())) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "source does not belong to project");
            }
            if (!PolicySourceStatus.ENABLED.name().equals(source.getStatus())) {
                throw new BusinessException(ErrorCode.CONFLICT, "policy source is disabled");
            }
            if (policyRepository.countActiveCrawlTask(source.getId()) > 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "policy source already has active crawl task");
            }
        } else if (policyRepository.findEnabledSourcesByProject(request.getProjectId()).isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "no enabled policy source in project");
        } else if (policyRepository.countActiveProjectCrawlTask(request.getProjectId()) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "project already has active full policy crawl task");
        }
        GenerateTask task = new GenerateTask();
        task.setProjectId(request.getProjectId());
        task.setTaskType(TASK_TYPE_POLICY_CRAWL);
        task.setBizType(BIZ_TYPE_POLICY_SOURCE);
        task.setBizId(request.getSourceId());
        task.setStatus(TaskStatus.QUEUED.name());
        task.setCurrentStage(STAGE_POLICY_CRAWL_QUEUED);
        task.setRetryCount(0);
        task.setMaxRetryCount(3);
        task.setCancelRequested(false);
        taskRepository.insertTask(task);
        if (task.getId() == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "policy crawl task id was not generated");
        }
        PolicyCrawlTask crawlTask = new PolicyCrawlTask();
        crawlTask.setTaskId(task.getId());
        crawlTask.setProjectId(request.getProjectId());
        crawlTask.setSourceId(source == null ? null : source.getId());
        crawlTask.setSourceName(source == null ? "ALL_POLICY_SOURCES" : source.getName());
        crawlTask.setStatus(TaskStatus.QUEUED.name());
        crawlTask.setProgress(0);
        crawlTask.setFetchedCount(0);
        crawlTask.setIndexedCount(0);
        crawlTask.setFailedCount(0);
        crawlTask.setMessage("policy crawl task queued");
        crawlTask.setCreatedBy(SecurityUtils.getCurrentUserId());
        crawlTask.setUpdatedBy(SecurityUtils.getCurrentUserId());
        requireUpdated(policyRepository.insertCrawlTask(crawlTask), "policy crawl task insert failed");
        taskOutboxApplicationService.enqueueTask(task, "policy crawl requested");
        return toCrawlTaskResponse(requireCrawlTask(task.getId()));
    }

    public PageResult<PolicyCrawlTaskResponse> queryCrawlTasks(PolicyCrawlTaskQueryRequest request) {
        if (request.getProjectId() != null) {
            projectAccessApplicationService.requireProjectAccess(request.getProjectId());
        }
        List<Long> accessibleProjectIds = accessibleProjectIdsWhenNoProject(request.getProjectId());
        if (request.getProjectId() == null && accessibleProjectIds != null && accessibleProjectIds.isEmpty()) {
            return new PageResult<>(request.getPageNo(), request.getPageSize(), 0, List.of());
        }
        String status = normalizeOptionalTaskStatus(request.getStatus());
        Page<PolicyCrawlTask> page = PageHelper.startPage(request.getPageNo(), request.getPageSize())
                .doSelectPage(() -> policyRepository.findCrawlTasks(request.getProjectId(), accessibleProjectIds, request.getSourceId(), status));
        return new PageResult<>(request.getPageNo(), request.getPageSize(), page.getTotal(), page.getResult().stream().map(this::toCrawlTaskResponse).toList());
    }

    public PageResult<PolicyArticleResponse> queryArticles(PolicyArticleQueryRequest request) {
        if (request.getProjectId() != null) {
            projectAccessApplicationService.requireProjectAccess(request.getProjectId());
        }
        List<Long> accessibleProjectIds = accessibleProjectIdsWhenNoProject(request.getProjectId());
        if (request.getProjectId() == null && accessibleProjectIds != null && accessibleProjectIds.isEmpty()) {
            return new PageResult<>(request.getPageNo(), request.getPageSize(), 0, List.of());
        }
        String indexStatus = normalizeOptionalIndexStatus(request.getIndexStatus());
        Page<PolicyArticle> page = PageHelper.startPage(request.getPageNo(), request.getPageSize())
                .doSelectPage(() -> policyRepository.findArticles(request.getProjectId(), accessibleProjectIds, request.getSourceId(),
                        trimToNull(request.getKeyword()), indexStatus, request.getPublishDateFrom(), request.getPublishDateTo()));
        return new PageResult<>(request.getPageNo(), request.getPageSize(), page.getTotal(), page.getResult().stream().map(this::toArticleResponse).toList());
    }

    /**
     * CRAWLER-013：人工确认采集结果后入库。只处理 PENDING_CONFIRM 状态的文章，逐条同步索引到项目默认知识库。
     */
    @Transactional
    public PolicyArticleConfirmResponse confirmArticles(PolicyArticleConfirmRequest request) {
        Project project = projectAccessApplicationService.requireProjectWritableManage(request.getProjectId());
        requirePolicyCrawlerEnabled(project);
        Long knowledgeBaseId = ensureDefaultKnowledgeBase(project);
        List<Long> articleIds = request.getArticleIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (articleIds.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "articleIds is required");
        }
        List<PolicyArticle> articles = policyRepository.findArticlesByIds(request.getProjectId(), articleIds);
        if (articles.size() != articleIds.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "some policy articles do not exist in this project");
        }
        PolicyArticleConfirmResponse response = new PolicyArticleConfirmResponse();
        int confirmed = 0;
        for (PolicyArticle article : articles) {
            if (!PolicyIndexStatus.PENDING_CONFIRM.name().equals(article.getIndexStatus())) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "policy article " + article.getId() + " is not waiting for confirmation, current status " + article.getIndexStatus());
            }
        }
        for (PolicyArticle article : articles) {
            try {
                indexArticle(article, knowledgeBaseId);
                confirmed++;
            } catch (RuntimeException ex) {
                response.getFailures().add(new PolicyArticleConfirmResponse.PolicyArticleConfirmFailure(
                        article.getId(), truncateError(ex.getMessage())));
            }
        }
        response.setConfirmedCount(confirmed);
        response.setFailedCount(response.getFailures().size());
        return response;
    }

    @Transactional
    public int createDueScheduledCrawlTasks() {
        int created = 0;
        for (PolicySource source : policyRepository.findDueSources()) {
            try {
                if (policyRepository.countActiveCrawlTask(source.getId()) > 0) {
                    continue;
                }
                Project project = projectAccessApplicationService.requireProjectWritableForSystem(source.getProjectId());
                if (!isPolicyCrawlerEnabled(project)) {
                    // PROJECT-017：项目未开启互联网政策资讯采集，定时调度直接跳过，不记为来源失败
                    continue;
                }
                ensureDefaultKnowledgeBase(project);
                GenerateTask task = new GenerateTask();
                task.setProjectId(source.getProjectId());
                task.setTaskType(TASK_TYPE_POLICY_CRAWL);
                task.setBizType(BIZ_TYPE_POLICY_SOURCE);
                task.setBizId(source.getId());
                task.setStatus(TaskStatus.QUEUED.name());
                task.setCurrentStage(STAGE_POLICY_CRAWL_QUEUED);
                task.setRetryCount(0);
                task.setMaxRetryCount(3);
                task.setCancelRequested(false);
                taskRepository.insertTask(task);
                if (task.getId() == null) {
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR, "policy crawl task id was not generated");
                }
                PolicyCrawlTask crawlTask = new PolicyCrawlTask();
                crawlTask.setTaskId(task.getId());
                crawlTask.setProjectId(source.getProjectId());
                crawlTask.setSourceId(source.getId());
                crawlTask.setSourceName(source.getName());
                crawlTask.setStatus(TaskStatus.QUEUED.name());
                crawlTask.setProgress(0);
                crawlTask.setFetchedCount(0);
                crawlTask.setIndexedCount(0);
                crawlTask.setFailedCount(0);
                crawlTask.setMessage("scheduled policy crawl task queued");
                crawlTask.setCreatedBy(SYSTEM_USER_ID);
                crawlTask.setUpdatedBy(SYSTEM_USER_ID);
                requireUpdated(policyRepository.insertCrawlTask(crawlTask), "policy crawl task insert failed");
                taskOutboxApplicationService.enqueueTask(task, "scheduled policy crawl requested");
                created++;
            } catch (RuntimeException ex) {
                requireUpdated(policyRepository.markSourceFailed(source.getId(), truncateError(ex.getMessage()), SYSTEM_USER_ID),
                        "policy source schedule failure update failed");
            }
        }
        return created;
    }

    public void executeCrawlTask(Long taskId) {
        PolicyCrawlTask task = requireCrawlTask(taskId);
        requireUpdated(policyRepository.markCrawlTaskRunning(taskId, SYSTEM_USER_ID), "policy crawl task running state changed");
        try {
            validateGenerateTask(task);
            doExecuteCrawlTask(task);
        } catch (RuntimeException ex) {
            requireUpdated(policyRepository.markCrawlTaskFailed(taskId, truncateError(ex.getMessage()), SYSTEM_USER_ID),
                    "policy crawl task failure state update failed");
            throw ex;
        }
    }

    private void doExecuteCrawlTask(PolicyCrawlTask task) {
        Project project = projectAccessApplicationService.requireProjectWritableForSystem(task.getProjectId());
        requirePolicyCrawlerEnabled(project);
        Long knowledgeBaseId = ensureDefaultKnowledgeBase(project);
        List<PolicySource> sources = task.getSourceId() == null
                ? policyRepository.findEnabledSourcesByProject(task.getProjectId())
                : List.of(requireEnabledSourceForSystem(task.getSourceId(), task.getProjectId()));
        if (sources.isEmpty()) {
            failTask(task.getTaskId(), "no enabled policy source in project");
            throw new BusinessException(ErrorCode.CONFLICT, "no enabled policy source in project");
        }
        int fetched = 0;
        int indexed = 0;
        int failed = 0;
        int articleFailed = 0;
        int skipped = 0;
        int pendingConfirm = 0;
        int unchanged = 0;
        String lastError = null;
        for (PolicySource source : sources) {
            try {
                PolicyCrawlerResponse response = crawlSource(source);
                List<PolicyCrawlerArticle> articles = response.getArticles() == null ? List.of() : response.getArticles();
                fetched += response.getFetchedCount() == null ? articles.size() : response.getFetchedCount();
                articleFailed += response.getFailedCount() == null ? 0 : response.getFailedCount();
                skipped += response.getSkippedCount() == null ? 0 : response.getSkippedCount();
                boolean autoIndex = !Boolean.FALSE.equals(source.getAutoIndex());
                for (PolicyCrawlerArticle item : articles) {
                    PolicyArticle article = upsertArticle(source, item);
                    if (PolicyIndexStatus.SUCCESS.name().equals(article.getIndexStatus())) {
                        // 内容未变化的已入库文章不重复送 RAG，避免每轮重复 embedding
                        unchanged++;
                        continue;
                    }
                    if (!autoIndex) {
                        // CRAWLER-013：政策源关闭自动入库时停在待确认，不写向量库
                        requireUpdated(policyRepository.markArticlePendingConfirm(article.getId(), SYSTEM_USER_ID),
                                "policy article pending confirm state update failed");
                        pendingConfirm++;
                        continue;
                    }
                    try {
                        indexArticle(article, knowledgeBaseId);
                        indexed++;
                    } catch (RuntimeException ex) {
                        failed++;
                        lastError = truncateError(ex.getMessage());
                    }
                }
                requireUpdated(policyRepository.markSourceCrawled(source.getId(), null, SYSTEM_USER_ID), "policy source crawl time update failed");
            } catch (RuntimeException ex) {
                failed++;
                lastError = truncateError(ex.getMessage());
                requireUpdated(policyRepository.markSourceFailed(source.getId(), lastError, SYSTEM_USER_ID), "policy source failure update failed");
            }
        }
        // 终态只由源级失败和入库失败决定；Python 侧单篇抓取失败和附件跳过只记录，不让个别文章拖垮整个任务
        String status = failed > 0 ? TaskStatus.FAILED.name() : TaskStatus.SUCCESS.name();
        String message = String.format(
                "policy crawl %s, fetched=%d, indexed=%d, pendingConfirm=%d, unchanged=%d, indexFailed=%d, articleFailed=%d, skipped=%d",
                failed > 0 ? "completed with failures" : "completed",
                fetched, indexed, pendingConfirm, unchanged, failed, articleFailed, skipped);
        requireUpdated(policyRepository.updateCrawlTaskProgress(task.getTaskId(), status, 100, fetched, indexed,
                failed + articleFailed, message, lastError, SYSTEM_USER_ID),
                "policy crawl task final state update failed");
        if (failed > 0) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, lastError == null ? "policy crawl failed" : lastError);
        }
    }

    private void validateGenerateTask(PolicyCrawlTask crawlTask) {
        GenerateTask task = taskRepository.findById(crawlTask.getTaskId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "generate task not found"));
        if (!TASK_TYPE_POLICY_CRAWL.equals(task.getTaskType())) {
            throw new BusinessException(ErrorCode.CONFLICT, "policy crawl task type mismatch");
        }
        if (!crawlTask.getProjectId().equals(task.getProjectId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "policy crawl project mismatch");
        }
        if (crawlTask.getSourceId() == null) {
            if (task.getBizId() != null) {
                throw new BusinessException(ErrorCode.CONFLICT, "policy crawl source mismatch");
            }
        } else if (!crawlTask.getSourceId().equals(task.getBizId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "policy crawl source mismatch");
        }
    }

    private PolicyCrawlerResponse crawlSource(PolicySource source) {
        PolicyCrawlerRequest request = new PolicyCrawlerRequest();
        request.setProjectId(source.getProjectId());
        request.setSourceId(source.getId());
        request.setUrl(source.getUrl());
        request.setLastCrawledAt(source.getLastCrawledAt());
        PolicyCrawlerResponse response = policyCrawlerClient.crawl(request);
        if (response == null) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "policy crawler returned empty response");
        }
        return response;
    }

    private PolicyArticle upsertArticle(PolicySource source, PolicyCrawlerArticle item) {
        String url = normalizeUrl(item.getUrl() == null || item.getUrl().isBlank() ? source.getUrl() : item.getUrl());
        String content = normalizeRequired(item.getContent(), "policy article content is empty");
        String title = normalizeRequired(item.getTitle(), "policy article title is empty");
        String hash = sha256(url);
        PolicyArticle article = policyRepository.findArticleByProjectAndHash(source.getProjectId(), hash).orElseGet(PolicyArticle::new);
        boolean creating = article.getId() == null;
        if (!creating
                && PolicyIndexStatus.SUCCESS.name().equals(article.getIndexStatus())
                && content.equals(article.getContent())) {
            // CRAWLER-019：内容未变化的已入库文章保持 SUCCESS，不回退状态、不重复入 RAG。
            // 不做这层判断，人工确认过的文章下一轮采集会被打回待确认。
            return article;
        }
        article.setProjectId(source.getProjectId());
        article.setSourceId(source.getId());
        article.setTitle(limit(title, 256));
        article.setUrl(url);
        article.setUrlHash(hash);
        article.setSummary(limit(trimToNull(item.getSummary()), 1000));
        article.setContent(content);
        article.setPublishDate(item.getPublishDate());
        article.setCategory(limit(trimToNull(item.getCategory()), 128));
        article.setPolicyNo(limit(trimToNull(item.getPolicyNo()), 128));
        article.setIndexStatus(PolicyIndexStatus.PENDING.name());
        article.setErrorMessage(null);
        article.setUpdatedBy(SYSTEM_USER_ID);
        if (creating) {
            article.setCreatedBy(SYSTEM_USER_ID);
            policyRepository.insertArticle(article);
            if (article.getId() == null) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "policy article id was not generated");
            }
        } else {
            requireUpdated(policyRepository.updateArticle(article), "policy article update failed");
        }
        return policyRepository.findArticleById(article.getId()).orElseThrow(() -> new BusinessException(ErrorCode.SYSTEM_ERROR, "policy article is not readable"));
    }

    private void indexArticle(PolicyArticle article, Long knowledgeBaseId) {
        requireUpdated(policyRepository.markArticleIndexing(article.getId(), SYSTEM_USER_ID), "policy article indexing state update failed");
        try {
            RagDocumentRequest document = new RagDocumentRequest();
            document.setDocumentId(String.valueOf(article.getId()));
            document.setTitle(article.getTitle());
            document.setContent(article.getContent());
            document.setSourceType("POLICY_ARTICLE");
            document.setSourceId(String.valueOf(article.getId()));
            document.setMetadata(Map.of(
                    "projectId", article.getProjectId(),
                    "sourceId", article.getSourceId(),
                    "articleId", article.getId(),
                    "url", article.getUrl(),
                    "publishDate", article.getPublishDate() == null ? "" : article.getPublishDate().toString()
            ));
            RagIndexRequest request = new RagIndexRequest();
            request.setProjectId(article.getProjectId());
            request.setKnowledgeBaseId(knowledgeBaseId);
            request.setDocuments(List.of(document));
            RagIndexResponse response = aiApplicationService.indexKnowledgeForSystem(request);
            if (response == null || response.getIndexedDocuments() == null || response.getIndexedDocuments() <= 0) {
                throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "RAG index returned no indexed documents");
            }
            requireUpdated(policyRepository.markArticleIndexSuccess(article.getId(), SYSTEM_USER_ID), "policy article index success update failed");
        } catch (RuntimeException ex) {
            String error = truncateError(ex.getMessage());
            requireUpdated(policyRepository.markArticleIndexFailed(article.getId(), error, SYSTEM_USER_ID), "policy article index failure update failed");
            throw ex;
        }
    }

    private void failTask(Long taskId, String error) {
        requireUpdated(policyRepository.updateCrawlTaskProgress(taskId, TaskStatus.FAILED.name(), 100, 0, 0, 1,
                "policy crawl failed", truncateError(error), SYSTEM_USER_ID), "policy crawl task failure state update failed");
    }

    /**
     * PROJECT-017：项目未开启互联网政策资讯采集时拒绝采集。
     * settings 缺失按未开启处理，错误文案引导到项目设置。
     */
    private void requirePolicyCrawlerEnabled(Project project) {
        if (!isPolicyCrawlerEnabled(project)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该项目尚未开启「互联网政策资讯采集」。请到「项目管理 → 项目设置」打开该开关后重试。");
        }
    }

    private boolean isPolicyCrawlerEnabled(Project project) {
        ProjectSettingsResponse settings = parseSettings(project);
        return settings != null && Boolean.TRUE.equals(settings.getInternetPolicyCrawlerEnabled());
    }

    private Long ensureDefaultKnowledgeBase(Project project) {
        Long knowledgeBaseId = parseDefaultKnowledgeBaseId(project);
        if (knowledgeBaseId == null) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该项目尚未配置「默认知识库」，采集结果无处入库。请到「项目管理 → 项目设置」填写「默认知识库ID」后重试。");
        }
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findById(knowledgeBaseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "「默认知识库ID」指向的知识库不存在，请到「项目管理 → 项目设置」更正。"));
        if (!project.getId().equals(knowledgeBase.getProjectId())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "「默认知识库ID」指向的知识库不属于当前项目，请到「项目管理 → 项目设置」更正。");
        }
        if (!KnowledgeBaseStatus.ENABLED.name().equals(knowledgeBase.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "「默认知识库」已被停用，请先到「知识库管理」启用它，或在项目设置中换一个已启用的知识库。");
        }
        return knowledgeBaseId;
    }

    private Long parseDefaultKnowledgeBaseId(Project project) {
        ProjectSettingsResponse settings = parseSettings(project);
        return settings == null ? null : settings.getDefaultKnowledgeBaseId();
    }

    private ProjectSettingsResponse parseSettings(Project project) {
        if (project.getSettings() == null || project.getSettings().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(project.getSettings(), ProjectSettingsResponse.class);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "project settings json parse failed");
        }
    }

    private PolicySource requireSource(Long sourceId) {
        if (sourceId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "sourceId is required");
        }
        return policyRepository.findSourceById(sourceId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "policy source not found"));
    }

    private PolicySource requireSourceManage(Long sourceId) {
        PolicySource source = requireSource(sourceId);
        projectAccessApplicationService.requireProjectWritableManage(source.getProjectId());
        return source;
    }

    private PolicySource requireEnabledSourceForSystem(Long sourceId, Long projectId) {
        PolicySource source = requireSource(sourceId);
        if (!projectId.equals(source.getProjectId())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "policy source project mismatch");
        }
        if (!PolicySourceStatus.ENABLED.name().equals(source.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "policy source is disabled");
        }
        return source;
    }

    private PolicyCrawlTask requireCrawlTask(Long taskId) {
        return policyRepository.findCrawlTaskByTaskId(taskId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "policy crawl task not found"));
    }

    private List<Long> accessibleProjectIdsWhenNoProject(Long projectId) {
        return projectId == null && !SecurityUtils.isPlatformAdmin() ? projectAccessApplicationService.currentUserAccessibleProjectIds() : null;
    }

    private String normalizeUrl(String value) {
        String url = normalizeRequired(value, "url is required");
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "url is invalid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!List.of("http", "https").contains(scheme) || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "url must be http or https");
        }
        return url;
    }

    private String normalizeFrequency(String value) {
        try {
            return PolicyCrawlFrequency.valueOf(normalizeRequired(value, "crawlFrequency is required").toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "crawlFrequency must be MANUAL, DAILY or WEEKLY");
        }
    }

    private String normalizeRequiredSourceStatus(String value) {
        try {
            return PolicySourceStatus.valueOf(normalizeRequired(value, "status is required").toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "status must be ENABLED or DISABLED");
        }
    }

    private String normalizeOptionalSourceStatus(String value) {
        if (value == null || value.isBlank()) return null;
        try { return PolicySourceStatus.valueOf(value.trim().toUpperCase(Locale.ROOT)).name(); }
        catch (IllegalArgumentException ex) { throw new BusinessException(ErrorCode.PARAM_ERROR, "status must be ENABLED or DISABLED"); }
    }

    private String normalizeOptionalIndexStatus(String value) {
        if (value == null || value.isBlank()) return null;
        try { return PolicyIndexStatus.valueOf(value.trim().toUpperCase(Locale.ROOT)).name(); }
        catch (IllegalArgumentException ex) { throw new BusinessException(ErrorCode.PARAM_ERROR, "indexStatus must be PENDING, INDEXING, SUCCESS or FAILED"); }
    }

    private String normalizeOptionalTaskStatus(String value) {
        if (value == null || value.isBlank()) return null;
        try { return TaskStatus.parse(value.trim().toUpperCase(Locale.ROOT)).name(); }
        catch (IllegalArgumentException ex) { throw new BusinessException(ErrorCode.PARAM_ERROR, "status is invalid"); }
    }

    private String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) throw new BusinessException(ErrorCode.PARAM_ERROR, message);
        return value.trim();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void requireUpdated(int rows, String message) {
        if (rows <= 0) throw new BusinessException(ErrorCode.CONFLICT, message);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "sha256 failed");
        }
    }

    private String limit(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private String truncateError(String message) {
        String error = message == null || message.isBlank() ? "policy crawl failed" : message.trim();
        return limit(error, MAX_ERROR_LENGTH);
    }

    private PolicySourceResponse toSourceResponse(PolicySource source) {
        PolicySourceResponse response = new PolicySourceResponse();
        response.setSourceId(source.getId());
        response.setProjectId(source.getProjectId());
        response.setName(source.getName());
        response.setUrl(source.getUrl());
        response.setCrawlFrequency(source.getCrawlFrequency());
        response.setStatus(source.getStatus());
        response.setAutoIndex(!Boolean.FALSE.equals(source.getAutoIndex()));
        response.setDescription(source.getDescription());
        response.setLastCrawledAt(source.getLastCrawledAt());
        response.setLastError(source.getLastError());
        response.setCreatedAt(source.getCreatedAt());
        response.setUpdatedAt(source.getUpdatedAt());
        return response;
    }

    private PolicyCrawlTaskResponse toCrawlTaskResponse(PolicyCrawlTask task) {
        PolicyCrawlTaskResponse response = new PolicyCrawlTaskResponse();
        response.setTaskId(task.getTaskId());
        response.setProjectId(task.getProjectId());
        response.setSourceId(task.getSourceId());
        response.setSourceName(task.getSourceName());
        response.setStatus(task.getStatus());
        response.setProgress(task.getProgress());
        response.setFetchedCount(task.getFetchedCount());
        response.setIndexedCount(task.getIndexedCount());
        response.setFailedCount(task.getFailedCount());
        response.setMessage(task.getMessage());
        response.setErrorMessage(task.getErrorMessage());
        response.setStartedAt(task.getStartedAt());
        response.setFinishedAt(task.getFinishedAt());
        response.setCreatedAt(task.getCreatedAt());
        return response;
    }

    private PolicyArticleResponse toArticleResponse(PolicyArticle article) {
        PolicyArticleResponse response = new PolicyArticleResponse();
        response.setArticleId(article.getId());
        response.setProjectId(article.getProjectId());
        response.setSourceId(article.getSourceId());
        response.setTitle(article.getTitle());
        response.setUrl(article.getUrl());
        response.setSummary(article.getSummary());
        response.setContent(article.getContent());
        response.setPublishDate(article.getPublishDate());
        response.setCategory(article.getCategory());
        response.setPolicyNo(article.getPolicyNo());
        response.setIndexStatus(article.getIndexStatus());
        response.setErrorMessage(article.getErrorMessage());
        response.setCreatedAt(article.getCreatedAt());
        response.setUpdatedAt(article.getUpdatedAt());
        return response;
    }
}
