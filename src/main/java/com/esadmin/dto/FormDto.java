package com.esadmin.dto;

import java.util.List;
import java.util.Map;

public class FormDto {
    private String id;
    private String name;
    private Map<String, Object> fieldInfo;
    private Map<String, Object> viewInfo;
    private Map<String, Object> appbindInfo;
    private Map<String, Object> extensionsInfo;
    
    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public Map<String, Object> getFieldInfo() { return fieldInfo; }
    public void setFieldInfo(Map<String, Object> fieldInfo) { this.fieldInfo = fieldInfo; }
    
    public Map<String, Object> getViewInfo() { return viewInfo; }
    public void setViewInfo(Map<String, Object> viewInfo) { this.viewInfo = viewInfo; }
    
    public Map<String, Object> getAppbindInfo() { return appbindInfo; }
    public void setAppbindInfo(Map<String, Object> appbindInfo) { this.appbindInfo = appbindInfo; }
    
    public Map<String, Object> getExtensionsInfo() { return extensionsInfo; }
    public void setExtensionsInfo(Map<String, Object> extensionsInfo) { this.extensionsInfo = extensionsInfo; }
}

class FormFieldInfo {
    private String name;
    private String columnName;
    private String type;
    private String fieldType;
    private String display;
    private String label;
    private String title;
    
    // Getters and Setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getColumnName() { return columnName; }
    public void setColumnName(String columnName) { this.columnName = columnName; }
    
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    
    public String getFieldType() { return fieldType; }
    public void setFieldType(String fieldType) { this.fieldType = fieldType; }
    
    public String getDisplay() { return display; }
    public void setDisplay(String display) { this.display = display; }
    
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
}

class SubTableInfo {
    private String tableName;
    private String rawTableName;
    private String displayName;
    private String frontTableName;
    private List<FormFieldInfo> fields;
    private String ownerTable;
    private String tableType;
    
    // Getters and Setters
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    
    public String getRawTableName() { return rawTableName; }
    public void setRawTableName(String rawTableName) { this.rawTableName = rawTableName; }
    
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    
    public String getFrontTableName() { return frontTableName; }
    public void setFrontTableName(String frontTableName) { this.frontTableName = frontTableName; }
    
    public List<FormFieldInfo> getFields() { return fields; }
    public void setFields(List<FormFieldInfo> fields) { this.fields = fields; }
    
    public String getOwnerTable() { return ownerTable; }
    public void setOwnerTable(String ownerTable) { this.ownerTable = ownerTable; }
    
    public String getTableType() { return tableType; }
    public void setTableType(String tableType) { this.tableType = tableType; }
}