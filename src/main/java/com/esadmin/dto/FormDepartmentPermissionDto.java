package com.esadmin.dto;

import java.time.LocalDateTime;
import java.util.List;

public class FormDepartmentPermissionDto {
    private String id;
    private String sourceType; // "form" 或 "excel"
    private String sourceId; // 表单ID或Excel表名
    private String sourceName; // 表单名称或Excel显示名称
    private List<DepartmentInfo> departments;
    private boolean allowAllDepartments; // 是否允许所有部门访问
    private LocalDateTime updateTime;
    private String remark;

    public static class DepartmentInfo {
        private String departmentId;
        private String departmentName;
        private String permissionType;
        private boolean isActive;

        public DepartmentInfo() {}

        public DepartmentInfo(String departmentId, String departmentName, String permissionType, boolean isActive) {
            this.departmentId = departmentId;
            this.departmentName = departmentName;
            this.permissionType = permissionType;
            this.isActive = isActive;
        }

        // Getters and Setters
        public String getDepartmentId() { return departmentId; }
        public void setDepartmentId(String departmentId) { this.departmentId = departmentId; }

        public String getDepartmentName() { return departmentName; }
        public void setDepartmentName(String departmentName) { this.departmentName = departmentName; }

        public String getPermissionType() { return permissionType; }
        public void setPermissionType(String permissionType) { this.permissionType = permissionType; }

        public boolean isActive() { return isActive; }
        public void setActive(boolean active) { isActive = active; }
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }

    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }

    public List<DepartmentInfo> getDepartments() { return departments; }
    public void setDepartments(List<DepartmentInfo> departments) { this.departments = departments; }

    public boolean isAllowAllDepartments() { return allowAllDepartments; }
    public void setAllowAllDepartments(boolean allowAllDepartments) { this.allowAllDepartments = allowAllDepartments; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
