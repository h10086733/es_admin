package com.esadmin.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 审核策略实体类
 */
@Entity
@Table(name = "review_policy")
public class ReviewPolicy {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType; // "form" 或 "excel"
    
    @Column(name = "source_id", nullable = false, length = 100)
    private String sourceId; // 表单ID或Excel表名
    
    @Column(name = "source_name", length = 200)
    private String sourceName; // 表单名称或Excel显示名称
    
    @Column(name = "review_mode", nullable = false, length = 20)
    private String reviewMode; // "view_first" 先看后审, "review_first" 先审后看
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getSourceType() {
        return sourceType;
    }
    
    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }
    
    public String getSourceId() {
        return sourceId;
    }
    
    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }
    
    public String getSourceName() {
        return sourceName;
    }
    
    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }
    
    public String getReviewMode() {
        return reviewMode;
    }
    
    public void setReviewMode(String reviewMode) {
        this.reviewMode = reviewMode;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}