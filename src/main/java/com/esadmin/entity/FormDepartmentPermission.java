package com.esadmin.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "form_department_permission", 
       indexes = {
           @Index(name = "idx_source_type_id", columnList = "source_type,source_id"),
           @Index(name = "idx_dept_id", columnList = "department_id"),
           @Index(name = "uk_source_dept", columnList = "source_type,source_id,department_id", unique = true)
       })
public class FormDepartmentPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonSerialize(using = ToStringSerializer.class)
    @Column(name = "id")
    private Long id;

    @Column(name = "source_type", length = 20, nullable = false)
    private String sourceType; // "form" 或 "excel"

    @Column(name = "source_id", length = 100, nullable = false)
    private String sourceId; // 表单ID或Excel表名

    @Column(name = "source_name", length = 500)
    private String sourceName; // 表单名称或Excel显示名称

    @Column(name = "department_id", length = 64, nullable = false)
    private String departmentId;

    @Column(name = "department_name", length = 500)
    private String departmentName;

    @Column(name = "permission_type", length = 20, nullable = false, columnDefinition = "VARCHAR(20) DEFAULT 'read'")
    private String permissionType = "read"; // read, write, admin

    @Column(name = "is_active", nullable = false, columnDefinition = "SMALLINT DEFAULT 1")
    private Integer isActive = 1; // 1=启用，0=禁用

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @Column(name = "creator_id")
    private Long creatorId;

    @Column(name = "remark", length = 1000)
    private String remark;

    // 投影构造函数 - 用于优化查询
    public FormDepartmentPermission(String sourceType, String sourceId, String sourceName, 
                                  String departmentId, String departmentName, 
                                  String permissionType, LocalDateTime updateTime) {
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.sourceName = sourceName;
        this.departmentId = departmentId;
        this.departmentName = departmentName;
        this.permissionType = permissionType;
        this.updateTime = updateTime;
        this.isActive = 1; // 查询出来的都是活跃的
    }

    // 默认构造函数
    public FormDepartmentPermission() {}

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }

    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }

    public String getDepartmentId() { return departmentId; }
    public void setDepartmentId(String departmentId) { this.departmentId = departmentId; }

    public String getDepartmentName() { return departmentName; }
    public void setDepartmentName(String departmentName) { this.departmentName = departmentName; }

    public String getPermissionType() { return permissionType; }
    public void setPermissionType(String permissionType) { this.permissionType = permissionType; }

    public Integer getIsActive() { return isActive; }
    public void setIsActive(Integer isActive) { this.isActive = isActive; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }

    public Long getCreatorId() { return creatorId; }
    public void setCreatorId(Long creatorId) { this.creatorId = creatorId; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
