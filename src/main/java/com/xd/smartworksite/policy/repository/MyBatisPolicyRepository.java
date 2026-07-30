package com.xd.smartworksite.policy.repository;

import com.xd.smartworksite.policy.domain.PolicyArticle;
import com.xd.smartworksite.policy.domain.PolicyCrawlTask;
import com.xd.smartworksite.policy.domain.PolicySource;
import com.xd.smartworksite.policy.mapper.PolicyMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisPolicyRepository implements PolicyRepository {
    private final PolicyMapper policyMapper;

    public MyBatisPolicyRepository(PolicyMapper policyMapper) {
        this.policyMapper = policyMapper;
    }

    public PolicySource insertSource(PolicySource source) { policyMapper.insertSource(source); return source; }
    public Optional<PolicySource> findSourceById(Long sourceId) { return Optional.ofNullable(policyMapper.selectSourceById(sourceId)); }
    public Optional<PolicySource> findSourceByProjectAndHash(Long projectId, String urlHash) { return Optional.ofNullable(policyMapper.selectSourceByProjectAndHash(projectId, urlHash)); }
    public List<PolicySource> findSources(Long projectId, List<Long> accessibleProjectIds, String keyword, String status) { return policyMapper.selectSources(projectId, accessibleProjectIds, keyword, status); }
    public List<PolicySource> findEnabledSourcesByProject(Long projectId) { return policyMapper.selectEnabledSourcesByProject(projectId); }
    public List<PolicySource> findDueSources() { return policyMapper.selectDueSources(); }
    public int countActiveCrawlTask(Long sourceId) { return policyMapper.countActiveCrawlTask(sourceId); }
    public int countActiveProjectCrawlTask(Long projectId) { return policyMapper.countActiveProjectCrawlTask(projectId); }
    public int updateSource(PolicySource source) { return policyMapper.updateSource(source); }
    public int updateSourceStatus(Long sourceId, String status, Long updatedBy) { return policyMapper.updateSourceStatus(sourceId, status, updatedBy); }
    public int softDeleteSource(Long sourceId, Long updatedBy) { return policyMapper.softDeleteSource(sourceId, updatedBy); }
    public int markSourceCrawled(Long sourceId, String lastError, Long updatedBy) { return policyMapper.markSourceCrawled(sourceId, lastError, updatedBy); }
    public int markSourceFailed(Long sourceId, String lastError, Long updatedBy) { return policyMapper.markSourceFailed(sourceId, lastError, updatedBy); }

    public int insertCrawlTask(PolicyCrawlTask task) { return policyMapper.insertCrawlTask(task); }
    public Optional<PolicyCrawlTask> findCrawlTaskByTaskId(Long taskId) { return Optional.ofNullable(policyMapper.selectCrawlTaskByTaskId(taskId)); }
    public List<PolicyCrawlTask> findCrawlTasks(Long projectId, List<Long> accessibleProjectIds, Long sourceId, String status) { return policyMapper.selectCrawlTasks(projectId, accessibleProjectIds, sourceId, status); }
    public int markCrawlTaskRunning(Long taskId, Long updatedBy) { return policyMapper.markCrawlTaskRunning(taskId, updatedBy); }
    public int markCrawlTaskFailed(Long taskId, String errorMessage, Long updatedBy) { return policyMapper.markCrawlTaskFailed(taskId, errorMessage, updatedBy); }
    public int updateCrawlTaskProgress(Long taskId, String status, Integer progress, Integer fetchedCount, Integer indexedCount, Integer failedCount, String message, String errorMessage, Long updatedBy) { return policyMapper.updateCrawlTaskProgress(taskId, status, progress, fetchedCount, indexedCount, failedCount, message, errorMessage, updatedBy); }

    public PolicyArticle insertArticle(PolicyArticle article) { policyMapper.insertArticle(article); return article; }
    public int updateArticle(PolicyArticle article) { return policyMapper.updateArticle(article); }
    public Optional<PolicyArticle> findArticleByProjectAndHash(Long projectId, String urlHash) { return Optional.ofNullable(policyMapper.selectArticleByProjectAndHash(projectId, urlHash)); }
    public Optional<PolicyArticle> findArticleById(Long articleId) { return Optional.ofNullable(policyMapper.selectArticleById(articleId)); }
    public List<PolicyArticle> findArticles(Long projectId, List<Long> accessibleProjectIds, Long sourceId, String keyword, String indexStatus, LocalDate publishDateFrom, LocalDate publishDateTo) { return policyMapper.selectArticles(projectId, accessibleProjectIds, sourceId, keyword, indexStatus, publishDateFrom, publishDateTo); }
    public List<PolicyArticle> findArticlesByIds(Long projectId, List<Long> articleIds) { return policyMapper.selectArticlesByIds(projectId, articleIds); }
    public int markArticlePendingConfirm(Long articleId, Long updatedBy) { return policyMapper.markArticlePendingConfirm(articleId, updatedBy); }
    public int markArticleIndexing(Long articleId, Long updatedBy) { return policyMapper.markArticleIndexing(articleId, updatedBy); }
    public int markArticleIndexSuccess(Long articleId, Long updatedBy) { return policyMapper.markArticleIndexSuccess(articleId, updatedBy); }
    public int markArticleIndexFailed(Long articleId, String errorMessage, Long updatedBy) { return policyMapper.markArticleIndexFailed(articleId, errorMessage, updatedBy); }
}
