package com.xd.smartworksite.policy.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public class PolicyArticleConfirmRequest {
    @NotNull
    private Long projectId;
    @NotEmpty
    @Size(max = 200)
    private List<Long> articleIds;

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public List<Long> getArticleIds() { return articleIds; }
    public void setArticleIds(List<Long> articleIds) { this.articleIds = articleIds; }
}
