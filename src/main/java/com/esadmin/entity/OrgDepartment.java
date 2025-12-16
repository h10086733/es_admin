package com.esadmin.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import javax.persistence.*;

@Entity
@Table(name = "ORG_UNIT")
public class OrgDepartment {

    @Id
    @JsonSerialize(using = ToStringSerializer.class)
    @Column(name = "ID")
    private Long id;

    @Column(name = "NAME", length = 500)
    private String name;

    @Column(name = "SECOND_NAME", length = 500)
    private String secondName;

    @Column(name = "CODE", length = 500)
    private String code;

    @Column(name = "SHORT_NAME", length = 500)
    private String shortName;

    @Column(name = "TYPE", length = 50)
    private String type;

    @Column(name = "IS_GROUP", columnDefinition = "SMALLINT DEFAULT 0")
    private Integer isGroup = 0;

    @Column(name = "PATH", length = 500)
    private String path;

    @Column(name = "IS_INTERNAL", columnDefinition = "SMALLINT DEFAULT 0")
    private Integer isInternal = 0;

    @Column(name = "SORT_ID")
    private Integer sortId;

    @Column(name = "IS_ENABLE", columnDefinition = "SMALLINT DEFAULT 0")
    private Integer isEnable = 0;

    @Column(name = "IS_DELETED", columnDefinition = "SMALLINT DEFAULT 0")
    private Integer isDeleted = 0;

    @Column(name = "STATUS", columnDefinition = "SMALLINT DEFAULT 0")
    private Integer status = 0;

    @Column(name = "LEVEL_SCOPE")
    private Integer levelScope;

    @Column(name = "ORG_ACCOUNT_ID")
    private Long orgAccountId;

    @Column(name = "DESCRIPTION", length = 4000)
    private String description;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSecondName() { return secondName; }
    public void setSecondName(String secondName) { this.secondName = secondName; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getShortName() { return shortName; }
    public void setShortName(String shortName) { this.shortName = shortName; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Integer getIsGroup() { return isGroup; }
    public void setIsGroup(Integer isGroup) { this.isGroup = isGroup; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public Integer getIsInternal() { return isInternal; }
    public void setIsInternal(Integer isInternal) { this.isInternal = isInternal; }

    public Integer getSortId() { return sortId; }
    public void setSortId(Integer sortId) { this.sortId = sortId; }

    public Integer getIsEnable() { return isEnable; }
    public void setIsEnable(Integer isEnable) { this.isEnable = isEnable; }

    public Integer getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Integer isDeleted) { this.isDeleted = isDeleted; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Integer getLevelScope() { return levelScope; }
    public void setLevelScope(Integer levelScope) { this.levelScope = levelScope; }

    public Long getOrgAccountId() { return orgAccountId; }
    public void setOrgAccountId(Long orgAccountId) { this.orgAccountId = orgAccountId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
