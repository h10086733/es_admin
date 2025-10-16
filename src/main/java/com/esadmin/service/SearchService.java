package com.esadmin.service;

import com.esadmin.dto.FormDto;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.GetIndexRequest;
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
    private final FormUrlService formUrlService;
    private final ExcelImportService excelImportService;
    
    public SearchService(RestHighLevelClient esClient,
                         FormService formService,
                         FormUrlService formUrlService,
                         ExcelImportService excelImportService) {
        this.esClient = esClient;
        this.formService = formService;
        this.formUrlService = formUrlService;
        this.excelImportService = excelImportService;
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

            Set<String> indices = new LinkedHashSet<>();
            indices.addAll(fetchIndices("form_*"));
            indices.addAll(fetchIndices("excel_*"));

            return indices.toArray(new String[0]);

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
        Map<String, String> excelCache = new HashMap<>();

        for (SearchHit hit : response.getHits().getHits()) {
            Map<String, Object> source = hit.getSourceAsMap();
            String sourceType = StringUtils.defaultIfBlank((String) source.get("source_type"), "form");
            String tableName = (String) source.get("table_name");
            String recordId = source.get("record_id") != null ? String.valueOf(source.get("record_id")) : "";

            String formId = (String) source.get("form_id");
            String formName;
            String jumpUrl = null;

            if ("excel".equalsIgnoreCase(sourceType)) {
                String cacheKey = StringUtils.defaultIfBlank(tableName, "excel_dataset");
                formName = excelCache.computeIfAbsent(cacheKey, key -> {
                    String display = excelImportService.getDisplayName(key);
                    return StringUtils.isNotBlank(display) ? display : key;
                });
                formId = "excel:" + cacheKey;
            } else {
                String cacheKey = StringUtils.defaultIfBlank(formId, "unknown_form");
                formName = formCache.computeIfAbsent(cacheKey, id -> {
                    try {
                        FormDto form = formService.getFormById(id);
                        return form != null ? form.getName() : "未知表单";
                    } catch (Exception e) {
                        log.error("获取表单名称失败", e);
                        return "未知表单";
                    }
                });
                jumpUrl = formUrlService.generateFormUrl(cacheKey);
                formId = cacheKey;
            }

            Map<String, Object> displayData = extractDisplayData(source, sourceType);
            Map<String, List<String>> highlight = extractHighlight(hit);

            if ("excel".equalsIgnoreCase(sourceType)) {
                Object sheetName = source.get("sheet_name");
                if (sheetName != null && StringUtils.isNotBlank(sheetName.toString())) {
                    displayData.putIfAbsent("工作表", sheetName);
                }
                displayData.putIfAbsent("Excel名称", formName);
            }

            com.esadmin.dto.SearchResponse.SearchHit searchHit = new com.esadmin.dto.SearchResponse.SearchHit();
            searchHit.setScore(hit.getScore());
            searchHit.setFormId(formId);
            searchHit.setFormName("excel".equalsIgnoreCase(sourceType) ? formName + " (Excel)" : formName);
            searchHit.setTableName(tableName);
            searchHit.setRecordId(recordId);
            searchHit.setData(displayData);
            searchHit.setHighlight(highlight);
            searchHit.setJumpUrl(jumpUrl);
            searchHit.setSourceType(sourceType);

            hits.add(searchHit);
        }

        result.setHits(hits);
        result.setTotal(response.getHits().getTotalHits().value);
        result.setMaxScore(response.getHits().getMaxScore());
        result.setElapsedTime((System.currentTimeMillis() - startTime) / 1000.0);
        result.setTook(response.getTook().getMillis());

        return result;
    }

    private Map<String, Object> extractDisplayData(Map<String, Object> source, String sourceType) {
        Set<String> systemFields = new HashSet<>(Arrays.asList(
                "form_id", "table_name", "record_id", "sync_time", "source_type", "excel_name", "sheet_name", "column_labels"
        ));

        Map<String, Object> displayData = new LinkedHashMap<>();
        Map<String, String> labelMap = Collections.emptyMap();
        if ("excel".equalsIgnoreCase(sourceType)) {
            Object labelsObj = source.get("column_labels");
            if (labelsObj instanceof Map) {
                Map<String, String> mapped = new LinkedHashMap<>();
                ((Map<?, ?>) labelsObj).forEach((k, v) -> {
                    if (k != null && v != null) {
                        mapped.put(k.toString(), v.toString());
                    }
                });
                labelMap = mapped;
            }
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value == null) {
                continue;
            }
            if (systemFields.contains(key) || key.startsWith("_primary_")) {
                continue;
            }
            if (value instanceof String && StringUtils.isBlank((String) value)) {
                continue;
            }

            if ("column_labels".equals(key)) {
                continue;
            }

            String displayKey = key;
            if (!labelMap.isEmpty() && labelMap.containsKey(key)) {
                String mappedKey = labelMap.get(key);
                if (StringUtils.isNotBlank(mappedKey)) {
                    displayKey = mappedKey;
                    if (displayData.containsKey(displayKey) && Objects.equals(displayData.get(displayKey), value)) {
                        continue;
                    }
                }
            }

            displayData.put(displayKey, value);
        }

        return displayData;
    }

    private Map<String, List<String>> extractHighlight(SearchHit hit) {
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
        return highlight;
    }

    private List<String> fetchIndices(String pattern) {
        try {
            String[] indices = esClient.indices().get(new GetIndexRequest(pattern), RequestOptions.DEFAULT).getIndices();
            return Arrays.asList(indices);
        } catch (Exception e) {
            log.debug("索引匹配为空: pattern={}, error={}", pattern, e.getMessage());
            return Collections.emptyList();
        }
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
