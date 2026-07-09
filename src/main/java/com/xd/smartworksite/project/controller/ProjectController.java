package com.xd.smartworksite.project.controller;

import com.xd.smartworksite.common.result.ApiResponse;
import com.xd.smartworksite.common.result.PageResult;
import com.xd.smartworksite.project.application.ProjectApplicationService;
import com.xd.smartworksite.project.dto.ProjectCreateRequest;
import com.xd.smartworksite.project.dto.ProjectQueryRequest;
import com.xd.smartworksite.project.dto.ProjectResponse;
import com.xd.smartworksite.project.dto.ProjectStatusRequest;
import com.xd.smartworksite.project.dto.ProjectUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/projects")
@Validated
public class ProjectController {

    private final ProjectApplicationService projectApplicationService;

    public ProjectController(ProjectApplicationService projectApplicationService) {
        this.projectApplicationService = projectApplicationService;
    }

    @GetMapping
    public ApiResponse<PageResult<ProjectResponse>> listProjects(@Valid ProjectQueryRequest request) {
        return ApiResponse.success(projectApplicationService.queryProjects(request));
    }

    @GetMapping("/{projectId}")
    public ApiResponse<ProjectResponse> getProject(@PathVariable Long projectId) {
        return ApiResponse.success(projectApplicationService.getProject(projectId));
    }

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ApiResponse<ProjectResponse> createProject(@Valid @RequestBody ProjectCreateRequest request) {
        return ApiResponse.success(projectApplicationService.createProject(request));
    }

    @PutMapping("/{projectId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ApiResponse<ProjectResponse> updateProject(@PathVariable Long projectId,
                                                      @Valid @RequestBody ProjectUpdateRequest request) {
        return ApiResponse.success(projectApplicationService.updateProject(projectId, request));
    }

    @DeleteMapping("/{projectId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ApiResponse<Void> deleteProject(@PathVariable Long projectId) {
        projectApplicationService.deleteProject(projectId);
        return ApiResponse.success(null);
    }

    @PutMapping("/{projectId}/status")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ApiResponse<Void> updateProjectStatus(@PathVariable Long projectId,
                                                 @Valid @RequestBody ProjectStatusRequest request) {
        projectApplicationService.updateProjectStatus(projectId, request.getStatus());
        return ApiResponse.success(null);
    }
}
