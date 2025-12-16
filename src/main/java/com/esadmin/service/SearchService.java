package com.esadmin.service;

import com.esadmin.dto.ExcelImportMetadata;
import com.esadmin.dto.FormDto;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.core.CountRequest;
import org.elasticsearch.client.core.CountResponse;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MultiMatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    
    private final RestHighLevelClient esClient;
    private final FormService formService;
    private final ExcelImportService excelImportService;
    private final ReviewPolicyService reviewPolicyService;
    private final KeyReviewService keyReviewService;
    private final AdminCheckService adminCheckService;
    private final FormDepartmentPermissionServiceUltra departmentPermissionService;
    private final MemberService memberService;

    public SearchService(RestHighLevelClient esClient,
                         FormService formService,
                         ExcelImportService excelImportService,
                         ReviewPolicyService reviewPolicyService,
                         KeyReviewService keyReviewService,
                         AdminCheckService adminCheckService,
                         FormDepartmentPermissionServiceUltra departmentPermissionService,
                         MemberService memberService) {
        this.esClient = esClient;
        this.formService = formService;
        this.excelImportService = excelImportService;
        this.reviewPolicyService = reviewPolicyService;
        this.keyReviewService = keyReviewService;
        this.adminCheckService = adminCheckService;
        this.departmentPermissionService = departmentPermissionService;
        this.memberService = memberService;
    }

    @Value("${app.search.max-size:100}")
    private int maxSize;

    @Value("${app.search.timeout:30}")
    private int timeout;
    
    /**
     * 根据用户部门权限过滤数据源ID列表
     */
    private static final String SUPER_DEPARTMENT_ID = "-1";

    private PermissionFilterResult filterDataSourcesByPermission(List<String> requestedIds, String userIdStr) {
        if (StringUtils.isBlank(userIdStr)) {
            log.warn("用户ID为空，拒绝数据访问");
            return PermissionFilterResult.deny("缺少用户ID，无法校验部门权限");
        }

        AdminCheckService.AdminPermission permission = adminCheckService.checkUserPermission(userIdStr);
        if (!permission.isView()) {
            log.warn("用户 {} 没有查看权限，isView=false", userIdStr);
            return PermissionFilterResult.deny("您没有权限访问，请联系管理员");
        }

        Long userId;
        try {
            userId = Long.valueOf(userIdStr);
        } catch (NumberFormatException e) {
            log.warn("用户ID格式无效: {}", userIdStr);
            return PermissionFilterResult.deny("用户ID格式无效，无法校验部门权限");
        }

        try {
            MemberService.MemberDepartment memberInfo = memberService.info(userId);
            if (memberInfo == null) {
                log.warn("未找到用户 {} 的部门信息，拒绝访问", userId);
                return PermissionFilterResult.deny("未找到您的部门信息，请联系管理员配置组织关系");
            }

            if (memberInfo.isAdmin() || SUPER_DEPARTMENT_ID.equals(memberInfo.getDepartmentId())) {
                List<String> accessible = collectAllDataSourceIds();
                if (requestedIds != null && !requestedIds.isEmpty()) {
                    accessible = new ArrayList<>(requestedIds);
                }
                log.info("用户 {} 为超级部门成员，允许访问 {} 个数据源", userId, accessible.size());
                return PermissionFilterResult.allow(accessible, 0, null);
            }

            PermissionMatrix permissionMatrix = departmentPermissionService.buildPermissionMatrix(memberInfo.getDepartmentId());
            List<ExcelImportMetadata> excelDatasets = safeLoadExcelDatasets();
            Map<String, ExcelImportMetadata> excelIndexMap = buildExcelIndexMap(excelDatasets);
            Map<String, ExcelImportMetadata> excelTableMap = buildExcelTableMap(excelDatasets);

            if (requestedIds == null || requestedIds.isEmpty()) {
                List<String> accessibleIds = new ArrayList<>();
                List<FormDto> allForms = safeLoadForms();

                for (FormDto form : allForms) {
                    if (form == null || StringUtils.isBlank(form.getId())) {
                        continue;
                    }
                    String key = buildSourceKey("form", form.getId());
                    if (isSourceAccessible(permissionMatrix, key)) {
                        accessibleIds.add(form.getId());
                    }
                }

                for (ExcelImportMetadata excel : excelDatasets) {
                    if (excel == null || StringUtils.isAnyBlank(excel.getTableName(), excel.getIndexName())) {
                        continue;
                    }
                    String key = buildSourceKey("excel", excel.getTableName());
                    if (isSourceAccessible(permissionMatrix, key)) {
                        accessibleIds.add("excel:" + excel.getIndexName());
                    }
                }

                if (accessibleIds.isEmpty()) {
                    log.warn("用户 {} (部门{}) 没有可访问的数据源", userId, memberInfo.getDepartmentId());
                    return PermissionFilterResult.deny("当前部门暂无可访问的数据源，请联系管理员开通权限");
                }

                log.info("用户 {} (部门{}) 有权限访问的数据源: {}", userId, memberInfo.getDepartmentId(), accessibleIds.size());
                return PermissionFilterResult.allow(accessibleIds, 0, null);
            }

            List<String> filteredIds = new ArrayList<>();
            int rejectedCount = 0;
            for (String requestedId : requestedIds) {
                if (StringUtils.isBlank(requestedId)) {
                    continue;
                }

                boolean hasPermission;
                if (requestedId.startsWith("excel:")) {
                    String excelKey = requestedId.substring(6);
                    String excelSourceId = resolveExcelSourceId(excelKey, excelIndexMap, excelTableMap);
                    if (excelSourceId == null) {
                        log.warn("未找到Excel数据源: {}", requestedId);
                        rejectedCount++;
                        continue;
                    }
                    String sourceKey = buildSourceKey("excel", excelSourceId);
                    hasPermission = isSourceAccessible(permissionMatrix, sourceKey);
                } else {
                    ExcelImportMetadata excelMetadata = excelTableMap.get(requestedId.toUpperCase(Locale.ROOT));
                    if (excelMetadata != null) {
                        String sourceKey = buildSourceKey("excel", excelMetadata.getTableName());
                        hasPermission = isSourceAccessible(permissionMatrix, sourceKey);
                    } else {
                        String sourceKey = buildSourceKey("form", requestedId);
                        hasPermission = isSourceAccessible(permissionMatrix, sourceKey);
                    }
                }

                if (hasPermission) {
                    filteredIds.add(requestedId);
                } else {
                    rejectedCount++;
                    log.info("用户 {} (部门{}) 无权限访问数据源: {}", userId, memberInfo.getDepartmentId(), requestedId);
                }
            }

            if (filteredIds.isEmpty()) {
                return PermissionFilterResult.deny("当前部门无权限访问所选数据源");
            }

            log.info("权限过滤结果: 请求{}个数据源，允许访问{}个", requestedIds.size(), filteredIds.size());
            String message = rejectedCount > 0 ? String.format("有 %d 个数据源因部门权限被过滤", rejectedCount) : null;
            return PermissionFilterResult.allow(filteredIds, rejectedCount, message);

        } catch (Exception e) {
            log.error("权限过滤失败，userId: {}", userId, e);
            return PermissionFilterResult.deny("部门权限校验失败，请稍后重试");
        }
    }

    private List<String> collectAllDataSourceIds() {
        List<String> ids = new ArrayList<>();
        safeLoadForms().stream()
                .filter(Objects::nonNull)
                .filter(form -> StringUtils.isNotBlank(form.getId()))
                .forEach(form -> ids.add(form.getId()));

        safeLoadExcelDatasets().stream()
                .filter(Objects::nonNull)
                .filter(excel -> StringUtils.isNotBlank(excel.getIndexName()))
                .forEach(excel -> ids.add("excel:" + excel.getIndexName()));
        return ids;
    }

    private List<ExcelImportMetadata> safeLoadExcelDatasets() {
        try {
            List<ExcelImportMetadata> datasets = excelImportService.listImports();
            return datasets != null ? datasets : Collections.emptyList();
        } catch (Exception e) {
            log.error("加载Excel数据源失败", e);
            return Collections.emptyList();
        }
    }

    private List<FormDto> safeLoadForms() {
        try {
            List<FormDto> forms = formService.getAllForms();
            return forms != null ? forms : Collections.emptyList();
        } catch (Exception e) {
            log.error("加载表单数据源失败", e);
            return Collections.emptyList();
        }
    }

    private Map<String, ExcelImportMetadata> buildExcelIndexMap(List<ExcelImportMetadata> datasets) {
        if (datasets == null || datasets.isEmpty()) {
            return Collections.emptyMap();
        }
        return datasets.stream()
                .filter(Objects::nonNull)
                .filter(meta -> StringUtils.isNotBlank(meta.getIndexName()))
                .collect(Collectors.toMap(
                        meta -> meta.getIndexName().toLowerCase(Locale.ROOT),
                        meta -> meta,
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ));
    }

    private Map<String, ExcelImportMetadata> buildExcelTableMap(List<ExcelImportMetadata> datasets) {
        if (datasets == null || datasets.isEmpty()) {
            return Collections.emptyMap();
        }
        return datasets.stream()
                .filter(Objects::nonNull)
                .filter(meta -> StringUtils.isNotBlank(meta.getTableName()))
                .collect(Collectors.toMap(
                        meta -> meta.getTableName().toUpperCase(Locale.ROOT),
                        meta -> meta,
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ));
    }

    private String resolveExcelSourceId(String excelKey,
                                        Map<String, ExcelImportMetadata> excelIndexMap,
                                        Map<String, ExcelImportMetadata> excelTableMap) {
        if (StringUtils.isBlank(excelKey)) {
            return null;
        }
        ExcelImportMetadata byIndex = excelIndexMap.get(excelKey.toLowerCase(Locale.ROOT));
        if (byIndex != null && StringUtils.isNotBlank(byIndex.getTableName())) {
            return byIndex.getTableName();
        }
        ExcelImportMetadata byTable = excelTableMap.get(excelKey.toUpperCase(Locale.ROOT));
        if (byTable != null && StringUtils.isNotBlank(byTable.getTableName())) {
            return byTable.getTableName();
        }
        return null;
    }

    @Value("${app.search.detail-base-url:http://192.168.31.157/seeyon/rest/token/dataManage/openData}")
    private String detailBaseUrl;

    public String getDetailBaseUrl() {
        return detailBaseUrl;
    }

    public boolean checkIfAdmin(String userId) {
        return adminCheckService.checkIfAdmin(userId);
    }

    public AdminCheckService.AdminPermission checkUserPermission(String userId) {
        return adminCheckService.checkUserPermission(userId);
    }

    public com.esadmin.dto.SearchResponse searchData(com.esadmin.dto.SearchRequest request) {
        long startTime = System.currentTimeMillis();
        
        try {
            if (request.getQuery() == null || request.getQuery().trim().isEmpty()) {
                return createEmptyResponse();
            }

            PermissionFilterResult filterResult = filterDataSourcesByPermission(request.getFormIds(), request.getUserId());
            if (filterResult.isDenyAll()) {
                return createPermissionDeniedResponse(filterResult.getMessage());
            }

            List<String> filteredFormIds = filterResult.getAllowedIds();
            log.info("权限过滤后的数据源数量: {}", filteredFormIds.size());
            
            FormIndexSelection indexSelection = prepareIndexSelection(filteredFormIds);
            List<String> filterFormIds = indexSelection.filterFormIds();
            log.info("搜索使用的索引: {}", Arrays.toString(indexSelection.indices()));

            // 构建搜索请求
            SearchRequest searchRequest;
            if (indexSelection.indices().length == 0) {
                searchRequest = new SearchRequest();
            } else {
                searchRequest = new SearchRequest(indexSelection.indices());
            }
            searchRequest.indicesOptions(IndicesOptions.lenientExpandOpen());

            SearchSourceBuilder sourceBuilder = buildSearchSource(request, filterFormIds);
            searchRequest.source(sourceBuilder);

            // 先执行一次搜索获取所有结果，统计审核策略分布
            SearchRequest countRequest = new SearchRequest(searchRequest.indices());
            countRequest.indicesOptions(searchRequest.indicesOptions());
            SearchSourceBuilder countSourceBuilder = buildSearchSource(request, filterFormIds);
            countSourceBuilder.size(10000); // 获取更多结果用于统计
            countSourceBuilder.from(0);
            countRequest.source(countSourceBuilder);
            
            SearchResponse countResponse = esClient.search(countRequest, RequestOptions.DEFAULT);
            
            // 统计审核策略分布
            ReviewFilterStats filterStats = calculateReviewFilterStats(countResponse, request.getUserId(), request.getQuery());
            
            // 执行原始搜索（分页）
            SearchResponse response = esClient.search(searchRequest, RequestOptions.DEFAULT);
            
            // 转换结果并应用审核策略过滤，传入统计信息
            com.esadmin.dto.SearchResponse finalResponse = convertSearchResponseWithReviewFilter(response, startTime, request.getUserId(), request.getQuery(), filterStats);

            if (filterResult.getFilteredOutCount() != null && filterResult.getFilteredOutCount() > 0) {
                finalResponse.setFilteredCount(filterResult.getFilteredOutCount());
                String message = StringUtils.defaultIfBlank(filterResult.getMessage(),
                        String.format("有 %d 个数据源因部门权限被过滤", filterResult.getFilteredOutCount()));
                finalResponse.setFilterMessage(message);
            }

            return finalResponse;

        } catch (Exception e) {
            log.error("搜索失败", e);
            return createErrorResponse(e.getMessage(), startTime);
        }
    }

    public List<Map<String, Object>> getSearchFormStats(com.esadmin.dto.SearchRequest request) {
        try {
            if (request.getQuery() == null || request.getQuery().trim().isEmpty()) {
                return getFormDocumentStatsWithPermission(request.getUserId());
            }

            PermissionFilterResult filterResult = filterDataSourcesByPermission(request.getFormIds(), request.getUserId());
            if (filterResult.isDenyAll()) {
                return new ArrayList<>();
            }

            List<String> filteredFormIds = filterResult.getAllowedIds();
            
            FormIndexSelection indexSelection = prepareIndexSelection(filteredFormIds);
            List<String> filterFormIds = indexSelection.filterFormIds();

            // 构建聚合查询来统计每个数据源的结果数量
            SearchRequest searchRequest;
            if (indexSelection.indices().length == 0) {
                searchRequest = new SearchRequest();
            } else {
                searchRequest = new SearchRequest(indexSelection.indices());
            }
            searchRequest.indicesOptions(IndicesOptions.lenientExpandOpen());

            // 强制启用聚合统计
            request.setIncludeStats(true);
            SearchSourceBuilder sourceBuilder = buildSearchSource(request, filterFormIds);
            sourceBuilder.size(0); // 不需要返回具体文档
            
            // 设置更短的超时时间，优先保证响应速度
            sourceBuilder.timeout(new TimeValue(3, TimeUnit.SECONDS));

            searchRequest.source(sourceBuilder);
            SearchResponse response = esClient.search(searchRequest, RequestOptions.DEFAULT);

            return buildSearchFormStats(response);

        } catch (Exception e) {
            log.warn("获取搜索表单统计超时或失败: {}", e.getMessage());
            // 快速降级：返回空列表，避免阻塞用户体验
            return new ArrayList<>();
        }
    }

    private List<Map<String, Object>> buildSearchFormStats(SearchResponse response) {
        Map<String, Long> formCounts = new HashMap<>();
        Map<String, Long> excelCounts = new HashMap<>();

        // 解析表单聚合结果
        if (response.getAggregations() != null) {
            org.elasticsearch.search.aggregations.bucket.terms.Terms formAgg = 
                response.getAggregations().get("form_stats");
            if (formAgg != null) {
                for (org.elasticsearch.search.aggregations.bucket.terms.Terms.Bucket bucket : formAgg.getBuckets()) {
                    String formId = bucket.getKeyAsString();
                    if (formId != null && !formId.isEmpty()) {
                        formCounts.put(formId, bucket.getDocCount());
                    }
                }
            }

            org.elasticsearch.search.aggregations.bucket.terms.Terms excelAgg = 
                response.getAggregations().get("excel_stats");
            if (excelAgg != null) {
                for (org.elasticsearch.search.aggregations.bucket.terms.Terms.Bucket bucket : excelAgg.getBuckets()) {
                    String tableName = bucket.getKeyAsString();
                    if (tableName != null && !tableName.isEmpty()) {
                        excelCounts.put(tableName, bucket.getDocCount());
                    }
                }
            }
        }

        // 构建结果
        List<Map<String, Object>> result = new ArrayList<>();

        // 添加表单结果
        List<FormDto> forms = formService.getAllForms();
        if (forms != null) {
            for (FormDto form : forms) {
                if (form == null || StringUtils.isBlank(form.getId())) {
                    continue;
                }
                Long count = formCounts.get(form.getId());
                if (count != null && count > 0) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", form.getId());
                    item.put("type", "form");
                    item.put("name", StringUtils.defaultIfBlank(form.getName(), "未命名表单"));
                    item.put("count", count);
                    item.put("index", "form_" + form.getId());
                    result.add(item);
                }
            }
        }

        // 添加Excel结果
        List<ExcelImportMetadata> excelDatasets = excelImportService.listImports();
        if (excelDatasets != null) {
            for (ExcelImportMetadata dataset : excelDatasets) {
                if (dataset == null || StringUtils.isAnyBlank(dataset.getTableName(), dataset.getDisplayName())) {
                    continue;
                }
                Long count = excelCounts.get(dataset.getTableName());
                if (count != null && count > 0) {
                    Map<String, Object> item = new HashMap<>();
                    String datasetId = "excel:" + dataset.getIndexName();
                    item.put("id", datasetId);
                    item.put("type", "excel");
                    item.put("name", dataset.getDisplayName());
                    item.put("count", count);
                    item.put("index", dataset.getIndexName());
                    item.put("table", dataset.getTableName());
                    result.add(item);
                }
            }
        }

        // 按结果数量降序排序
        result.sort((a, b) -> {
            long countA = ((Number) a.getOrDefault("count", 0L)).longValue();
            long countB = ((Number) b.getOrDefault("count", 0L)).longValue();
            return Long.compare(countB, countA);
        });

        return result;
    }
    
    private List<Map<String, Object>> extractFormStatsFromAggregations(org.elasticsearch.search.aggregations.Aggregations aggregations) {
        Map<String, Long> formCounts = new HashMap<>();
        Map<String, Long> excelCounts = new HashMap<>();

        // 解析表单聚合结果
        org.elasticsearch.search.aggregations.bucket.terms.Terms formAgg = aggregations.get("form_stats");
        if (formAgg != null) {
            for (org.elasticsearch.search.aggregations.bucket.terms.Terms.Bucket bucket : formAgg.getBuckets()) {
                String formId = bucket.getKeyAsString();
                if (formId != null && !formId.isEmpty()) {
                    formCounts.put(formId, bucket.getDocCount());
                }
            }
        }

        org.elasticsearch.search.aggregations.bucket.terms.Terms excelAgg = aggregations.get("excel_stats");
        if (excelAgg != null) {
            for (org.elasticsearch.search.aggregations.bucket.terms.Terms.Bucket bucket : excelAgg.getBuckets()) {
                String tableName = bucket.getKeyAsString();
                if (tableName != null && !tableName.isEmpty()) {
                    excelCounts.put(tableName, bucket.getDocCount());
                }
            }
        }

        // 构建结果（只返回有搜索结果的数据源）
        List<Map<String, Object>> result = new ArrayList<>();

        // 添加有结果的表单
        if (!formCounts.isEmpty()) {
            List<FormDto> allForms = formService.getAllForms();
            if (allForms != null) {
                for (FormDto form : allForms) {
                    if (form != null && StringUtils.isNotBlank(form.getId())) {
                        Long count = formCounts.get(form.getId());
                        if (count != null && count > 0) {
                            Map<String, Object> item = new HashMap<>();
                            item.put("id", form.getId());
                            item.put("type", "form");
                            item.put("name", StringUtils.defaultIfBlank(form.getName(), "未命名表单"));
                            item.put("count", count);
                            item.put("index", "form_" + form.getId());
                            result.add(item);
                        }
                    }
                }
            }
        }

        // 添加有结果的Excel数据
        if (!excelCounts.isEmpty()) {
            List<ExcelImportMetadata> excelDatasets = excelImportService.listImports();
            if (excelDatasets != null) {
                for (ExcelImportMetadata dataset : excelDatasets) {
                    if (dataset != null && StringUtils.isNotBlank(dataset.getTableName())) {
                        Long count = excelCounts.get(dataset.getTableName());
                        if (count != null && count > 0) {
                            Map<String, Object> item = new HashMap<>();
                            String datasetId = "excel:" + dataset.getIndexName();
                            item.put("id", datasetId);
                            item.put("type", "excel");
                            item.put("name", dataset.getDisplayName());
                            item.put("count", count);
                            item.put("index", dataset.getIndexName());
                            item.put("table", dataset.getTableName());
                            result.add(item);
                        }
                    }
                }
            }
        }

        // 按结果数量降序排序
        result.sort((a, b) -> {
            long countA = ((Number) a.getOrDefault("count", 0L)).longValue();
            long countB = ((Number) b.getOrDefault("count", 0L)).longValue();
            return Long.compare(countB, countA);
        });

        return result;
    }

    private FormIndexSelection prepareIndexSelection(List<String> formIds) {
        final int maxExplicitIndices = 80;

        if (formIds == null || formIds.isEmpty()) {
            return FormIndexSelection.allIndices();
        }

        LinkedHashSet<String> normalizedIds = formIds.stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (normalizedIds.isEmpty()) {
            return FormIndexSelection.allIndices();
        }

        List<String> resolvedIndices = normalizedIds.stream()
                .map(this::resolveIndexName)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());

        if (resolvedIndices.isEmpty()) {
            return FormIndexSelection.allIndices();
        }

        if (resolvedIndices.size() > maxExplicitIndices) {
            return FormIndexSelection.filteredAll(new ArrayList<>(normalizedIds));
        }

        return FormIndexSelection.explicit(resolvedIndices.toArray(new String[0]));
    }

    /**
     * 获取有权限访问的数据源文档统计
     */
    public List<Map<String, Object>> getFormDocumentStatsWithPermission(String userIdStr) {
        try {
            PermissionFilterResult filterResult = filterDataSourcesByPermission(null, userIdStr);
            if (filterResult.isDenyAll()) {
                log.warn("用户 {} 权限被拒绝，原因: {}", userIdStr, filterResult.getMessage());
                throw new IllegalStateException(filterResult.getMessage());
            }

            List<String> accessibleIds = filterResult.getAllowedIds();
            
            List<FormDto> forms = formService.getAllForms();
            List<ExcelImportMetadata> excelDatasets = excelImportService.listImports();

            Map<String, String> datasetIndexMap = new LinkedHashMap<>();
            
            // 只添加有权限访问的表单
            if (forms != null) {
                forms.stream()
                        .filter(Objects::nonNull)
                        .filter(form -> StringUtils.isNotBlank(form.getId()))
                        .filter(form -> accessibleIds.contains(form.getId())) // 权限过滤
                        .forEach(form -> datasetIndexMap.put(form.getId(), "form_" + form.getId()));
            }
            
            // 只添加有权限访问的Excel数据源
            if (excelDatasets != null) {
                excelDatasets.stream()
                        .filter(Objects::nonNull)
                        .filter(dataset -> StringUtils.isNotBlank(dataset.getIndexName()))
                        .filter(dataset -> accessibleIds.contains("excel:" + dataset.getIndexName())) // 权限过滤
                        .forEach(dataset -> datasetIndexMap.put("excel:" + dataset.getIndexName(), dataset.getIndexName()));
            }

            Map<String, Long> docCounts = fetchDocCounts(datasetIndexMap);

            List<Map<String, Object>> result = new ArrayList<>();
            
            // 处理表单结果
            if (forms != null) {
                for (FormDto form : forms) {
                    if (form == null || StringUtils.isBlank(form.getId()) || 
                        !accessibleIds.contains(form.getId())) { // 权限检查
                        continue;
                    }

                    Long count = docCounts.get(form.getId());
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", form.getId());
                    item.put("type", "form");
                    item.put("name", StringUtils.defaultIfBlank(form.getName(), "未命名表单"));
                    item.put("count", count != null ? count : 0L);
                    item.put("index", "form_" + form.getId());
                    result.add(item);
                }
            }

            // 处理Excel结果
            if (excelDatasets != null) {
                for (ExcelImportMetadata dataset : excelDatasets) {
                    if (dataset == null || StringUtils.isBlank(dataset.getIndexName()) ||
                        !accessibleIds.contains("excel:" + dataset.getIndexName())) { // 权限检查
                        continue;
                    }

                    Long count = docCounts.get("excel:" + dataset.getIndexName());
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", "excel:" + dataset.getIndexName());
                    item.put("type", "excel");
                    item.put("name", StringUtils.defaultIfBlank(dataset.getDisplayName(), dataset.getIndexName()));
                    item.put("count", count != null ? count : 0L);
                    item.put("index", dataset.getIndexName());
                    result.add(item);
                }
            }

            // 按文档数量排序
            result.sort((a, b) -> {
                Long countA = (Long) a.get("count");
                Long countB = (Long) b.get("count");
                return Long.compare(countB != null ? countB : 0L, countA != null ? countA : 0L);
            });

            log.info("权限过滤后返回 {} 个数据源统计", result.size());
            return result;

        } catch (Exception e) {
            log.error("获取权限过滤后的表单文档统计失败", e);
            return new ArrayList<>();
        }
    }

    public List<Map<String, Object>> getFormDocumentStats() {
        try {
            List<FormDto> forms = formService.getAllForms();
            List<ExcelImportMetadata> excelDatasets = excelImportService.listImports();

            Map<String, String> datasetIndexMap = new LinkedHashMap<>();
            if (forms != null) {
                forms.stream()
                        .filter(Objects::nonNull)
                        .filter(form -> StringUtils.isNotBlank(form.getId()))
                        .forEach(form -> datasetIndexMap.put(form.getId(), "form_" + form.getId()));
            }
            if (excelDatasets != null) {
                excelDatasets.stream()
                        .filter(Objects::nonNull)
                        .filter(dataset -> StringUtils.isNotBlank(dataset.getIndexName()))
                        .forEach(dataset -> datasetIndexMap.put("excel:" + dataset.getIndexName(), dataset.getIndexName()));
            }

            Map<String, Long> docCounts = fetchDocCounts(datasetIndexMap);

            List<Map<String, Object>> result = new ArrayList<>();
            if (forms != null) {
                for (FormDto form : forms) {
                    if (form == null || StringUtils.isBlank(form.getId())) {
                        continue;
                    }
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", form.getId());
                    item.put("type", "form");
                    item.put("name", StringUtils.defaultIfBlank(form.getName(), "未命名表单"));
                    item.put("count", docCounts.getOrDefault(form.getId(), 0L));
                    item.put("index", "form_" + form.getId());
                    result.add(item);
                }
            }

            if (excelDatasets != null) {
                for (ExcelImportMetadata dataset : excelDatasets) {
                    if (dataset == null || StringUtils.isAnyBlank(dataset.getIndexName(), dataset.getDisplayName())) {
                        continue;
                    }
                    Map<String, Object> item = new HashMap<>();
                    String datasetId = "excel:" + dataset.getIndexName();
                    item.put("id", datasetId);
                    item.put("type", "excel");
                    item.put("name", dataset.getDisplayName());
                    item.put("count", docCounts.getOrDefault(datasetId, (long) dataset.getRowCount()));
                    item.put("index", dataset.getIndexName());
                    item.put("table", dataset.getTableName());
                    result.add(item);
                }
            }

            result.sort((a, b) -> {
                long countA = ((Number) a.getOrDefault("count", 0L)).longValue();
                long countB = ((Number) b.getOrDefault("count", 0L)).longValue();
                int compare = Long.compare(countB, countA);
                if (compare != 0) {
                    return compare;
                }
                String typeA = Optional.ofNullable(a.get("type")).map(Object::toString).orElse("form");
                String typeB = Optional.ofNullable(b.get("type")).map(Object::toString).orElse("form");
                compare = typeA.compareTo(typeB);
                if (compare != 0) {
                    return compare;
                }
                String nameA = Optional.ofNullable(a.get("name")).map(Object::toString).orElse("");
                String nameB = Optional.ofNullable(b.get("name")).map(Object::toString).orElse("");
                return nameA.compareTo(nameB);
            });

            return result;

        } catch (Exception e) {
            log.error("获取表单文档统计失败", e);
            return Collections.emptyList();
        }
    }

    private Map<String, Long> fetchDocCounts(Map<String, String> datasetIndexMap) {
        Map<String, Long> counts = new HashMap<>();
        if (datasetIndexMap == null || datasetIndexMap.isEmpty()) {
            return counts;
        }

        datasetIndexMap.forEach((datasetId, indexName) -> {
            if (StringUtils.isBlank(indexName)) {
                return;
            }
            try {
                CountRequest countRequest = new CountRequest(indexName);
                countRequest.query(QueryBuilders.matchAllQuery());
                countRequest.indicesOptions(IndicesOptions.lenientExpandOpen());
                CountResponse countResponse = esClient.count(countRequest, RequestOptions.DEFAULT);
                counts.put(datasetId, countResponse.getCount());
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("统计索引文档数量失败: index={}, error={}", indexName, e.getMessage());
                }
                counts.putIfAbsent(datasetId, 0L);
            }
        });

        return counts;
    }

    private String resolveIndexName(String datasetId) {
        if (StringUtils.isBlank(datasetId)) {
            return null;
        }
        if (datasetId.startsWith("excel:")) {
            String indexName = datasetId.substring("excel:".length());
            return StringUtils.isNotBlank(indexName) ? indexName : null;
        }
        return "form_" + datasetId;
    }

    private SearchSourceBuilder buildSearchSource(com.esadmin.dto.SearchRequest request, List<String> filterFormIds) {
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();

        // 构建查询
        BoolQueryBuilder mainQuery = QueryBuilders.boolQuery();
        
        String queryText = request.getQuery().trim();
        Boolean exactSearch = request.getExactSearch() != null && request.getExactSearch();
        
        if (exactSearch) {
            // 精确搜索：只使用精确匹配和短语匹配
            mainQuery.should(QueryBuilders.multiMatchQuery(queryText)
                .fields(Map.of("*", 1.0f))
                .type(MultiMatchQueryBuilder.Type.PHRASE)
                .boost(2.0f));
                
            // 添加term查询以支持完全匹配
            mainQuery.should(QueryBuilders.multiMatchQuery(queryText)
                .fields(Map.of("*", 1.0f))
                .type(MultiMatchQueryBuilder.Type.BEST_FIELDS)
                .operator(org.elasticsearch.index.query.Operator.AND)
                .boost(3.0f));
                
            mainQuery.minimumShouldMatch(1);
        } else {
            // 模糊搜索：使用原有的复杂搜索逻辑
            // 处理复合查询（包含+号或空格分隔的多个关键词）
            String[] keywords = parseKeywords(queryText);
        
            if (keywords.length > 1) {
                // 多关键词查询：同时匹配多个词的文档得分更高
                
                // 1. 完整短语匹配（最高权重）
                mainQuery.should(QueryBuilders.multiMatchQuery(queryText)
                    .fields(Map.of("*", 1.0f))
                    .type(MultiMatchQueryBuilder.Type.PHRASE)
                    .boost(5.0f));
                
                // 2. 所有关键词都匹配（高权重）
                BoolQueryBuilder allKeywordsQuery = QueryBuilders.boolQuery();
                for (String keyword : keywords) {
                    allKeywordsQuery.must(QueryBuilders.multiMatchQuery(keyword.trim())
                        .fields(Map.of("*", 1.0f))
                        .type(MultiMatchQueryBuilder.Type.BEST_FIELDS));
                }
                mainQuery.should(allKeywordsQuery.boost(4.0f));
                
                // 3. 大部分关键词匹配（中等权重）
                BoolQueryBuilder mostKeywordsQuery = QueryBuilders.boolQuery();
                for (String keyword : keywords) {
                    mostKeywordsQuery.should(QueryBuilders.multiMatchQuery(keyword.trim())
                        .fields(Map.of("*", 1.0f))
                        .type(MultiMatchQueryBuilder.Type.BEST_FIELDS));
                }
                int minMatch = Math.max(1, (int) Math.ceil(keywords.length * 0.7)); // 至少70%的词匹配
                mostKeywordsQuery.minimumShouldMatch(minMatch);
                mainQuery.should(mostKeywordsQuery.boost(2.5f));
                
                // 4. 任意关键词匹配（基础权重）
                for (String keyword : keywords) {
                    mainQuery.should(QueryBuilders.multiMatchQuery(keyword.trim())
                        .fields(Map.of("*", 1.0f))
                        .type(MultiMatchQueryBuilder.Type.BEST_FIELDS)
                        .boost(1.0f));
                    
                    // 前缀匹配
                    mainQuery.should(QueryBuilders.multiMatchQuery(keyword.trim())
                        .fields(Map.of("*", 1.0f))
                        .type(MultiMatchQueryBuilder.Type.PHRASE_PREFIX)
                        .boost(1.5f));
                }
                
            } else {
                // 单关键词查询：使用原有逻辑
                
                // 精确匹配得分更高
                mainQuery.should(QueryBuilders.multiMatchQuery(queryText)
                    .fields(Map.of("*", 1.0f))
                    .type(MultiMatchQueryBuilder.Type.PHRASE)
                    .boost(3.0f));
                
                // 前缀匹配
                mainQuery.should(QueryBuilders.multiMatchQuery(queryText)
                    .fields(Map.of("*", 1.0f))
                    .type(MultiMatchQueryBuilder.Type.PHRASE_PREFIX)
                    .boost(2.0f));
                
                // 模糊匹配
                mainQuery.should(QueryBuilders.multiMatchQuery(queryText)
                    .fields(Map.of("*", 1.0f))
                    .type(MultiMatchQueryBuilder.Type.BEST_FIELDS)
                    .fuzziness("AUTO")
                    .boost(1.0f));
            }
            
            mainQuery.minimumShouldMatch(1);
        }

        if (filterFormIds != null && !filterFormIds.isEmpty()) {
            mainQuery.filter(QueryBuilders.termsQuery("form_id", filterFormIds));
        }

        sourceBuilder.query(mainQuery);

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

        // 排除不必要的字段
        sourceBuilder.fetchSource(null, new String[]{"sync_time"});
        
        // 添加聚合统计（如果需要）
        if (request.getIncludeStats() != null && request.getIncludeStats()) {
            // 使用高效的聚合配置
            sourceBuilder.aggregation(
                org.elasticsearch.search.aggregations.AggregationBuilders.terms("form_stats")
                    .field("form_id")
                    .size(20) // 进一步减少聚合数量
                    .shardSize(30) // 限制分片级别的聚合大小
            );
            sourceBuilder.aggregation(
                org.elasticsearch.search.aggregations.AggregationBuilders.terms("excel_stats")
                    .field("table_name")
                    .size(20) // 进一步减少聚合数量  
                    .shardSize(30) // 限制分片级别的聚合大小
            );
        }
        
        // 超时设置
        sourceBuilder.timeout(new TimeValue(timeout, TimeUnit.SECONDS));

        return sourceBuilder;
    }
    
    // 审核过滤统计信息类
    private static class ReviewFilterStats {
        private final int totalViewFirstCount;  // 先看后审记录数
        private final int totalReviewFirstCount; // 先审后看记录数
        private final boolean isKeywordUnderReview; // 关键字是否在审核中
        private final String reviewResult; // 审核结果
        
        public ReviewFilterStats(int totalViewFirstCount, int totalReviewFirstCount, 
                                boolean isKeywordUnderReview, String reviewResult) {
            this.totalViewFirstCount = totalViewFirstCount;
            this.totalReviewFirstCount = totalReviewFirstCount;
            this.isKeywordUnderReview = isKeywordUnderReview;
            this.reviewResult = reviewResult;
        }
        
        public int getTotalViewFirstCount() { return totalViewFirstCount; }
        public int getTotalReviewFirstCount() { return totalReviewFirstCount; }
        public boolean isKeywordUnderReview() { return isKeywordUnderReview; }
        public String getReviewResult() { return reviewResult; }
    }
    
    private ReviewFilterStats calculateReviewFilterStats(SearchResponse countResponse, String userId, String searchQuery) {
        // 检查关键字审核状态
        boolean isKeywordUnderReview = false;
        String reviewResult = "";
        try {
            if (StringUtils.isNotBlank(searchQuery) && StringUtils.isNotBlank(userId)) {
                KeyReviewService.ReviewDecision decision = keyReviewService.reviewKeyword(userId, searchQuery);
                reviewResult = decision.getMessage();
                isKeywordUnderReview = "审核中".equals(reviewResult);
            }
        } catch (Exception e) {
            log.warn("关键字审核检查失败", e);
        }
        
        // 统计审核策略分布
        Map<String, String> reviewPolicyCache = new HashMap<>();
        int viewFirstCount = 0;
        int reviewFirstCount = 0;
        
        for (SearchHit hit : countResponse.getHits().getHits()) {
            Map<String, Object> source = hit.getSourceAsMap();
            String sourceType = StringUtils.defaultIfBlank((String) source.get("source_type"), "form");
            String formId = (String) source.get("form_id");
            String tableName = (String) source.get("table_name");
            
            String dataSourceId;
            if ("excel".equalsIgnoreCase(sourceType)) {
                dataSourceId = StringUtils.defaultIfBlank(tableName, "excel_dataset");
            } else {
                dataSourceId = StringUtils.defaultIfBlank(formId, "unknown_form");
            }
            
            // 获取审核策略
            String reviewMode = reviewPolicyCache.computeIfAbsent(
                sourceType + ":" + dataSourceId,
                key -> {
                    try {
                        return reviewPolicyService.getReviewMode(sourceType, dataSourceId);
                    } catch (Exception e) {
                        log.warn("获取审核策略失败: {}", key, e);
                        return "view_first";
                    }
                }
            );
            
            if ("review_first".equals(reviewMode)) {
                reviewFirstCount++;
            } else {
                viewFirstCount++;
            }
        }
        
        log.info("审核策略统计完成: 先看后审={}, 先审后看={}, 总数={}, 关键字审核状态={}", 
            viewFirstCount, reviewFirstCount, countResponse.getHits().getHits().length, reviewResult);
            
        return new ReviewFilterStats(viewFirstCount, reviewFirstCount, isKeywordUnderReview, reviewResult);
    }

    private com.esadmin.dto.SearchResponse convertSearchResponseWithReviewFilter(org.elasticsearch.action.search.SearchResponse response,
                                                                                  long startTime,
                                                                                  String userId,
                                                                                  String searchQuery,
                                                                                  ReviewFilterStats filterStats) {
        com.esadmin.dto.SearchResponse result = new com.esadmin.dto.SearchResponse();
        
        // 使用预计算的关键字审核状态
        boolean isKeywordUnderReview = filterStats.isKeywordUnderReview();
        String reviewResult = filterStats.getReviewResult();
        result.setReviewResult(reviewResult);
        
        List<com.esadmin.dto.SearchResponse.SearchHit> hits = new ArrayList<>();
        Map<String, String> formCache = new HashMap<>();
        Map<String, String> excelCache = new HashMap<>();

        // 预加载审核策略，避免重复查询
        Map<String, String> reviewPolicyCache = new HashMap<>();

        for (SearchHit hit : response.getHits().getHits()) {
            Map<String, Object> source = hit.getSourceAsMap();
            String sourceType = StringUtils.defaultIfBlank((String) source.get("source_type"), "form");
            String tableName = (String) source.get("table_name");
            String recordId = source.get("record_id") != null ? String.valueOf(source.get("record_id")) : "";

            String formId = (String) source.get("form_id");
            String formName;
            String jumpUrl = null;
            String dataSourceId;

            if ("excel".equalsIgnoreCase(sourceType)) {
                String cacheKey = StringUtils.defaultIfBlank(tableName, "excel_dataset");
                formName = excelCache.computeIfAbsent(cacheKey, key -> {
                    String display = excelImportService.getDisplayName(key);
                    return StringUtils.isNotBlank(display) ? display : key;
                });
                formId = "excel:" + cacheKey;
                dataSourceId = cacheKey;
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
                formId = cacheKey;
                jumpUrl = buildOpenDataUrl(formId, recordId, userId);
                dataSourceId = formId;
            }

            // 检查审核策略
            String reviewMode = reviewPolicyCache.computeIfAbsent(
                sourceType + ":" + dataSourceId,
                key -> {
                    try {
                        return reviewPolicyService.getReviewMode(sourceType, dataSourceId);
                    } catch (Exception e) {
                        log.warn("获取审核策略失败: {}", key, e);
                        return "view_first"; // 默认先看后审
                    }
                }
            );

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

            // 根据审核策略决定是否包含在结果中
            if ("review_first".equals(reviewMode)) {
                // 先审后看的记录被过滤掉，不添加到结果中
                // 过滤的记录数量统计由全局统计提供
            } else {
                // 先看后审的记录正常显示
                hits.add(searchHit);
            }
        }

        result.setHits(hits);
        result.setTotal(response.getHits().getTotalHits().value);
        result.setMaxScore(response.getHits().getMaxScore());
        result.setElapsedTime((System.currentTimeMillis() - startTime) / 1000.0);
        result.setTook(response.getTook().getMillis());
        
        // 使用全局过滤统计信息
        if (filterStats.getTotalReviewFirstCount() > 0) {
            result.setFilteredCount(filterStats.getTotalReviewFirstCount());
            String filterReason;
            if (isKeywordUnderReview) {
                filterReason = "有 " + filterStats.getTotalReviewFirstCount() + " 条记录（先审后看）需要等关键字审核通过后才能查看";
            } else {
                filterReason = "有 " + filterStats.getTotalReviewFirstCount() + " 条记录因审核策略（先审后看）被过滤，未在结果中显示";
            }
            result.setFilterMessage(filterReason);
            log.info("搜索过滤统计 - 显示: {}, 全局过滤: {}, 当前页结果: {}, 关键字审核状态: {}", 
                hits.size(), filterStats.getTotalReviewFirstCount(), response.getHits().getHits().length, reviewResult);
        }

        return result;
    }

    private com.esadmin.dto.SearchResponse convertSearchResponse(org.elasticsearch.action.search.SearchResponse response,
                                                                 long startTime,
                                                                 String userId) {
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
                formId = cacheKey;
                jumpUrl = buildOpenDataUrl(formId, recordId, userId);
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

    private String buildOpenDataUrl(String formId, String recordId, String userId) {
        if (StringUtils.isAnyBlank(detailBaseUrl, formId, recordId, userId)) {
            return null;
        }
        String base = detailBaseUrl.endsWith("/") ? detailBaseUrl.substring(0, detailBaseUrl.length() - 1) : detailBaseUrl;
        return String.format("%s/%s/%s/%s", base, formId, recordId, userId);
    }

    private static final class PermissionFilterResult {
        private final List<String> allowedIds;
        private final boolean denyAll;
        private final String message;
        private final Integer filteredOutCount;

        private PermissionFilterResult(List<String> allowedIds, boolean denyAll, String message, Integer filteredOutCount) {
            this.allowedIds = allowedIds != null ? allowedIds : Collections.emptyList();
            this.denyAll = denyAll;
            this.message = message;
            this.filteredOutCount = filteredOutCount;
        }

        static PermissionFilterResult allow(List<String> allowedIds, int filteredOutCount, String message) {
            Integer count = filteredOutCount > 0 ? filteredOutCount : null;
            return new PermissionFilterResult(allowedIds, false, message, count);
        }

        static PermissionFilterResult deny(String message) {
            return new PermissionFilterResult(Collections.emptyList(), true, message, null);
        }

        List<String> getAllowedIds() {
            return allowedIds;
        }

        boolean isDenyAll() {
            return denyAll;
        }

        String getMessage() {
            return message;
        }

        Integer getFilteredOutCount() {
            return filteredOutCount;
        }
    }

    private static final class FormIndexSelection {
        private final String[] indices;
        private final List<String> filterFormIds;

        private FormIndexSelection(String[] indices, List<String> filterFormIds) {
            this.indices = indices;
            this.filterFormIds = filterFormIds;
        }

        static FormIndexSelection allIndices() {
            return new FormIndexSelection(new String[]{"form_*", "excel_*"}, Collections.emptyList());
        }

        static FormIndexSelection filteredAll(List<String> filterFormIds) {
            return new FormIndexSelection(new String[]{"form_*", "excel_*"}, filterFormIds);
        }

        static FormIndexSelection explicit(String[] indices) {
            return new FormIndexSelection(indices, Collections.emptyList());
        }

        String[] indices() {
            return indices;
        }

        List<String> filterFormIds() {
            return filterFormIds;
        }
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

    private String[] parseKeywords(String queryText) {
        if (StringUtils.isBlank(queryText)) {
            return new String[0];
        }
        
        // 支持+号和空格分隔的关键词
        String[] keywords = queryText.trim()
                .split("[+\\s]+")  // 按+号或空格分割
                .clone();
        
        // 过滤空字符串并去重
        return Arrays.stream(keywords)
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .distinct()
                .toArray(String[]::new);
    }

    private com.esadmin.dto.SearchResponse createEmptyResponse() {
        com.esadmin.dto.SearchResponse response = new com.esadmin.dto.SearchResponse();
        response.setHits(new ArrayList<>());
        response.setTotal(0);
        return response;
    }

    private com.esadmin.dto.SearchResponse createPermissionDeniedResponse(String message) {
        com.esadmin.dto.SearchResponse response = createEmptyResponse();
        response.setFilterMessage(StringUtils.defaultIfBlank(message, "当前部门暂无可访问的数据源，请联系管理员配置权限"));
        response.setFilteredCount(0);
        return response;
    }

    private boolean isSourceAccessible(PermissionMatrix matrix, String sourceKey) {
        if (matrix == null) {
            return true;
        }
        if (!matrix.isRestricted(sourceKey)) {
            return true;
        }
        return matrix.isAllowed(sourceKey);
    }

    private String buildSourceKey(String sourceType, String sourceId) {
        return (sourceType != null ? sourceType : "") + ":" + (sourceId != null ? sourceId : "");
    }

    private com.esadmin.dto.SearchResponse createErrorResponse(String error, long startTime) {
        com.esadmin.dto.SearchResponse response = createEmptyResponse();
        response.setError(error);
        response.setElapsedTime((System.currentTimeMillis() - startTime) / 1000.0);
        return response;
    }
}
