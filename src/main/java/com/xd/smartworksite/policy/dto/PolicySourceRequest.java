package com.xd.smartworksite.policy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class PolicySourceRequest {
    @NotNull
    private Long projectId;
    @NotBlank
    @Size(max = 128)
    private String name;
    @NotBlank
    @Size(max = 1024)
    private String url;
    @NotBlank
    @Size(max = 32)
    private String crawlFrequency;
    @Size(max = 1000)
    private String description;
    private Boolean autoIndex;

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getCrawlFrequency() { return crawlFrequency; }
    public void setCrawlFrequency(String crawlFrequency) { this.crawlFrequency = crawlFrequency; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Boolean getAutoIndex() { return autoIndex; }
    public void setAutoIndex(Boolean autoIndex) { this.autoIndex = autoIndex; }
}
