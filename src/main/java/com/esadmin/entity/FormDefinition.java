package com.esadmin.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import javax.persistence.*;
import java.util.Map;

@Entity
@Table(name = "CAP_FORM_DEFINITION")
public class FormDefinition {

    @Id
    @JsonSerialize(using = ToStringSerializer.class)
    @Column(name = "ID")
    private Long id;

    @Column(name = "NAME")
    private String name;

    @Column(name = "FIELD_INFO", columnDefinition = "TEXT")
    private String fieldInfo;

    @Column(name = "VIEW_INFO", columnDefinition = "TEXT")
    private String viewInfo;

    @Column(name = "APPBIND_INFO", columnDefinition = "TEXT")
    private String appbindInfo;

    @Column(name = "EXTENSIONS_INFO", columnDefinition = "TEXT")
    private String extensionsInfo;

    @Column(name = "DELETE_FLAG")
    private Integer deleteFlag;

    // 转换为DTO对象
    @Transient
    private Map<String, Object> fieldInfoMap;

    @Transient
    private Map<String, Object> viewInfoMap;

    @Transient
    private Map<String, Object> appbindInfoMap;

    @Transient
    private Map<String, Object> extensionsInfoMap;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getFieldInfo() { return fieldInfo; }
    public void setFieldInfo(String fieldInfo) { this.fieldInfo = fieldInfo; }
    
    public String getViewInfo() { return viewInfo; }
    public void setViewInfo(String viewInfo) { this.viewInfo = viewInfo; }
    
    public String getAppbindInfo() { return appbindInfo; }
    public void setAppbindInfo(String appbindInfo) { this.appbindInfo = appbindInfo; }
    
    public String getExtensionsInfo() { return extensionsInfo; }
    public void setExtensionsInfo(String extensionsInfo) { this.extensionsInfo = extensionsInfo; }
    
    public Integer getDeleteFlag() { return deleteFlag; }
    public void setDeleteFlag(Integer deleteFlag) { this.deleteFlag = deleteFlag; }
    
    public Map<String, Object> getFieldInfoMap() { return fieldInfoMap; }
    public void setFieldInfoMap(Map<String, Object> fieldInfoMap) { this.fieldInfoMap = fieldInfoMap; }
    
    public Map<String, Object> getViewInfoMap() { return viewInfoMap; }
    public void setViewInfoMap(Map<String, Object> viewInfoMap) { this.viewInfoMap = viewInfoMap; }
    
    public Map<String, Object> getAppbindInfoMap() { return appbindInfoMap; }
    public void setAppbindInfoMap(Map<String, Object> appbindInfoMap) { this.appbindInfoMap = appbindInfoMap; }
    
    public Map<String, Object> getExtensionsInfoMap() { return extensionsInfoMap; }
    public void setExtensionsInfoMap(Map<String, Object> extensionsInfoMap) { this.extensionsInfoMap = extensionsInfoMap; }
}
