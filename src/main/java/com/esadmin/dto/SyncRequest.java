package com.esadmin.dto;
import com.fasterxml.jackson.annotation.JsonProperty;

public class SyncRequest {
    @JsonProperty("full_sync")
    private Boolean fullSync = Boolean.FALSE;

    public Boolean getFullSync() { return fullSync; }
    public void setFullSync(Boolean fullSync) { this.fullSync = fullSync; }
}
