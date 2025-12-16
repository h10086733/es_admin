package com.esadmin.dto;

import java.util.ArrayList;
import java.util.List;

public class DepartmentTreeNode {
    private String id;
    private String name;
    private String type;
    private List<DepartmentTreeNode> children = new ArrayList<>();

    public DepartmentTreeNode() {}

    public DepartmentTreeNode(String id, String name, String type) {
        this.id = id;
        this.name = name;
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<DepartmentTreeNode> getChildren() {
        return children;
    }

    public void setChildren(List<DepartmentTreeNode> children) {
        this.children = children;
    }

    public void addChild(DepartmentTreeNode child) {
        this.children.add(child);
    }
}
