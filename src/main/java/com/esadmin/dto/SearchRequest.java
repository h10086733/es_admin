package com.esadmin.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.PositiveOrZero;
import java.util.List;

public class SearchRequest {
    
    @NotBlank(message = "搜索关键词不能为空")
    private String query;
    
    private List<String> formIds;
    
    @PositiveOrZero(message = "size必须大于等于0")
    private Integer size = 10;
    
    @PositiveOrZero(message = "from必须大于等于0") 
    private Integer from = 0;
    
    // Getters and Setters
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    
    public List<String> getFormIds() { return formIds; }
    public void setFormIds(List<String> formIds) { this.formIds = formIds; }
    
    public Integer getSize() { return size; }
    public void setSize(Integer size) { this.size = size; }
    
    public Integer getFrom() { return from; }
    public void setFrom(Integer from) { this.from = from; }
}