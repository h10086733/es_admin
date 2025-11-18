package com.esadmin.controller;

import com.esadmin.dto.SearchRequest;
import com.esadmin.dto.SearchResponse;
import com.esadmin.service.KeyReviewService;
import com.esadmin.service.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/search")
@Validated
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);
    private final SearchService searchService;
    private final KeyReviewService keyReviewService;

    public SearchController(SearchService searchService, KeyReviewService keyReviewService) {
        this.searchService = searchService;
        this.keyReviewService = keyReviewService;
    }

    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@Valid @RequestBody SearchRequest request) {
        try {
            log.info("执行搜索: query={}, size={}, from={}, userId={}",
                    request.getQuery(), request.getSize(), request.getFrom(), request.getUserId());

            KeyReviewService.ReviewDecision decision =
                    keyReviewService.reviewKeyword(request.getUserId(), request.getQuery());

            if (!decision.isApproved()) {
                log.info("关键字审核未通过: userId={}, reason={}", request.getUserId(), decision.getMessage());

                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("message", decision.getMessage());

                Map<String, Object> data = new HashMap<>();
                data.put("result", decision.getMessage());
                response.put("data", data);

                return ResponseEntity.ok(response);
            }

            String reviewMessage = decision.getMessage();
            
            SearchResponse result = searchService.searchData(request);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            
            Map<String, Object> data = new HashMap<>();
            data.put("query", request.getQuery());
            data.put("hits", result.getHits() != null ? result.getHits() : new ArrayList<>());
            data.put("total", result.getTotal());
            data.put("max_score", result.getMaxScore() != null ? result.getMaxScore() : 0.0);
            data.put("size", request.getSize());
            data.put("from", request.getFrom());
            data.put("review_result", reviewMessage);
            data.put("detail_base_url", searchService.getDetailBaseUrl());
            
            // 添加过滤信息
            if (result.getFilteredCount() != null && result.getFilteredCount() > 0) {
                data.put("filteredCount", result.getFilteredCount());
                data.put("filterMessage", result.getFilterMessage());
            }

            response.put("data", data);

            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("搜索请求处理失败", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            
            return ResponseEntity.status(500).body(response);
        }
    }

    @GetMapping("/forms")
    public ResponseEntity<Map<String, Object>> getSearchForms() {
        try {
            List<Map<String, Object>> forms = searchService.getFormDocumentStats();

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", forms);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("获取搜索表单列表失败", e);

            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());

            return ResponseEntity.status(500).body(response);
        }
    }

    @PostMapping("/forms")
    public ResponseEntity<Map<String, Object>> getSearchFormStats(@Valid @RequestBody SearchRequest request) {
        try {
            List<Map<String, Object>> forms = searchService.getSearchFormStats(request);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", forms);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("获取搜索表单统计失败", e);

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

    @GetMapping("/admin/check/{userId}")
    public ResponseEntity<Map<String, Object>> checkIfAdmin(@PathVariable String userId) {
        try {
            boolean isAdmin = searchService.checkIfAdmin(userId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", Map.of("isAdmin", isAdmin));
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("检查管理员权限失败: userId={}", userId, e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            
            return ResponseEntity.status(500).body(response);
        }
    }
}
