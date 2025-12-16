package com.esadmin.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import javax.persistence.*;

@Entity
@Table(name = "ORG_MEMBER")
public class OrgMember {

    @Id
    @JsonSerialize(using = ToStringSerializer.class)
    @Column(name = "ID")
    private Long id;

    @Column(name = "NAME")
    private String name;

    @Column(name = "ORG_DEPARTMENT_ID")
    private Long orgDepartmentId;

    @Column(name = "IS_ADMIN", columnDefinition = "SMALLINT DEFAULT 0")
    private Integer isAdmin = 0;
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Long getOrgDepartmentId() { return orgDepartmentId; }
    public void setOrgDepartmentId(Long orgDepartmentId) { this.orgDepartmentId = orgDepartmentId; }

    public Integer getIsAdmin() { return isAdmin; }
    public void setIsAdmin(Integer isAdmin) { this.isAdmin = isAdmin; }
}
