package com.xd.smartworksite.policy.domain;

import java.time.LocalDateTime;

public class PolicySource {
    private Long id;
    private Long projectId;
    private String name;
    private String url;
    private String urlHash;
    private String crawlFrequency;
    private String status;
    private Boolean autoIndex;
    private String description;
    private LocalDateTime lastCrawledAt;
    private String lastError;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getUrlHash() { return urlHash; }
    public void setUrlHash(String urlHash) { this.urlHash = urlHash; }
    public String getCrawlFrequency() { return crawlFrequency; }
    public void setCrawlFrequency(String crawlFrequency) { this.crawlFrequency = crawlFrequency; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Boolean getAutoIndex() { return autoIndex; }
    public void setAutoIndex(Boolean autoIndex) { this.autoIndex = autoIndex; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getLastCrawledAt() { return lastCrawledAt; }
    public void setLastCrawledAt(LocalDateTime lastCrawledAt) { this.lastCrawledAt = lastCrawledAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
