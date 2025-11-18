package com.esadmin.dto;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Excel 导入元数据。
 */
public class ExcelImportMetadata {

    private Long id;
    private String tableName;
    private String indexName;
    private String displayName;
    private String sheetName;
    private int rowCount;
    private LocalDateTime importTime;
    private Map<String, String> columnLabels = new LinkedHashMap<>();
    private String reviewMode; // 审核模式：view_first(先看后审), review_first(先审后看)

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getIndexName() {
        return indexName;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getSheetName() {
        return sheetName;
    }

    public void setSheetName(String sheetName) {
        this.sheetName = sheetName;
    }

    public int getRowCount() {
        return rowCount;
    }

    public void setRowCount(int rowCount) {
        this.rowCount = rowCount;
    }

    public LocalDateTime getImportTime() {
        return importTime;
    }

    public void setImportTime(LocalDateTime importTime) {
        this.importTime = importTime;
    }

    public Map<String, String> getColumnLabels() {
        return columnLabels;
    }

    public void setColumnLabels(Map<String, String> columnLabels) {
        this.columnLabels = columnLabels;
    }
    
    public String getReviewMode() {
        return reviewMode;
    }
    
    public void setReviewMode(String reviewMode) {
        this.reviewMode = reviewMode;
    }
}
