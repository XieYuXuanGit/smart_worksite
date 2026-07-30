package com.xd.smartworksite.policy.dto;

import java.util.ArrayList;
import java.util.List;

public class PolicyArticleConfirmResponse {
    private Integer confirmedCount = 0;
    private Integer failedCount = 0;
    private List<PolicyArticleConfirmFailure> failures = new ArrayList<>();

    public Integer getConfirmedCount() { return confirmedCount; }
    public void setConfirmedCount(Integer confirmedCount) { this.confirmedCount = confirmedCount; }
    public Integer getFailedCount() { return failedCount; }
    public void setFailedCount(Integer failedCount) { this.failedCount = failedCount; }
    public List<PolicyArticleConfirmFailure> getFailures() { return failures; }
    public void setFailures(List<PolicyArticleConfirmFailure> failures) { this.failures = failures; }

    public static class PolicyArticleConfirmFailure {
        private Long articleId;
        private String errorMessage;

        public PolicyArticleConfirmFailure() {
        }

        public PolicyArticleConfirmFailure(Long articleId, String errorMessage) {
            this.articleId = articleId;
            this.errorMessage = errorMessage;
        }

        public Long getArticleId() { return articleId; }
        public void setArticleId(Long articleId) { this.articleId = articleId; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    }
}
