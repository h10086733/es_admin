package com.esadmin.controller;

import com.esadmin.dto.SearchRequest;
import com.esadmin.dto.SearchResponse;
import com.esadmin.service.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/search")
@Validated
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);
    
    private final SearchService searchService;
    
    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@Valid @RequestBody SearchRequest request) {
        try {
            log.info("执行搜索: query={}, size={}, from={}", request.getQuery(), request.getSize(), request.getFrom());
            
            SearchResponse result = searchService.searchData(request);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", Map.of(
                "query", request.getQuery(),
                "hits", result.getHits(),
                "total", result.getTotal(),
                "max_score", result.getMaxScore(),
                "size", request.getSize(),
                "from", request.getFrom()
            ));
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("搜索请求处理失败", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            
            return ResponseEntity.status(500).body(response);
        }
    }

    @GetMapping("/record/{formId}/{recordId}")
    public ResponseEntity<Map<String, Object>> getRecordDetail(
            @PathVariable String formId, 
            @PathVariable String recordId) {
        try {
            // TODO: 实现获取记录详情的逻辑
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", Map.of(
                "form_id", formId,
                "record_id", recordId,
                "record", Map.of() // 空记录，需要实现具体逻辑
            ));
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("获取记录详情失败", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            
            return ResponseEntity.status(500).body(response);
        }
    }
}