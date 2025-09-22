package com.esadmin.dto;

public class SyncRequest {
    private Boolean fullSync = true;
    
    public Boolean getFullSync() { return fullSync; }
    public void setFullSync(Boolean fullSync) { this.fullSync = fullSync; }
}