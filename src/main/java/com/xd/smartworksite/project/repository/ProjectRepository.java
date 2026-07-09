package com.xd.smartworksite.project.repository;

import com.xd.smartworksite.project.domain.Project;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository {

    List<Project> findPage(String keyword, Long userId);

    Optional<Project> findById(Long projectId);

    Optional<Project> findByProjectCode(String projectCode);

    Project insert(Project project);

    void update(Project project);

    void softDelete(Long projectId, Long updatedBy);

    void updateStatus(Long projectId, String status, Long updatedBy);
}
