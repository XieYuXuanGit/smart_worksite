package com.xd.smartworksite.policy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class PolicySourceStatusRequest {
    @NotBlank
    @Size(max = 32)
    private String status;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
