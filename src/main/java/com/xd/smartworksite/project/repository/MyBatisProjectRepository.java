package com.xd.smartworksite.project.repository;

import com.xd.smartworksite.project.domain.Project;
import com.xd.smartworksite.project.mapper.ProjectMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisProjectRepository implements ProjectRepository {

    private final ProjectMapper projectMapper;

    public MyBatisProjectRepository(ProjectMapper projectMapper) {
        this.projectMapper = projectMapper;
    }

    @Override
    public List<Project> findPage(String keyword, Long userId) {
        return projectMapper.selectPage(keyword, userId);
    }

    @Override
    public Optional<Project> findById(Long projectId) {
        return Optional.ofNullable(projectMapper.selectById(projectId));
    }

    @Override
    public Optional<Project> findByProjectCode(String projectCode) {
        return Optional.ofNullable(projectMapper.selectByProjectCode(projectCode));
    }

    @Override
    public Project insert(Project project) {
        projectMapper.insert(project);
        return project;
    }

    @Override
    public void update(Project project) {
        projectMapper.update(project);
    }

    @Override
    public void softDelete(Long projectId, Long updatedBy) {
        projectMapper.softDelete(projectId, updatedBy);
    }

    @Override
    public void updateStatus(Long projectId, String status, Long updatedBy) {
        projectMapper.updateStatus(projectId, status, updatedBy);
    }
}
