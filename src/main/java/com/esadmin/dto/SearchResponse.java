package com.esadmin.dto;

import java.util.List;
import java.util.Map;

public class SearchResponse {
    private List<SearchHit> hits;
    private long total;
    private Float maxScore;
    private double elapsedTime;
    private long took;
    private String error;
    
    // Getters and Setters
    public List<SearchHit> getHits() { return hits; }
    public void setHits(List<SearchHit> hits) { this.hits = hits; }
    
    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }
    
    public Float getMaxScore() { return maxScore; }
    public void setMaxScore(Float maxScore) { this.maxScore = maxScore; }
    
    public double getElapsedTime() { return elapsedTime; }
    public void setElapsedTime(double elapsedTime) { this.elapsedTime = elapsedTime; }
    
    public long getTook() { return took; }
    public void setTook(long took) { this.took = took; }
    
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    
    public static class SearchHit {
        private float score;
        private String formId;
        private String formName;
        private String tableName;
        private String recordId;
        private Map<String, Object> data;
        private Map<String, List<String>> highlight;
        
        // Getters and Setters
        public float getScore() { return score; }
        public void setScore(float score) { this.score = score; }
        
        public String getFormId() { return formId; }
        public void setFormId(String formId) { this.formId = formId; }
        
        public String getFormName() { return formName; }
        public void setFormName(String formName) { this.formName = formName; }
        
        public String getTableName() { return tableName; }
        public void setTableName(String tableName) { this.tableName = tableName; }
        
        public String getRecordId() { return recordId; }
        public void setRecordId(String recordId) { this.recordId = recordId; }
        
        public Map<String, Object> getData() { return data; }
        public void setData(Map<String, Object> data) { this.data = data; }
        
        public Map<String, List<String>> getHighlight() { return highlight; }
        public void setHighlight(Map<String, List<String>> highlight) { this.highlight = highlight; }
    }
}