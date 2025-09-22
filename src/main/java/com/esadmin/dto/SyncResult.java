package com.esadmin.dto;

public class SyncResult {
    private boolean success;
    private String message;
    private long count;
    private long total;
    private double elapsedTime;
    private double rate;
    private String formName;
    private String formId;
    private String type;
    private String error;
    
    // Getters and Setters
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    
    public long getCount() { return count; }
    public void setCount(long count) { this.count = count; }
    
    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }
    
    public double getElapsedTime() { return elapsedTime; }
    public void setElapsedTime(double elapsedTime) { this.elapsedTime = elapsedTime; }
    
    public double getRate() { return rate; }
    public void setRate(double rate) { this.rate = rate; }
    
    public String getFormName() { return formName; }
    public void setFormName(String formName) { this.formName = formName; }
    
    public String getFormId() { return formId; }
    public void setFormId(String formId) { this.formId = formId; }
    
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}