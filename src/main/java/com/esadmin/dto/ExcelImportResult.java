package com.esadmin.dto;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Excel 导入操作结果描述。
 */
public class ExcelImportResult {

    private String tableName;
    private String indexName;
    private String displayName;
    private String sheetName;
    private int rowCount;
    private LocalDateTime importTime;
    private Map<String, String> columnLabels = new LinkedHashMap<>();

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
}
