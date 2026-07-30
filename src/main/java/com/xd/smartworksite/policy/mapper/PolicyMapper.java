package com.xd.smartworksite.policy.mapper;

import com.xd.smartworksite.policy.domain.PolicyArticle;
import com.xd.smartworksite.policy.domain.PolicyCrawlTask;
import com.xd.smartworksite.policy.domain.PolicySource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface PolicyMapper {
    int insertSource(PolicySource source);
    PolicySource selectSourceById(@Param("sourceId") Long sourceId);
    PolicySource selectSourceByProjectAndHash(@Param("projectId") Long projectId, @Param("urlHash") String urlHash);
    List<PolicySource> selectSources(@Param("projectId") Long projectId, @Param("accessibleProjectIds") List<Long> accessibleProjectIds,
                                     @Param("keyword") String keyword, @Param("status") String status);
    List<PolicySource> selectEnabledSourcesByProject(@Param("projectId") Long projectId);
    List<PolicySource> selectDueSources();
    int countActiveCrawlTask(@Param("sourceId") Long sourceId);
    int countActiveProjectCrawlTask(@Param("projectId") Long projectId);
    int updateSource(PolicySource source);
    int updateSourceStatus(@Param("sourceId") Long sourceId, @Param("status") String status, @Param("updatedBy") Long updatedBy);
    int softDeleteSource(@Param("sourceId") Long sourceId, @Param("updatedBy") Long updatedBy);
    int markSourceCrawled(@Param("sourceId") Long sourceId, @Param("lastError") String lastError, @Param("updatedBy") Long updatedBy);
    int markSourceFailed(@Param("sourceId") Long sourceId, @Param("lastError") String lastError, @Param("updatedBy") Long updatedBy);

    int insertCrawlTask(PolicyCrawlTask task);
    PolicyCrawlTask selectCrawlTaskByTaskId(@Param("taskId") Long taskId);
    List<PolicyCrawlTask> selectCrawlTasks(@Param("projectId") Long projectId, @Param("accessibleProjectIds") List<Long> accessibleProjectIds,
                                           @Param("sourceId") Long sourceId, @Param("status") String status);
    int markCrawlTaskRunning(@Param("taskId") Long taskId, @Param("updatedBy") Long updatedBy);
    int markCrawlTaskFailed(@Param("taskId") Long taskId, @Param("errorMessage") String errorMessage, @Param("updatedBy") Long updatedBy);
    int updateCrawlTaskProgress(@Param("taskId") Long taskId, @Param("status") String status, @Param("progress") Integer progress,
                                @Param("fetchedCount") Integer fetchedCount, @Param("indexedCount") Integer indexedCount,
                                @Param("failedCount") Integer failedCount, @Param("message") String message,
                                @Param("errorMessage") String errorMessage, @Param("updatedBy") Long updatedBy);

    int insertArticle(PolicyArticle article);
    int updateArticle(PolicyArticle article);
    PolicyArticle selectArticleByProjectAndHash(@Param("projectId") Long projectId, @Param("urlHash") String urlHash);
    PolicyArticle selectArticleById(@Param("articleId") Long articleId);
    List<PolicyArticle> selectArticles(@Param("projectId") Long projectId, @Param("accessibleProjectIds") List<Long> accessibleProjectIds,
                                       @Param("sourceId") Long sourceId, @Param("keyword") String keyword,
                                       @Param("indexStatus") String indexStatus, @Param("publishDateFrom") LocalDate publishDateFrom,
                                       @Param("publishDateTo") LocalDate publishDateTo);
    List<PolicyArticle> selectArticlesByIds(@Param("projectId") Long projectId, @Param("articleIds") List<Long> articleIds);
    int markArticlePendingConfirm(@Param("articleId") Long articleId, @Param("updatedBy") Long updatedBy);
    int markArticleIndexing(@Param("articleId") Long articleId, @Param("updatedBy") Long updatedBy);
    int markArticleIndexSuccess(@Param("articleId") Long articleId, @Param("updatedBy") Long updatedBy);
    int markArticleIndexFailed(@Param("articleId") Long articleId, @Param("errorMessage") String errorMessage, @Param("updatedBy") Long updatedBy);
}
