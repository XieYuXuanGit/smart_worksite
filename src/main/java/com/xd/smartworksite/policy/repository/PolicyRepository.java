package com.xd.smartworksite.policy.repository;

import com.xd.smartworksite.policy.domain.PolicyArticle;
import com.xd.smartworksite.policy.domain.PolicyCrawlTask;
import com.xd.smartworksite.policy.domain.PolicySource;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PolicyRepository {
    PolicySource insertSource(PolicySource source);
    Optional<PolicySource> findSourceById(Long sourceId);
    Optional<PolicySource> findSourceByProjectAndHash(Long projectId, String urlHash);
    List<PolicySource> findSources(Long projectId, List<Long> accessibleProjectIds, String keyword, String status);
    List<PolicySource> findEnabledSourcesByProject(Long projectId);
    List<PolicySource> findDueSources();
    int countActiveCrawlTask(Long sourceId);
    int countActiveProjectCrawlTask(Long projectId);
    int updateSource(PolicySource source);
    int updateSourceStatus(Long sourceId, String status, Long updatedBy);
    int softDeleteSource(Long sourceId, Long updatedBy);
    int markSourceCrawled(Long sourceId, String lastError, Long updatedBy);
    int markSourceFailed(Long sourceId, String lastError, Long updatedBy);

    int insertCrawlTask(PolicyCrawlTask task);
    Optional<PolicyCrawlTask> findCrawlTaskByTaskId(Long taskId);
    List<PolicyCrawlTask> findCrawlTasks(Long projectId, List<Long> accessibleProjectIds, Long sourceId, String status);
    int markCrawlTaskRunning(Long taskId, Long updatedBy);
    int markCrawlTaskFailed(Long taskId, String errorMessage, Long updatedBy);
    int updateCrawlTaskProgress(Long taskId, String status, Integer progress, Integer fetchedCount, Integer indexedCount,
                                Integer failedCount, String message, String errorMessage, Long updatedBy);

    PolicyArticle insertArticle(PolicyArticle article);
    int updateArticle(PolicyArticle article);
    Optional<PolicyArticle> findArticleByProjectAndHash(Long projectId, String urlHash);
    Optional<PolicyArticle> findArticleById(Long articleId);
    List<PolicyArticle> findArticles(Long projectId, List<Long> accessibleProjectIds, Long sourceId, String keyword,
                                     String indexStatus, LocalDate publishDateFrom, LocalDate publishDateTo);
    List<PolicyArticle> findArticlesByIds(Long projectId, List<Long> articleIds);
    int markArticlePendingConfirm(Long articleId, Long updatedBy);
    int markArticleIndexing(Long articleId, Long updatedBy);
    int markArticleIndexSuccess(Long articleId, Long updatedBy);
    int markArticleIndexFailed(Long articleId, String errorMessage, Long updatedBy);
}
