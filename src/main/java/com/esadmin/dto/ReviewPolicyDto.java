package com.esadmin.dto;

import java.time.LocalDateTime;

/**
 * 审核策略DTO
 */
public class ReviewPolicyDto {
    
    private Long id;
    private String sourceType;
    private String sourceId;
    private String sourceName;
    private String reviewMode;
    private String reviewModeDisplay;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
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
        // 设置显示名称
        if ("view_first".equals(reviewMode)) {
            this.reviewModeDisplay = "先看后审";
        } else if ("review_first".equals(reviewMode)) {
            this.reviewModeDisplay = "先审后看";
        } else {
            this.reviewModeDisplay = reviewMode;
        }
    }
    
    public String getReviewModeDisplay() {
        return reviewModeDisplay;
    }
    
    public void setReviewModeDisplay(String reviewModeDisplay) {
        this.reviewModeDisplay = reviewModeDisplay;
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