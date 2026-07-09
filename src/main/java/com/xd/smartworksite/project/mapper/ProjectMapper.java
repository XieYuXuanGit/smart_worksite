package com.xd.smartworksite.project.mapper;

import com.xd.smartworksite.project.domain.Project;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ProjectMapper {

    List<Project> selectPage(@Param("keyword") String keyword, @Param("userId") Long userId);

    Project selectById(@Param("projectId") Long projectId);

    Project selectByProjectCode(@Param("projectCode") String projectCode);

    int insert(Project project);

    int update(Project project);

    int softDelete(@Param("projectId") Long projectId, @Param("updatedBy") Long updatedBy);

    int updateStatus(@Param("projectId") Long projectId, @Param("status") String status, @Param("updatedBy") Long updatedBy);
}
