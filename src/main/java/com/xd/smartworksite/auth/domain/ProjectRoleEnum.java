package com.xd.smartworksite.auth.domain;

public enum ProjectRoleEnum {

    PROJECT_ADMIN("项目管理员"),
    MEMBER("项目成员"),
    VIEWER("只读观察者");

    private final String label;

    ProjectRoleEnum(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean canManageMembers() {
        return this == PROJECT_ADMIN;
    }

    public boolean canWrite() {
        return this == PROJECT_ADMIN || this == MEMBER;
    }

    public static ProjectRoleEnum fromValue(String value) {
        for (ProjectRoleEnum role : values()) {
            if (role.name().equals(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("无效的项目角色: " + value);
    }

    public static boolean isValid(String value) {
        for (ProjectRoleEnum role : values()) {
            if (role.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
