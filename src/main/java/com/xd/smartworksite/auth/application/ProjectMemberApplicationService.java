package com.xd.smartworksite.auth.application;

import com.xd.smartworksite.auth.domain.ProjectMember;
import com.xd.smartworksite.auth.domain.ProjectRoleEnum;
import com.xd.smartworksite.auth.dto.ProjectMemberCreateRequest;
import com.xd.smartworksite.auth.dto.ProjectMemberResponse;
import com.xd.smartworksite.auth.mapper.ProjectMemberMapper;
import com.xd.smartworksite.auth.mapper.UserAccountMapper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.common.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProjectMemberApplicationService {

    private final ProjectMemberMapper projectMemberMapper;
    private final UserAccountMapper userAccountMapper;

    public ProjectMemberApplicationService(ProjectMemberMapper projectMemberMapper,
                                           UserAccountMapper userAccountMapper) {
        this.projectMemberMapper = projectMemberMapper;
        this.userAccountMapper = userAccountMapper;
    }

    public List<ProjectMemberResponse> listMembers(Long projectId) {
        requireProjectAccess(projectId);
        return projectMemberMapper.selectByProjectId(projectId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public ProjectMemberResponse addMember(Long projectId, ProjectMemberCreateRequest request) {
        requireProjectManage(projectId);
        if (userAccountMapper.selectById(request.getUserId()) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        if (projectMemberMapper.selectByProjectIdAndUserId(projectId, request.getUserId()) != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "用户已是项目成员");
        }
        validateProjectRole(request.getProjectRole());
        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUserId(request.getUserId());
        member.setProjectRole(request.getProjectRole());
        member.setStatus("ENABLED");
        projectMemberMapper.insert(member);
        return toResponse(projectMemberMapper.selectByProjectIdAndUserId(projectId, request.getUserId()));
    }

    @Transactional
    public ProjectMemberResponse updateMember(Long projectId, Long userId, ProjectMemberCreateRequest request) {
        requireProjectManage(projectId);
        ProjectMember member = projectMemberMapper.selectByProjectIdAndUserId(projectId, userId);
        if (member == null) throw new BusinessException(ErrorCode.NOT_FOUND, "成员不存在");
        validateProjectRole(request.getProjectRole());
        member.setProjectRole(request.getProjectRole());
        projectMemberMapper.update(member);
        return toResponse(projectMemberMapper.selectByProjectIdAndUserId(projectId, userId));
    }

    @Transactional
    public void removeMember(Long projectId, Long userId) {
        requireProjectManage(projectId);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (currentUserId.equals(userId)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "不能移除自己");
        }
        if (projectMemberMapper.selectByProjectIdAndUserId(projectId, userId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "成员不存在");
        }
        projectMemberMapper.deleteByProjectIdAndUserId(projectId, userId, currentUserId);
    }

    public void requireProjectMember(Long projectId) {
        if (SecurityUtils.isPlatformAdmin()) return;
        Long userId = SecurityUtils.getCurrentUserId();
        if (projectMemberMapper.countActiveMember(projectId, userId) == 0) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "您不是该项目成员");
        }
    }

    private void requireProjectAccess(Long projectId) {
        requireProjectMember(projectId);
    }

    private void requireProjectManage(Long projectId) {
        if (SecurityUtils.isPlatformAdmin()) return;
        Long userId = SecurityUtils.getCurrentUserId();
        ProjectMember member = projectMemberMapper.selectByProjectIdAndUserId(projectId, userId);
        if (member == null || !"PROJECT_ADMIN".equals(member.getProjectRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "需要项目管理员权限");
        }
    }

    private void validateProjectRole(String projectRole) {
        if (!ProjectRoleEnum.isValid(projectRole)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "无效的项目角色: " + projectRole + "，可选值: PROJECT_ADMIN, MEMBER, VIEWER");
        }
    }

    private ProjectMemberResponse toResponse(ProjectMember m) {
        ProjectMemberResponse r = new ProjectMemberResponse();
        r.setId(m.getId());
        r.setProjectId(m.getProjectId());
        r.setUserId(m.getUserId());
        r.setUsername(m.getUsername());
        r.setDisplayName(m.getDisplayName());
        r.setProjectRole(m.getProjectRole());
        r.setStatus(m.getStatus());
        r.setCreatedAt(m.getCreatedAt());
        return r;
    }
}
