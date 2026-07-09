package com.xd.smartworksite.project.application;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.common.result.PageResult;
import com.xd.smartworksite.common.security.SecurityUtils;
import com.xd.smartworksite.project.domain.Project;
import com.xd.smartworksite.project.dto.ProjectCreateRequest;
import com.xd.smartworksite.project.dto.ProjectQueryRequest;
import com.xd.smartworksite.project.dto.ProjectResponse;
import com.xd.smartworksite.project.dto.ProjectUpdateRequest;
import com.xd.smartworksite.project.repository.ProjectRepository;
import com.xd.smartworksite.auth.domain.ProjectMember;
import com.xd.smartworksite.auth.mapper.ProjectMemberMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
public class ProjectApplicationService {

    private static final String PROJECT_STATUS_ENABLED = "ENABLED";
    private static final String PROJECT_ROLE_ADMIN = "PROJECT_ADMIN";

    private final ProjectRepository projectRepository;
    private final ProjectMemberMapper projectMemberMapper;

    public ProjectApplicationService(ProjectRepository projectRepository,
                                     ProjectMemberMapper projectMemberMapper) {
        this.projectRepository = projectRepository;
        this.projectMemberMapper = projectMemberMapper;
    }

    public PageResult<ProjectResponse> queryProjects(ProjectQueryRequest request) {
        // Platform admin sees all projects; others only see projects they are a member of
        Long userId = SecurityUtils.isPlatformAdmin() ? null : SecurityUtils.getCurrentUserId();
        Page<Project> page = PageHelper.startPage(request.getPageNo(), request.getPageSize())
                .doSelectPage(() -> projectRepository.findPage(request.getKeyword(), userId));
        List<ProjectResponse> records = page.getResult().stream().map(this::toResponse).toList();
        return new PageResult<>(request.getPageNo(), request.getPageSize(), page.getTotal(), records);
    }

    public ProjectResponse getProject(Long projectId) {
        return toResponse(requireProject(projectId));
    }

    @Transactional
    public ProjectResponse createProject(ProjectCreateRequest request) {
        String projectCode = normalizeProjectCode(request.getProjectCode());
        ensureProjectCodeAvailable(projectCode, null);

        Long currentUserId = SecurityUtils.getCurrentUserId();
        Project project = new Project();
        project.setProjectName(normalizeRequiredText(request.getProjectName(), "projectName is required"));
        project.setProjectCode(projectCode);
        project.setLocation(trimToNull(request.getLocation()));
        project.setDescription(trimToNull(request.getDescription()));
        project.setStatus(PROJECT_STATUS_ENABLED);
        project.setCreatedBy(currentUserId);
        project.setUpdatedBy(currentUserId);
        projectRepository.insert(project);

        // Auto-add creator as PROJECT_ADMIN member
        ProjectMember member = new ProjectMember();
        member.setProjectId(project.getId());
        member.setUserId(currentUserId);
        member.setProjectRole(PROJECT_ROLE_ADMIN);
        member.setStatus(PROJECT_STATUS_ENABLED);
        projectMemberMapper.insert(member);

        return getProject(project.getId());
    }

    @Transactional
    public ProjectResponse updateProject(Long projectId, ProjectUpdateRequest request) {
        Project project = requireProject(projectId);
        checkProjectManagePermission(projectId);

        String projectCode = normalizeProjectCode(request.getProjectCode());
        ensureProjectCodeAvailable(projectCode, projectId);

        project.setProjectName(normalizeRequiredText(request.getProjectName(), "projectName is required"));
        project.setProjectCode(projectCode);
        project.setLocation(trimToNull(request.getLocation()));
        project.setDescription(trimToNull(request.getDescription()));
        project.setUpdatedBy(SecurityUtils.getCurrentUserId());
        projectRepository.update(project);
        return getProject(projectId);
    }

    @Transactional
    public void deleteProject(Long projectId) {
        requireProject(projectId);
        checkProjectManagePermission(projectId);
        projectRepository.softDelete(projectId, SecurityUtils.getCurrentUserId());
    }

    @Transactional
    public void updateProjectStatus(Long projectId, String status) {
        requireProject(projectId);
        checkProjectManagePermission(projectId);
        projectRepository.updateStatus(projectId, status, SecurityUtils.getCurrentUserId());
    }

    private void checkProjectManagePermission(Long projectId) {
        if (SecurityUtils.isPlatformAdmin()) {
            return;
        }
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ProjectMember member = projectMemberMapper.selectByProjectIdAndUserId(projectId, currentUserId);
        if (member == null || !PROJECT_ROLE_ADMIN.equals(member.getProjectRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "no permission to manage this project");
        }
    }

    private Project requireProject(Long projectId) {
        if (projectId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "projectId is required");
        }
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "project not found"));
    }

    private void ensureProjectCodeAvailable(String projectCode, Long currentProjectId) {
        projectRepository.findByProjectCode(projectCode)
                .filter(project -> currentProjectId == null || !currentProjectId.equals(project.getId()))
                .ifPresent(project -> {
                    throw new BusinessException(ErrorCode.CONFLICT, "projectCode already exists");
                });
    }

    private String normalizeProjectCode(String projectCode) {
        return normalizeRequiredText(projectCode, "projectCode is required").toUpperCase(Locale.ROOT);
    }

    private String normalizeRequiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, message);
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private ProjectResponse toResponse(Project project) {
        ProjectResponse response = new ProjectResponse();
        response.setProjectId(project.getId());
        response.setProjectName(project.getProjectName());
        response.setProjectCode(project.getProjectCode());
        response.setLocation(project.getLocation());
        response.setStatus(project.getStatus());
        response.setDescription(project.getDescription());
        response.setCreatedAt(project.getCreatedAt());
        response.setUpdatedAt(project.getUpdatedAt());
        return response;
    }
}
