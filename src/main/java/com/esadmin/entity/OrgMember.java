package com.esadmin.entity;

import javax.persistence.*;

@Entity
@Table(name = "ORG_MEMBER")
public class OrgMember {

    @Id
    @Column(name = "ID")
    private Long id;

    @Column(name = "NAME")
    private String name;
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}