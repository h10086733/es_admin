package com.esadmin.service;

import com.esadmin.dto.FormDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MultiMatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    
    private final RestHighLevelClient esClient;
    private final FormService formService;
    private final ObjectMapper objectMapper;
    
    public SearchService(RestHighLevelClient esClient, FormService formService, ObjectMapper objectMapper) {
        this.esClient = esClient;
        this.formService = formService;
        this.objectMapper = objectMapper;
    }

    @Value("${app.search.max-size:100}")
    private int maxSize;

    @Value("${app.search.timeout:30}")
    private int timeout;

    public com.esadmin.dto.SearchResponse searchData(com.esadmin.dto.SearchRequest request) {
        long startTime = System.currentTimeMillis();
        
        try {
            if (request.getQuery() == null || request.getQuery().trim().isEmpty()) {
                return createEmptyResponse();
            }

            // 构建索引名称
            String[] indices = buildIndices(request.getFormIds());
            if (indices.length == 0) {
                return createEmptyResponse();
            }

            // 构建搜索请求
            SearchRequest searchRequest = new SearchRequest(indices);
            SearchSourceBuilder sourceBuilder = buildSearchSource(request);
            searchRequest.source(sourceBuilder);

            // 执行搜索
            SearchResponse response = esClient.search(searchRequest, RequestOptions.DEFAULT);
            
            // 转换结果
            return convertSearchResponse(response, startTime);

        } catch (Exception e) {
            log.error("搜索失败", e);
            return createErrorResponse(e.getMessage(), startTime);
        }
    }

    private String[] buildIndices(List<String> formIds) {
        try {
            if (formIds != null && !formIds.isEmpty()) {
                return formIds.stream()
                    .map(id -> "form_" + id)
                    .toArray(String[]::new);
            }

            // 获取所有form索引
            org.elasticsearch.client.indices.GetIndexRequest getIndexRequest = 
                new org.elasticsearch.client.indices.GetIndexRequest("form_*");
            
            String[] allIndices = esClient.indices()
                .get(getIndexRequest, RequestOptions.DEFAULT)
                .getIndices();
            
            return allIndices;
            
        } catch (Exception e) {
            log.error("获取索引列表失败", e);
            return new String[0];
        }
    }

    private SearchSourceBuilder buildSearchSource(com.esadmin.dto.SearchRequest request) {
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();

        // 构建查询
        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
        
        // 精确匹配得分更高
        boolQuery.should(QueryBuilders.multiMatchQuery(request.getQuery())
            .fields(Map.of("*", 1.0f))
            .type(MultiMatchQueryBuilder.Type.PHRASE)
            .boost(3.0f));
        
        // 前缀匹配
        boolQuery.should(QueryBuilders.multiMatchQuery(request.getQuery())
            .fields(Map.of("*", 1.0f))
            .type(MultiMatchQueryBuilder.Type.PHRASE_PREFIX)
            .boost(2.0f));
        
        // 模糊匹配
        boolQuery.should(QueryBuilders.multiMatchQuery(request.getQuery())
            .fields(Map.of("*", 1.0f))
            .type(MultiMatchQueryBuilder.Type.BEST_FIELDS)
            .fuzziness("AUTO")
            .boost(1.0f));
        
        boolQuery.minimumShouldMatch(1);
        sourceBuilder.query(boolQuery);

        // 高亮设置
        HighlightBuilder highlightBuilder = new HighlightBuilder();
        highlightBuilder.field("*")
            .fragmentSize(150)
            .numOfFragments(3)
            .preTags("<mark>")
            .postTags("</mark>");
        sourceBuilder.highlighter(highlightBuilder);

        // 分页和排序
        sourceBuilder.size(Math.min(request.getSize(), maxSize));
        sourceBuilder.from(request.getFrom());
        sourceBuilder.sort("_score", SortOrder.DESC);
        sourceBuilder.sort("sync_time", SortOrder.DESC);

        // 排除不必要的字段
        sourceBuilder.fetchSource(null, new String[]{"sync_time"});
        
        // 超时设置
        sourceBuilder.timeout(new TimeValue(timeout, TimeUnit.SECONDS));

        return sourceBuilder;
    }

    private com.esadmin.dto.SearchResponse convertSearchResponse(org.elasticsearch.action.search.SearchResponse response, long startTime) {
        com.esadmin.dto.SearchResponse result = new com.esadmin.dto.SearchResponse();
        
        List<com.esadmin.dto.SearchResponse.SearchHit> hits = new ArrayList<>();
        Map<String, String> formCache = new HashMap<>();

        for (SearchHit hit : response.getHits().getHits()) {
            Map<String, Object> source = hit.getSourceAsMap();
            String formId = (String) source.get("form_id");

            // 获取表单名称（使用缓存）
            String formName = formCache.computeIfAbsent(formId, id -> {
                try {
                    FormDto form = formService.getFormById(id);
                    return form != null ? form.getName() : "未知表单";
                } catch (Exception e) {
                    log.error("获取表单名称失败", e);
                    return "未知表单";
                }
            });

            // 提取显示数据
            Map<String, Object> displayData = extractDisplayData(source);

            // 转换高亮
            Map<String, List<String>> highlight = new HashMap<>();
            if (hit.getHighlightFields() != null) {
                hit.getHighlightFields().forEach((key, value) -> {
                    List<String> fragments = new ArrayList<>();
                    for (org.elasticsearch.common.text.Text fragment : value.getFragments()) {
                        fragments.add(fragment.string());
                    }
                    highlight.put(key, fragments);
                });
            }

            com.esadmin.dto.SearchResponse.SearchHit searchHit = 
                new com.esadmin.dto.SearchResponse.SearchHit();
            searchHit.setScore(hit.getScore());
            searchHit.setFormId(formId);
            searchHit.setFormName(formName);
            searchHit.setTableName((String) source.get("table_name"));
            searchHit.setRecordId(String.valueOf(source.get("record_id")));
            searchHit.setData(displayData);
            searchHit.setHighlight(highlight);

            hits.add(searchHit);
        }

        result.setHits(hits);
        result.setTotal(response.getHits().getTotalHits().value);
        result.setMaxScore(response.getHits().getMaxScore());
        result.setElapsedTime((System.currentTimeMillis() - startTime) / 1000.0);
        result.setTook(response.getTook().getMillis());

        return result;
    }

    private Map<String, Object> extractDisplayData(Map<String, Object> source) {
        // 定义系统字段，不在搜索结果中显示
        Set<String> systemFields = Set.of(
            "form_id", "table_name", "record_id", "sync_time"
        );

        Map<String, Object> displayData = new HashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // 跳过系统字段和空值
            if (systemFields.contains(key) || value == null || "".equals(value)) {
                continue;
            }

            displayData.put(key, value);
        }

        return displayData;
    }

    private com.esadmin.dto.SearchResponse createEmptyResponse() {
        com.esadmin.dto.SearchResponse response = new com.esadmin.dto.SearchResponse();
        response.setHits(new ArrayList<>());
        response.setTotal(0);
        return response;
    }

    private com.esadmin.dto.SearchResponse createErrorResponse(String error, long startTime) {
        com.esadmin.dto.SearchResponse response = createEmptyResponse();
        response.setError(error);
        response.setElapsedTime((System.currentTimeMillis() - startTime) / 1000.0);
        return response;
    }
}