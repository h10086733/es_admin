package com.esadmin.service;

import com.esadmin.dto.FormDto;
import com.esadmin.dto.SyncResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.rest.RestStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.elasticsearch.core.TimeValue;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.sql.Date;
import java.io.IOException;
import java.util.Locale;

@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    
    // 预编译正则表达式，提升性能
    private static final Pattern DATE_TIME_WITH_MILLIS = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d+");
    private static final Pattern DATE_TIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    private static final Pattern DATE_ONLY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    
    // 预创建DateTimeFormatter，避免重复创建
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
    private final RestHighLevelClient esClient;
    private final FormService formService;
    private final MemberService memberService;
    private final ObjectMapper objectMapper;

    private final Map<String, Boolean> indexExistenceCache = new ConcurrentHashMap<>();
    private final Map<String, Object> indexLocks = new ConcurrentHashMap<>();


    public SyncService(RestHighLevelClient esClient, FormService formService, 
                      MemberService memberService, ObjectMapper objectMapper) {
        this.esClient = esClient;
        this.formService = formService;
        this.memberService = memberService;
        this.objectMapper = objectMapper;
    }

    @Value("${app.sync.batch-size:2000}")
    private int batchSize;

    @Value("${app.sync.db-batch-size:1000}")
    private int dbBatchSize;

    @Value("${app.sync.es-max-retries:5}")
    private int esMaxRetries;

    @Value("${app.sync.es-retry-initial-backoff-millis:200}")
    private long esRetryInitialBackoffMillis;

    @Value("${app.sync.es-retry-max-backoff-millis:5000}")
    private long esRetryMaxBackoffMillis;

    @Value("${app.sync.es-bulk-timeout-seconds:60}")
    private int bulkTimeoutSeconds;

    @Value("${app.sync.es-bulk-max-actions:4000}")
    private int esBulkMaxActions;

    public SyncResult syncFormData(String formId, boolean fullSync) {
        long startTime = System.currentTimeMillis();
        
        try {
            log.info("开始同步表单数据: formId={}, fullSync={}", formId, fullSync);
            
            // 获取表单配置
            FormDto form = formService.getFormById(formId);
            if (form == null) {
                return createFailureResult("表单不存在", formId, null);
            }

            String tableName = formService.getFormTableName(formId);
            if (tableName == null) {
                return createFailureResult("无法获取表名", formId, form.getName());
            }

            // 检查表是否存在
            if (!formService.checkTableExists(tableName)) {
                return createFailureResult("数据表不存在: " + tableName, formId, form.getName());
            }

            // 确保表的ID字段有索引（提升排序性能）
            if (!formService.ensureTableIdIndex(tableName)) {
                log.warn("为表 {} 创建ID索引失败，可能影响同步性能", tableName);
            }

            // 尝试创建增量同步索引，如果失败则使用无索引方案
            boolean hasIncrementalIndexes = formService.ensureIncrementalSyncIndexes(tableName);
            if (!hasIncrementalIndexes) {
                log.warn("为表 {} 创建增量同步索引失败，将使用无索引增量同步方案", tableName);
            }

            // 获取字段信息
            List<Map<String, Object>> fields = formService.getFormFields(formId);
            Map<String, String> fieldLabels = formService.getFieldLabels(formId);

            // 创建索引映射
            String indexName = "form_" + formId;
            if (!createIndexMapping(indexName, fields)) {
                return createFailureResult("创建索引失败", formId, form.getName());
            }

            // 预加载成员信息
            Map<String, String> memberCache = preloadMemberCache(tableName);
            
            // 预加载主要显示字段（避免每个文档都查询）
            List<String> primaryDisplayFields = getPrimaryDisplayFields(formId);
            // 转换为Map提升查找性能
            Map<String, Integer> primaryFieldsMap = new HashMap<>();
            for (int i = 0; i < primaryDisplayFields.size(); i++) {
                primaryFieldsMap.put(primaryDisplayFields.get(i), i);
            }

            // 根据索引情况选择同步策略 - 主表同步
            SyncResult mainTableResult;
            if (!fullSync && !hasIncrementalIndexes) {
                // 无索引增量同步：使用ES差集方法
                mainTableResult = syncFormDataWithoutIndexes(formId, form, tableName, fields, fieldLabels, memberCache, primaryFieldsMap, startTime);
            } else {
                // 有索引的常规同步
                mainTableResult = syncFormDataWithIndexes(formId, form, tableName, fields, fieldLabels, memberCache, primaryFieldsMap, fullSync, hasIncrementalIndexes, startTime);
            }
            
            // 如果主表同步失败，直接返回
            if (!mainTableResult.isSuccess()) {
                return mainTableResult;
            }
            
            // 同步附表
            SyncResult subTableResult = syncSubTables(formId, form, fullSync, memberCache, startTime);
            
            // 合并主表和附表的同步结果
            return mergeResults(mainTableResult, subTableResult);

        } catch (Exception e) {
            log.error("同步表单数据失败", e);
            return createFailureResult("同步失败: " + e.getMessage(), formId, null);
        }
    }
    
    /**
     * 无索引增量同步方案 - 适用于大表且无法创建modify_date索引的场景
     */
    private SyncResult syncFormDataWithoutIndexes(String formId, FormDto form, String tableName,
            List<Map<String, Object>> fields, Map<String, String> fieldLabels,
            Map<String, String> memberCache, Map<String, Integer> primaryFieldsMap, long startTime) {

        try {
            log.info("执行无索引增量同步: formId={}", formId);

            String indexName = "form_" + formId;
            Set<Long> syncedIds = getSyncedIdsFromES(formId);
            log.info("ES中已同步记录数: {}", syncedIds.size());

            long dbTotalCount = formService.getTableTotalCount(tableName);
            log.info("数据库总记录数: {}", dbTotalCount);

            long successCount = 0;
            long totalChecked = 0;
            int offset = 0;
            BulkSyncBuffer bulkBuffer = newBulkBuffer(formId, indexName, tableName, fieldLabels, memberCache, primaryFieldsMap);

            while (offset < dbTotalCount) {
                List<Map<String, Object>> batch = formService.getTableDataByIdBatch(tableName, dbBatchSize, offset);
                if (batch.isEmpty()) {
                    break;
                }

                List<Map<String, Object>> recordsToSync = new ArrayList<>();
                for (Map<String, Object> record : batch) {
                    Object idObj = record.get("ID");
                    if (idObj == null) {
                        continue;
                    }
                    Long recordId = Long.valueOf(idObj.toString());
                    if (!syncedIds.contains(recordId)) {
                        recordsToSync.add(record);
                    }
                }

                totalChecked += batch.size();

                if (!recordsToSync.isEmpty()) {
                    successCount += bulkBuffer.addRecords(recordsToSync);
                    log.info("批次同步完成: 检查 {} 条，新增 {} 条，累计同步 {} 条",
                            batch.size(), recordsToSync.size(), successCount);
                }

                offset += batch.size();

                if (totalChecked % (dbBatchSize * 10) == 0) {
                    double progress = (double) totalChecked / Math.max(1, dbTotalCount) * 100.0;
                    double elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
                    String progressStr = String.format(Locale.ROOT, "%.1f", progress);
                    String elapsedStr = String.format(Locale.ROOT, "%.1f", elapsedSeconds);
                    log.info("无索引增量同步进度: {}%，已检查 {}/{}，新增 {} 条，耗时 {}s",
                            progressStr, totalChecked, dbTotalCount, successCount, elapsedStr);
                }
            }

            successCount += bulkBuffer.flush();
            refreshIndexSafely(indexName);

            double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
            double rate = successCount / Math.max(elapsed, 0.001);

            SyncResult result = new SyncResult();
            result.setSuccess(true);
            result.setMessage(String.format(Locale.ROOT,
                    "无索引增量同步完成，检查 %d 条记录，新增同步 %d 条记录，耗时 %.1f 秒，平均速度 %.1f 条/秒",
                    totalChecked, successCount, elapsed, rate));
            result.setCount(successCount);
            result.setTotal(totalChecked);
            result.setElapsedTime(elapsed);
            result.setRate(rate);
            result.setFormId(formId);
            result.setFormName(form.getName());
            result.setType("form_sync_no_index");

            return result;

        } catch (Exception e) {
            log.error("无索引增量同步失败", e);
            return createFailureResult("无索引增量同步失败: " + e.getMessage(), formId, form.getName());
        }
    }
    
    /**
     * 有索引的常规同步方案
     */
    private SyncResult syncFormDataWithIndexes(String formId, FormDto form, String tableName, 
            List<Map<String, Object>> fields, Map<String, String> fieldLabels, 
            Map<String, String> memberCache, Map<String, Integer> primaryFieldsMap, boolean fullSync, boolean hasIncrementalIndexes, long startTime) {
        
        try {
            // 确保索引存在（已在主流程中创建，这里不需要重复）
            String indexName = "form_" + formId;

            // 同步数据
            long successCount = 0;
            long totalCount = 0;
            BulkSyncBuffer bulkBuffer = newBulkBuffer(formId, indexName, tableName, fieldLabels, memberCache, primaryFieldsMap);
            
            // 使用基于时间的安全增量同步方法
            LocalDateTime lastModifyDateTime = null;
            Long lastRecordId = null;
            
            if (!fullSync && hasIncrementalIndexes) {
                // 获取ES中最新的修改时间和记录ID
                Map<String, Object> lastRecord = getLatestRecordFromES(formId);
                if (lastRecord != null) {
                    Object modifyDateObj = lastRecord.get("modify_date");
                    Object recordIdObj = lastRecord.get("record_id");
                    
                    if (modifyDateObj != null) {
                        lastModifyDateTime = parseModifyDate(modifyDateObj.toString());
                    }
                    if (recordIdObj != null) {
                        lastRecordId = Long.valueOf(recordIdObj.toString());
                    }
                }
                log.info("执行ES增量同步: formId={}, 最新修改时间={}, 最新记录ID={}", 
                        formId, lastModifyDateTime, lastRecordId);
            } else {
                log.info("执行全量同步: formId={}", formId);
            }

            // 游标分页变量
            LocalDateTime currentModifyDate = lastModifyDateTime;
            Long currentRecordId = lastRecordId;
            Long lastProcessedId = 0L; // 用于全量同步的ID游标
            
            while (true) {
                List<Map<String, Object>> batch;
                
                if (fullSync || !hasIncrementalIndexes) {
                    // 全量同步使用基于ID的游标分页（性能更好）
                    log.debug("查询数据库：tableName={}, limit={}, lastProcessedId={}", tableName, dbBatchSize, lastProcessedId);
                    // 第一次查询时(lastProcessedId=0)传递null，从头开始；后续查询传递实际的ID作为游标
                    Long minIdForQuery = (lastProcessedId == 0L) ? null : lastProcessedId;
                    batch = formService.getTableDataBatchByMinId(tableName, dbBatchSize, 0, minIdForQuery);
                } else {
                    // 增量同步使用新的游标分页方法
                    batch = formService.getTableDataIncrementalCursor(tableName, dbBatchSize, 
                            currentModifyDate, currentRecordId);
                }
                
                if (batch.isEmpty()) {
                    break;
                }

                totalCount += batch.size();
                successCount += bulkBuffer.addRecords(batch);

                // 更新游标位置
                if (!fullSync && !batch.isEmpty()) {
                    // 增量同步：更新游标到当前批次的最后一条记录
                    Map<String, Object> lastRecord = batch.get(batch.size() - 1);
                    Object lastModifyDateObj = lastRecord.get("modify_date");
                    Object lastIdObj = lastRecord.get("ID");
                    
                    if (lastModifyDateObj != null) {
                        try {
                            if (lastModifyDateObj instanceof java.sql.Timestamp) {
                                currentModifyDate = ((java.sql.Timestamp) lastModifyDateObj).toLocalDateTime();
                            } else if (lastModifyDateObj instanceof java.util.Date) {
                                currentModifyDate = new java.sql.Timestamp(((java.util.Date) lastModifyDateObj).getTime()).toLocalDateTime();
                            } else {
                                currentModifyDate = parseModifyDate(lastModifyDateObj.toString());
                            }
                        } catch (Exception e) {
                            log.warn("解析最后记录修改时间失败: {}", lastModifyDateObj, e);
                        }
                    }
                    
                    if (lastIdObj != null) {
                        try {
                            currentRecordId = Long.valueOf(lastIdObj.toString());
                        } catch (NumberFormatException e) {
                            log.warn("解析最后记录ID失败: {}", lastIdObj, e);
                        }
                    }
                    
                    log.debug("游标推进到: modifyDate={}, recordId={}", currentModifyDate, currentRecordId);
                } else if (fullSync || !hasIncrementalIndexes) {
                    // 全量同步：更新ID游标到当前批次的最后一条记录ID（因为数据已按ID排序）
                    if (!batch.isEmpty()) {
                        Map<String, Object> lastRecord = batch.get(batch.size() - 1);
                        Object lastIdObj = lastRecord.get("ID");
                        if (lastIdObj != null) {
                            try {
                                lastProcessedId = Long.valueOf(lastIdObj.toString());
                                log.info("全量同步ID游标推进到: {} (批次最后记录)", lastProcessedId);
                            } catch (NumberFormatException e) {
                                log.warn("解析最后记录ID失败: {}", lastIdObj, e);
                            }
                        }
                    }
                }
                
                // 如果返回的数据少于批次大小，说明已经是最后一批
                if (batch.size() < dbBatchSize) {
                    break;
                }
                
                // 打印进度
                double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
                double rate = successCount / elapsed;
                log.info(String.format("同步进度: %d/%d 条记录 | 耗时: %.1fs | 速度: %.1f 条/秒", 
                    successCount, totalCount, elapsed, rate));
            }

            successCount += bulkBuffer.flush();
            refreshIndexSafely(indexName);

            double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
            double rate = successCount / elapsed;
            
            SyncResult result = new SyncResult();
            result.setSuccess(true);
            result.setMessage(String.format("同步完成，成功同步 %d 条记录，耗时 %.1f 秒，平均速度 %.1f 条/秒", 
                successCount, elapsed, rate));
            result.setCount(successCount);
            result.setTotal(totalCount);
            result.setElapsedTime(elapsed);
            result.setRate(rate);
            result.setFormId(formId);
            result.setFormName(form.getName());
            result.setType("form_sync");
            
            return result;

        } catch (Exception e) {
            log.error("同步表单数据失败", e);
            return createFailureResult("同步失败: " + e.getMessage(), formId, null);
        }
    }

    private boolean createIndexMapping(String indexName, List<Map<String, Object>> fields) {
        Object lock = indexLocks.computeIfAbsent(indexName, key -> new Object());
        synchronized (lock) {
            if (Boolean.TRUE.equals(indexExistenceCache.get(indexName))) {
                return true;
            }

            if (indexExists(indexName)) {
                log.debug("索引已存在，跳过创建: {}", indexName);
                return true;
            }

            try {
                Map<String, Object> properties = new HashMap<>();
                properties.put("form_id", Map.of("type", "keyword"));
                properties.put("table_name", Map.of("type", "keyword"));
                properties.put("record_id", Map.of("type", "long"));
                properties.put("sync_time", Map.of("type", "date"));

                String dateFormats = "strict_date_optional_time||epoch_millis||yyyy-MM-dd HH:mm:ss.S||yyyy-MM-dd HH:mm:ss||yyyy-MM-dd";
                properties.put("modify_date", Map.of("type", "date", "format", dateFormats));
                properties.put("start_date", Map.of("type", "date", "format", dateFormats));
                properties.put("start_member_id", Map.of("type", "long"));
                properties.put("modify_member_id", Map.of("type", "long"));
                properties.put("approve_member_id", Map.of("type", "long"));
                properties.put("ratify_member_id", Map.of("type", "long"));

                for (Map<String, Object> field : fields) {
                    String fieldName = getFieldName(field);
                    String fieldType = getFieldType(field);

                    if (fieldType.matches("(?i)text|VARCHAR")) {
                        properties.put(fieldName, Map.of("type", "text", "analyzer", "standard"));
                    } else if (fieldType.matches("(?i)datetime|TIMESTAMP|date|DATE")) {
                        properties.put(fieldName, Map.of("type", "date", "format", dateFormats));
                    } else if (fieldType.matches("(?i)DECIMAL|INTEGER")) {
                        properties.put(fieldName, Map.of("type", "double"));
                    } else if (fieldType.equals("member")) {
                        properties.put(fieldName, Map.of("type", "keyword"));
                    } else {
                        properties.put(fieldName, Map.of("type", "text"));
                    }
                }

                Map<String, Object> mapping = Map.of(
                    "mappings", Map.of("properties", properties),
                    "settings", Map.of(
                        "analysis", Map.of(
                            "analyzer", Map.of(
                                "default", Map.of("type", "standard")
                            )
                        )
                    )
                );

                CreateIndexRequest createRequest = new CreateIndexRequest(indexName);
                createRequest.source(objectMapper.writeValueAsString(mapping), XContentType.JSON);

                executeWithRetry(() -> {
                    esClient.indices().create(createRequest, RequestOptions.DEFAULT);
                    return Boolean.TRUE;
                }, "创建索引 " + indexName);

                indexExistenceCache.put(indexName, true);
                log.info("创建索引 {} 成功", indexName);
                return true;

            } catch (Exception e) {
                indexExistenceCache.remove(indexName);
                log.error("创建索引失败: {}", indexName, e);
                return false;
            }
        }
    }

    private boolean indexExists(String indexName) {
        Boolean cached = indexExistenceCache.get(indexName);
        if (Boolean.TRUE.equals(cached)) {
            return true;
        }

        GetIndexRequest getRequest = new GetIndexRequest(indexName);
        try {
            Boolean exists = executeWithRetry(
                () -> esClient.indices().exists(getRequest, RequestOptions.DEFAULT),
                "检查索引是否存在 " + indexName
            );

            if (Boolean.TRUE.equals(exists)) {
                indexExistenceCache.put(indexName, true);
                return true;
            }

            return false;

        } catch (Exception e) {
            log.error("检查索引是否存在失败: {}", indexName, e);
            return false;
        }
    }

    private <T> T executeWithRetry(ESCallable<T> action, String actionDescription) throws Exception {
        int attempt = 0;
        int maxRetries = Math.max(0, esMaxRetries);

        while (true) {
            try {
                return action.call();
            } catch (Exception ex) {
                attempt++;

                if (!shouldRetry(ex) || attempt > maxRetries) {
                    throw ex;
                }

                long sleepMillis = calculateBackoffMillis(attempt);
                String sleepHuman = String.format(Locale.ROOT, "%.1f", sleepMillis / 1000.0);
                log.warn("ES操作 {} 失败: {}，将在 {} ms (~{} 秒) 后重试 (第 {}/{})",
                        actionDescription, getStatusForLog(ex), sleepMillis, sleepHuman, attempt, maxRetries);

                try {
                    TimeUnit.MILLISECONDS.sleep(sleepMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("重试被中断", ie);
                }
            }
        }
    }

    private long calculateBackoffMillis(int attempt) {
        long baseDelay = Math.max(50L, esRetryInitialBackoffMillis);
        long maxDelay = Math.max(baseDelay, esRetryMaxBackoffMillis);
        long multiplier = 1L << Math.max(0, attempt - 1);
        long backoff = baseDelay * multiplier;
        return Math.min(backoff, maxDelay);
    }

    private boolean shouldRetry(Throwable throwable) {
        if (throwable == null) {
            return false;
        }

        ElasticsearchStatusException statusException = findCause(throwable, ElasticsearchStatusException.class);
        if (statusException != null && statusException.status() == RestStatus.TOO_MANY_REQUESTS) {
            return true;
        }

        ResponseException responseException = findCause(throwable, ResponseException.class);
        if (responseException != null && responseException.getResponse() != null &&
                responseException.getResponse().getStatusLine().getStatusCode() == 429) {
            return true;
        }

        if (findCause(throwable, IOException.class) != null) {
            return true;
        }

        return false;
    }

    private String getStatusForLog(Throwable throwable) {
        ElasticsearchStatusException statusException = findCause(throwable, ElasticsearchStatusException.class);
        if (statusException != null) {
            return statusException.status().toString();
        }

        ResponseException responseException = findCause(throwable, ResponseException.class);
        if (responseException != null && responseException.getResponse() != null) {
            return responseException.getResponse().getStatusLine().toString();
        }

        return throwable.getMessage();
    }

    private <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    @FunctionalInterface
    private interface ESCallable<T> {
        T call() throws Exception;
    }

    private Map<String, Object> buildDocument(String formId, String tableName, 
            Map<String, Object> record, Map<String, String> fieldLabels, Map<String, String> memberCache, 
            Map<String, Integer> primaryFieldsMap) {
        
        Map<String, Object> doc = new HashMap<>();
        doc.put("form_id", formId);
        doc.put("table_name", tableName);
        doc.put("record_id", Long.valueOf(String.valueOf(record.get("ID"))));
        doc.put("sync_time", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        // 使用预加载的主要显示字段（避免重复查询）
        
        // 添加主表字段数据，并转换字段名为中文标签
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            if (value == null || shouldSkipField(key)) {
                continue;
            }

            // 对于系统字段，同时存储原始字段名和中文名
            if (isSystemField(key)) {
                Object formattedValue = formatFieldValue(key, value, memberCache);
                if (formattedValue != null) {
                    // 存储原始字段名（用于查询和排序）
                    doc.put(key, formattedValue);
                    // 存储中文显示名（用于前端显示）
                    String displayName = getFieldDisplayName(key, fieldLabels);
                    doc.put(displayName, formattedValue);
                }
            } else {
                // 转换字段名为中文标签
                String displayName = getFieldDisplayName(key, fieldLabels);
                
                // 格式化值
                Object formattedValue = formatFieldValue(key, value, memberCache);
                if (formattedValue != null) {
                    doc.put(displayName, formattedValue);
                    
                    // 标记主要显示字段，用于前端标题提取（O(1)查找）
                    Integer priority = primaryFieldsMap.get(key);
                    if (priority != null) {
                        doc.put("_primary_field_" + priority, displayName);
                        doc.put("_primary_value_" + priority, formattedValue);
                    }
                }
            }
        }

        return doc;
    }

    private Map<String, String> preloadMemberCache(String tableName) {
        try {
            log.info("预加载成员信息缓存");
            long startTime = System.currentTimeMillis();
            
            // 优化SQL: 使用UNION ALL一次性获取所有不重复的成员ID
            String sql = "SELECT DISTINCT member_id FROM (" +
                        "SELECT start_member_id as member_id FROM " + tableName + " WHERE start_member_id IS NOT NULL AND start_member_id != 0 " +
                        "UNION ALL " +
                        "SELECT modify_member_id as member_id FROM " + tableName + " WHERE modify_member_id IS NOT NULL AND modify_member_id != 0 " +
                        "UNION ALL " +
                        "SELECT approve_member_id as member_id FROM " + tableName + " WHERE approve_member_id IS NOT NULL AND approve_member_id != 0 " +
                        "UNION ALL " +
                        "SELECT ratify_member_id as member_id FROM " + tableName + " WHERE ratify_member_id IS NOT NULL AND ratify_member_id != 0" +
                        ") t";
            
            List<Map<String, Object>> memberRecords = formService.getJdbcTemplate().queryForList(sql);
            Set<Long> memberIds = new HashSet<>();
            
            // 收集所有成员ID
            for (Map<String, Object> record : memberRecords) {
                addMemberIdIfValid(memberIds, record.get("member_id"));
            }
            
            // 批量获取成员姓名
            Map<String, String> memberCache = new HashMap<>();
            if (!memberIds.isEmpty()) {
                memberCache = memberService.getMembersBatch(new ArrayList<>(memberIds));
            }
            
            double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
            log.info("成员信息缓存预加载完成: {} 个成员，耗时 {} 秒", memberCache.size(), String.format("%.1f", elapsed));
            
            return memberCache;
        } catch (Exception e) {
            log.error("预加载成员信息缓存失败", e);
            return new HashMap<>();
        }
    }
    
    private void addMemberIdIfValid(Set<Long> memberIds, Object memberIdObj) {
        if (memberIdObj != null) {
            try {
                Long memberId;
                if (memberIdObj instanceof Number) {
                    memberId = ((Number) memberIdObj).longValue();
                } else {
                    memberId = Long.valueOf(memberIdObj.toString());
                }
                
                if (memberId != 0) {
                    memberIds.add(memberId);
                }
            } catch (NumberFormatException e) {
                log.debug("无效的成员ID: {}", memberIdObj);
                // 忽略无效的成员ID
            }
        }
    }

    private Object formatFieldValue(String fieldName, Object value, Map<String, String> memberCache) {
        if (value == null) return null;
        
        

        // 处理成员字段 - 特别处理系统成员字段
        if (fieldName.endsWith("_member_id") || fieldName.endsWith("member_id") || 
            fieldName.equals("start_member_id") || fieldName.equals("modify_member_id") ||
            fieldName.equals("approve_member_id") || fieldName.equals("ratify_member_id")) {
            
            String memberName = memberCache.get(String.valueOf(value));
            if (memberName != null) {
                return memberName;
            }
            
            // 如果缓存中没有，直接返回ID值（避免单独查询）
            return String.valueOf(value);
        }

        // 处理日期字段 - 优先处理，避免错误的格式进入ES
        if (value instanceof Date) {
            try {
                if (value instanceof java.sql.Date) {
                    // java.sql.Date 不支持 toInstant()，使用 getTime() 转换
                    LocalDateTime dateTime = new java.sql.Timestamp(((java.sql.Date) value).getTime()).toLocalDateTime();
                    return dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                } else if (value instanceof java.sql.Timestamp) {
                    LocalDateTime dateTime = ((java.sql.Timestamp) value).toLocalDateTime();
                    return dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                } else {
                    LocalDateTime dateTime = new java.sql.Timestamp(((Date) value).getTime()).toLocalDateTime();
                    return dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                }
            } catch (Exception e) {
                log.warn("日期对象转换失败: {} (类型: {})", value, value.getClass().getSimpleName(), e);
                // 如果日期转换失败，尝试字符串转换
                String valueStr = value.toString();
                if (valueStr.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d+")) {
                    try {
                        String cleanDateStr = valueStr.replaceAll("\\.\\d+$", "");
                        LocalDateTime dateTime = LocalDateTime.parse(cleanDateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                        return dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                    } catch (Exception e2) {
                        log.error("字符串日期转换也失败: {}", valueStr, e2);
                    }
                }
                return String.valueOf(value);
            }
        }
        
        // 处理所有可能的日期字符串格式
        if (value instanceof String) {
            String valueStr = value.toString().trim();
            
            // 使用预编译的正则表达式，避免重复编译
            try {
                if (DATE_TIME_WITH_MILLIS.matcher(valueStr).matches()) {
                    // 处理形如 "2025-09-20 16:33:47.0" 的格式
                    String cleanDateStr = valueStr.replaceAll("\\.\\d+$", "");
                    LocalDateTime dateTime = LocalDateTime.parse(cleanDateStr, DATE_TIME_FORMATTER);
                    return dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                } else if (DATE_TIME.matcher(valueStr).matches()) {
                    // 处理形如 "2025-09-20 16:33:47" 的格式
                    LocalDateTime dateTime = LocalDateTime.parse(valueStr, DATE_TIME_FORMATTER);
                    return dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                } else if (fieldName.contains("date") && DATE_ONLY.matcher(valueStr).matches()) {
                    // 处理形如 "2025-09-20" 的格式
                    java.time.LocalDate date = java.time.LocalDate.parse(valueStr, DATE_FORMATTER);
                    return date.atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                }
            } catch (Exception e) {
                log.warn("日期字符串转换失败: {} (字段: {})", valueStr, fieldName, e);
                // 如果日期解析失败，返回原字符串
                return valueStr;
            }
        }

        return String.valueOf(value);
    }

    private String getFieldDisplayName(String fieldName, Map<String, String> fieldLabels) {
        String label = fieldLabels.get(fieldName);
        if (label != null) return label;

        // 处理特殊系统字段
        Map<String, String> systemFields = Map.of(
            "start_date", "创建时间",
            "modify_date", "修改时间",
            "start_member_id", "创建人",
            "modify_member_id", "修改人",
            "approve_member_id", "审核人",
            "ratify_member_id", "核定人"
        );

        return systemFields.getOrDefault(fieldName, fieldName);
    }

    private boolean shouldSkipField(String fieldName) {
        Set<String> hiddenFields = Set.of(
            "ID", "id", "form_id", "table_name", "record_id", "sync_time",
            "state", "sort", "ratifyflag", "finishedflag", "approve_date", "ratify_date",
            "start_date", "modify_date", "start_member_id", "modify_member_id", 
            "approve_member_id", "ratify_member_id"
        );
        return hiddenFields.contains(fieldName);
    }

    private boolean isSystemField(String fieldName) {
        Set<String> systemFields = Set.of(
            "start_date", "modify_date", "start_member_id", "modify_member_id", 
            "approve_member_id", "ratify_member_id"
        );
        return systemFields.contains(fieldName);
    }

    private String getFieldName(Map<String, Object> field) {
        String name = (String) field.get("name");
        return name != null ? name : (String) field.get("columnName");
    }

    private String getFieldType(Map<String, Object> field) {
        String type = (String) field.get("type");
        return type != null ? type : (String) field.get("fieldType");
    }


    private List<String> getPrimaryDisplayFields(String formId) {
        try {
            FormDto form = formService.getFormById(formId);
            if (form == null || form.getViewInfo() == null) {
                return new ArrayList<>();
            }
            
            Map<String, Object> viewInfo = form.getViewInfo();
            Object formViewListObj = viewInfo.get("formViewList");
            
            if (formViewListObj instanceof List) {
                List<Map<String, Object>> formViewList = (List<Map<String, Object>>) formViewListObj;
                
                // 找到第一个视图（通常是主视图）
                for (Map<String, Object> view : formViewList) {
                    Object fieldNamesObj = view.get("fieldNames");
                    if (fieldNamesObj instanceof List) {
                        return (List<String>) fieldNamesObj;
                    }
                }
            }
            
            return new ArrayList<>();
        } catch (Exception e) {
            log.error("获取主要显示字段失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 从Elasticsearch查询指定表单的最新修改时间
     */
    private String getLatestModifyDateFromES(String formId) {
        try {
            String indexName = "form_" + formId;

            if (!indexExists(indexName)) {
                log.debug("索引不存在，返回null作为起始时间: {}", indexName);
                return null;
            }

            SearchRequest searchRequest = new SearchRequest(indexName);
            SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
            searchSourceBuilder.query(QueryBuilders.matchAllQuery());
            searchSourceBuilder.sort("modify_date", SortOrder.DESC);
            searchSourceBuilder.size(1);
            searchSourceBuilder.fetchSource(new String[]{"modify_date"}, null);
            searchRequest.source(searchSourceBuilder);

            SearchResponse searchResponse = executeWithRetry(
                    () -> esClient.search(searchRequest, RequestOptions.DEFAULT),
                    "查询索引最新修改时间 " + indexName);
            
            if (searchResponse.getHits().getTotalHits().value > 0) {
                SearchHit hit = searchResponse.getHits().getAt(0);
                Map<String, Object> source = hit.getSourceAsMap();
                Object modifyDateObj = source.get("modify_date");
                
                if (modifyDateObj != null) {
                    String modifyDate = modifyDateObj.toString();
                    log.info("从ES获取最新修改时间: formId={}, latestModifyDate={}", formId, modifyDate);
                    return modifyDate;
                }
            }
            
            log.debug("ES中没有找到记录，返回null作为起始时间: formId={}", formId);
            return null;
            
        } catch (Exception e) {
            log.error("从ES查询最新修改时间失败: formId={}", formId, e);
            return null;
        }
    }

    /**
     * 从Elasticsearch查询指定表单的最新记录（包含修改时间和记录ID）
     */
    private Map<String, Object> getLatestRecordFromES(String formId) {
        try {
            String indexName = "form_" + formId;

            if (!indexExists(indexName)) {
                log.debug("索引不存在，返回null: {}", indexName);
                return null;
            }

            SearchRequest searchRequest = new SearchRequest(indexName);
            SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
            searchSourceBuilder.query(QueryBuilders.matchAllQuery());
            searchSourceBuilder.sort("modify_date", SortOrder.DESC);
            searchSourceBuilder.sort("record_id", SortOrder.DESC);
            searchSourceBuilder.size(1);
            searchSourceBuilder.fetchSource(new String[]{"modify_date", "record_id"}, null);
            searchRequest.source(searchSourceBuilder);

            SearchResponse searchResponse = executeWithRetry(
                    () -> esClient.search(searchRequest, RequestOptions.DEFAULT),
                    "查询索引最新记录 " + indexName);
            
            if (searchResponse.getHits().getTotalHits().value > 0) {
                SearchHit hit = searchResponse.getHits().getAt(0);
                Map<String, Object> source = hit.getSourceAsMap();
                
                log.info("从ES获取最新记录: formId={}, modifyDate={}, recordId={}", 
                        formId, source.get("modify_date"), source.get("record_id"));
                
                return source;
            }
            
            log.debug("ES中没有找到记录: formId={}", formId);
            return null;
            
        } catch (Exception e) {
            log.error("从ES查询最新记录失败: formId={}", formId, e);
            return null;
        }
    }

    /**
     * 获取批次记录中的最大修改时间
     */
    private LocalDateTime getBatchMaxModifyTime(List<Map<String, Object>> batch) {
        LocalDateTime maxTime = null;
        
        for (Map<String, Object> record : batch) {
            Object modifyDateObj = record.get("modify_date");
            if (modifyDateObj != null) {
                try {
                    LocalDateTime recordTime;
                    if (modifyDateObj instanceof java.sql.Timestamp) {
                        recordTime = ((java.sql.Timestamp) modifyDateObj).toLocalDateTime();
                    } else if (modifyDateObj instanceof java.util.Date) {
                        recordTime = new java.sql.Timestamp(((java.util.Date) modifyDateObj).getTime()).toLocalDateTime();
                    } else {
                        // 尝试解析字符串
                        recordTime = parseModifyDate(modifyDateObj.toString());
                    }
                    
                    if (maxTime == null || recordTime.isAfter(maxTime)) {
                        maxTime = recordTime;
                    }
                } catch (Exception e) {
                    log.debug("解析记录修改时间失败: {}", modifyDateObj, e);
                }
            }
        }
        
        return maxTime;
    }

    /**
     * 解析从ES获取的时间字符串为LocalDateTime
     */
    private LocalDateTime parseModifyDate(String dateStr) {
        try {
            // 尝试解析ISO格式的时间字符串
            if (dateStr.contains("T")) {
                return LocalDateTime.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } else {
                // 如果是其他格式，可以添加更多解析逻辑
                return LocalDateTime.parse(dateStr);
            }
        } catch (Exception e) {
            log.warn("解析时间字符串失败: {}, 使用默认时间", dateStr, e);
            // 如果解析失败，返回一个很早的时间，这样会获取所有记录
            return LocalDateTime.of(1970, 1, 1, 0, 0, 0);
        }
    }

    /**
     * 获取ES中已同步的记录ID集合
     */
    private Set<Long> getSyncedIdsFromES(String formId) {
        Set<Long> syncedIds = new HashSet<>();
        
        try {
            String indexName = "form_" + formId;
            
            if (!indexExists(indexName)) {
                log.debug("索引不存在，返回空ID集合: {}", indexName);
                return syncedIds;
            }
            
            // 使用scroll API获取所有记录ID（内存效率高）
            SearchRequest searchRequest = new SearchRequest(indexName);
            SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
            
            // 只查询record_id字段
            searchSourceBuilder.fetchSource(new String[]{"record_id"}, null);
            searchSourceBuilder.size(10000); // 每次获取10000条
            searchSourceBuilder.query(QueryBuilders.matchAllQuery());
            
            searchRequest.source(searchSourceBuilder);
            searchRequest.scroll(TimeValue.timeValueMinutes(2L));
            
            SearchResponse searchResponse = executeWithRetry(
                    () -> esClient.search(searchRequest, RequestOptions.DEFAULT),
                    "初始化scroll查询 " + indexName);
            String scrollId = searchResponse.getScrollId();
            
            // 处理第一批结果
            for (SearchHit hit : searchResponse.getHits().getHits()) {
                Object recordIdObj = hit.getSourceAsMap().get("record_id");
                if (recordIdObj != null) {
                    try {
                        syncedIds.add(Long.valueOf(recordIdObj.toString()));
                    } catch (NumberFormatException e) {
                        log.debug("无效的record_id: {}", recordIdObj);
                    }
                }
            }
            
            // 处理后续批次
            while (searchResponse.getHits().getHits().length > 0) {
                org.elasticsearch.action.search.SearchScrollRequest scrollRequest = 
                    new org.elasticsearch.action.search.SearchScrollRequest(scrollId);
                scrollRequest.scroll(TimeValue.timeValueMinutes(2L));
                
                SearchResponse nextResponse = executeWithRetry(
                        () -> esClient.scroll(scrollRequest, RequestOptions.DEFAULT),
                        "scroll分页查询 " + indexName);
                scrollId = nextResponse.getScrollId();
                searchResponse = nextResponse;
                
                for (SearchHit hit : searchResponse.getHits().getHits()) {
                    Object recordIdObj = hit.getSourceAsMap().get("record_id");
                    if (recordIdObj != null) {
                        try {
                            syncedIds.add(Long.valueOf(recordIdObj.toString()));
                        } catch (NumberFormatException e) {
                            log.debug("无效的record_id: {}", recordIdObj);
                        }
                    }
                }
            }
            
            // 清理scroll
            if (scrollId != null && !scrollId.isEmpty()) {
                org.elasticsearch.action.search.ClearScrollRequest clearScrollRequest =
                        new org.elasticsearch.action.search.ClearScrollRequest();
                clearScrollRequest.addScrollId(scrollId);
                executeWithRetry(() -> {
                    esClient.clearScroll(clearScrollRequest, RequestOptions.DEFAULT);
                    return Boolean.TRUE;
                }, "清理scroll上下文 " + indexName);
            }
            
            log.debug("从ES获取已同步ID集合完成: formId={}, 记录数={}", formId, syncedIds.size());
            
        } catch (Exception e) {
            log.error("获取ES已同步ID集合失败: formId={}", formId, e);
        }
        
        return syncedIds;
    }
    
    /**
     * 批量同步记录到ES
     */
    private long syncRecordBatch(String formId, String indexName, String tableName, 
                                List<Map<String, Object>> records, Map<String, String> fieldLabels,
                                Map<String, String> memberCache, Map<String, Integer> primaryFieldsMap) {
        if (records == null || records.isEmpty()) {
            return 0L;
        }

        long successCount = 0L;
        int flushThreshold = Math.max(1, esBulkMaxActions);
        int chunkSize = Math.max(1, Math.min(batchSize, flushThreshold));
        int timeoutSeconds = Math.max(5, bulkTimeoutSeconds);

        for (int from = 0; from < records.size(); from += chunkSize) {
            int to = Math.min(records.size(), from + chunkSize);
            List<Map<String, Object>> chunk = records.subList(from, to);

            BulkRequest bulkRequest = new BulkRequest();
            bulkRequest.timeout(TimeValue.timeValueSeconds(timeoutSeconds));

            for (Map<String, Object> record : chunk) {
                String docId = formId + "_" + record.get("ID");
                Map<String, Object> doc = buildDocument(formId, tableName, record, fieldLabels, memberCache, primaryFieldsMap);

                IndexRequest indexRequest = new IndexRequest(indexName)
                    .id(docId)
                    .source(doc, XContentType.JSON);
                bulkRequest.add(indexRequest);
            }

            try {
                BulkResponse bulkResponse = executeWithRetry(
                        () -> esClient.bulk(bulkRequest, RequestOptions.DEFAULT),
                        "批量写入索引 " + indexName);

                if (bulkResponse.hasFailures()) {
                    log.error("批量同步失败: {}", bulkResponse.buildFailureMessage());
                } else {
                    successCount += chunk.size();
                }

            } catch (Exception e) {
                log.error("批量同步记录失败: formId={}, 索引={}, 批次大小={}",
                        formId, indexName, chunk.size(), e);
            }
        }

        return successCount;
    }

    private BulkSyncBuffer newBulkBuffer(String formId, String indexName, String tableName,
                                         Map<String, String> fieldLabels,
                                         Map<String, String> memberCache,
                                         Map<String, Integer> primaryFieldsMap) {
        return new BulkSyncBuffer(formId, indexName, tableName, fieldLabels, memberCache, primaryFieldsMap);
    }

    private void refreshIndexSafely(String indexName) {
        try {
            executeWithRetry(() -> {
                esClient.indices().refresh(new RefreshRequest(indexName), RequestOptions.DEFAULT);
                return Boolean.TRUE;
            }, "刷新索引 " + indexName);
        } catch (Exception e) {
            log.warn("刷新索引失败: {}", indexName, e);
        }
    }

    private final class BulkSyncBuffer {
        private final List<Map<String, Object>> buffer = new ArrayList<>();
        private final int flushThreshold;
        private final String formId;
        private final String indexName;
        private final String tableName;
        private final Map<String, String> fieldLabels;
        private final Map<String, String> memberCache;
        private final Map<String, Integer> primaryFieldsMap;

        private BulkSyncBuffer(String formId, String indexName, String tableName,
                               Map<String, String> fieldLabels,
                               Map<String, String> memberCache,
                               Map<String, Integer> primaryFieldsMap) {
            this.formId = formId;
            this.indexName = indexName;
            this.tableName = tableName;
            this.fieldLabels = fieldLabels;
            this.memberCache = memberCache;
            this.primaryFieldsMap = primaryFieldsMap;
            this.flushThreshold = Math.max(1, esBulkMaxActions);
        }

        private long addRecords(Collection<Map<String, Object>> records) {
            if (records == null || records.isEmpty()) {
                return 0L;
            }

            long synced = 0L;
            for (Map<String, Object> record : records) {
                if (record == null) {
                    continue;
                }
                buffer.add(record);
                if (buffer.size() >= flushThreshold) {
                    synced += flush();
                }
            }
            return synced;
        }

        private long flush() {
            if (buffer.isEmpty()) {
                return 0L;
            }
            List<Map<String, Object>> toFlush = new ArrayList<>(buffer);
            buffer.clear();
            return syncRecordBatch(formId, indexName, tableName, toFlush, fieldLabels, memberCache, primaryFieldsMap);
        }
    }

    /**
     * 同步表单的所有附表
     */
    private SyncResult syncSubTables(String formId, FormDto form, boolean fullSync, 
                                   Map<String, String> memberCache, long startTime) {
        try {
            List<Map<String, Object>> subTables = formService.getFormSubTables(formId);
            if (subTables.isEmpty()) {
                log.debug("表单 {} 没有附表", formId);
                return createSuccessResult("无附表需要同步", formId, form.getName(), 0, 0.0);
            }
            
            long totalSubTableCount = 0;
            long totalSubTableRecords = 0;
            
            for (Map<String, Object> subTable : subTables) {
                String subTableName = formService.getSubTableName(subTable);
                if (subTableName == null) {
                    log.warn("附表表名为空，跳过同步");
                    continue;
                }
                
                log.info("开始同步附表: {}", subTableName);
                
                // 检查附表是否存在
                if (!formService.checkTableExists(subTableName)) {
                    log.warn("附表不存在，跳过: {}", subTableName);
                    continue;
                }
                
                // 获取附表字段信息
                List<Map<String, Object>> subTableFields = formService.getSubTableFields(subTable);
                Map<String, String> subTableFieldLabels = getSubTableFieldLabels(subTableFields);
                
                // 创建附表索引
                String subTableIndexName = "form_" + formId + "_sub_" + subTableName.toLowerCase();
                if (!createIndexMapping(subTableIndexName, subTableFields)) {
                    log.error("创建附表索引失败: {}", subTableIndexName);
                    continue;
                }
                
                // 同步附表数据
                long subTableSyncCount = syncSubTableData(formId, subTableIndexName, subTableName, 
                        subTableFields, subTableFieldLabels, memberCache, fullSync);
                
                totalSubTableCount++;
                totalSubTableRecords += subTableSyncCount;
                
                log.info("附表同步完成: {}, 同步记录数: {}", subTableName, subTableSyncCount);
            }
            
            double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
            return createSuccessResult(
                String.format("附表同步完成，共同步 %d 个附表，%d 条记录", totalSubTableCount, totalSubTableRecords),
                formId, form.getName(), totalSubTableRecords, elapsed);
                
        } catch (Exception e) {
            log.error("同步附表失败", e);
            return createFailureResult("附表同步失败: " + e.getMessage(), formId, form.getName());
        }
    }
    
    /**
     * 同步单个附表的数据
     */
    private long syncSubTableData(String formId, String indexName, String tableName, 
                                List<Map<String, Object>> fields, Map<String, String> fieldLabels,
                                Map<String, String> memberCache, boolean fullSync) {
        try {
            long successCount = 0;
            Long lastProcessedId = 0L;
            
            while (true) {
                // 使用ID游标分页获取附表数据
                List<Map<String, Object>> batch = formService.getTableDataBatchByMinId(tableName, dbBatchSize, 0, lastProcessedId);
                
                if (batch.isEmpty()) {
                    break;
                }
                int flushThreshold = Math.max(1, esBulkMaxActions);
                int chunkSize = Math.max(1, Math.min(dbBatchSize, flushThreshold));
                int timeoutSeconds = Math.max(5, bulkTimeoutSeconds);

                for (int from = 0; from < batch.size(); from += chunkSize) {
                    int to = Math.min(batch.size(), from + chunkSize);
                    List<Map<String, Object>> chunk = batch.subList(from, to);

                    BulkRequest bulkRequest = new BulkRequest();
                    bulkRequest.timeout(TimeValue.timeValueSeconds(timeoutSeconds));

                    for (Map<String, Object> record : chunk) {
                        String docId = formId + "_" + tableName + "_" + record.get("ID");
                        Map<String, Object> doc = buildSubTableDocument(formId, tableName, record, fieldLabels, memberCache);

                        IndexRequest indexRequest = new IndexRequest(indexName)
                                .id(docId)
                                .source(doc, XContentType.JSON);
                        bulkRequest.add(indexRequest);
                    }

                    try {
                        BulkResponse bulkResponse = executeWithRetry(
                                () -> esClient.bulk(bulkRequest, RequestOptions.DEFAULT),
                                "附表批量写入索引 " + indexName);

                        if (bulkResponse.hasFailures()) {
                            log.error("附表批量插入失败: {}", bulkResponse.buildFailureMessage());
                        } else {
                            successCount += chunk.size();
                        }

                    } catch (Exception e) {
                        log.error("附表批量同步失败: formId={}, tableName={}, 批次大小={}",
                                formId, tableName, chunk.size(), e);
                    }
                }
                
                // 更新ID游标到当前批次的最后一条记录ID（因为数据已按ID排序）
                if (!batch.isEmpty()) {
                    Map<String, Object> lastRecord = batch.get(batch.size() - 1);
                    Object lastIdObj = lastRecord.get("ID");
                    if (lastIdObj != null) {
                        try {
                            lastProcessedId = Long.valueOf(lastIdObj.toString());
                            log.debug("附表ID游标推进到: {} (批次最后记录)", lastProcessedId);
                        } catch (NumberFormatException e) {
                            log.warn("解析附表最后记录ID失败: {}", lastIdObj, e);
                        }
                    }
                }
                
                // 如果返回的数据少于批次大小，说明已经是最后一批
                if (batch.size() < dbBatchSize) {
                    break;
                }
            }
            
            refreshIndexSafely(indexName);
            
            return successCount;
            
        } catch (Exception e) {
            log.error("同步附表数据失败: {}", tableName, e);
            return 0;
        }
    }
    
    /**
     * 构建附表文档
     */
    private Map<String, Object> buildSubTableDocument(String formId, String tableName, 
            Map<String, Object> record, Map<String, String> fieldLabels, Map<String, String> memberCache) {
        
        Map<String, Object> doc = new HashMap<>();
        doc.put("form_id", formId);
        doc.put("table_name", tableName);
        doc.put("table_type", "sub_table"); // 标记为附表
        doc.put("record_id", Long.valueOf(String.valueOf(record.get("ID"))));
        doc.put("sync_time", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        
        // 处理附表字段
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            if (value == null || shouldSkipField(key)) {
                continue;
            }
            
            // 转换字段名为中文标签
            String displayName = getFieldDisplayName(key, fieldLabels);
            
            // 格式化值
            Object formattedValue = formatFieldValue(key, value, memberCache);
            if (formattedValue != null) {
                doc.put(displayName, formattedValue);
            }
        }
        
        return doc;
    }
    
    /**
     * 获取附表字段标签
     */
    private Map<String, String> getSubTableFieldLabels(List<Map<String, Object>> fields) {
        Map<String, String> labels = new HashMap<>();
        
        for (Map<String, Object> field : fields) {
            String fieldName = getFieldName(field);
            String fieldLabel = formService.getFieldLabel(field);
            if (fieldName != null && fieldLabel != null) {
                labels.put(fieldName, fieldLabel);
            }
        }
        
        return labels;
    }
    
    /**
     * 合并主表和附表的同步结果
     */
    private SyncResult mergeResults(SyncResult mainResult, SyncResult subResult) {
        SyncResult mergedResult = new SyncResult();
        mergedResult.setSuccess(mainResult.isSuccess() && subResult.isSuccess());
        mergedResult.setFormId(mainResult.getFormId());
        mergedResult.setFormName(mainResult.getFormName());
        mergedResult.setType("form_sync_with_subtables");
        
        long totalCount = mainResult.getCount() + subResult.getCount();
        double totalElapsed = Math.max(mainResult.getElapsedTime(), subResult.getElapsedTime());
        double avgRate = totalCount / totalElapsed;
        
        mergedResult.setCount(totalCount);
        mergedResult.setTotal(mainResult.getTotal() + subResult.getTotal());
        mergedResult.setElapsedTime(totalElapsed);
        mergedResult.setRate(avgRate);
        
        String message = String.format("主表: %s, 附表: %s", mainResult.getMessage(), subResult.getMessage());
        mergedResult.setMessage(message);
        
        return mergedResult;
    }
    
    /**
     * 创建成功结果
     */
    private SyncResult createSuccessResult(String message, String formId, String formName, long count, double elapsed) {
        SyncResult result = new SyncResult();
        result.setSuccess(true);
        result.setMessage(message);
        result.setFormId(formId);
        result.setFormName(formName);
        result.setCount(count);
        result.setElapsedTime(elapsed);
        result.setRate(count / Math.max(elapsed, 0.001));
        result.setType("sub_table_sync");
        return result;
    }

    private SyncResult createFailureResult(String message, String formId, String formName) {
        SyncResult result = new SyncResult();
        result.setSuccess(false);
        result.setMessage(message);
        result.setFormId(formId);
        result.setFormName(formName);
        result.setType("form_sync");
        return result;
    }
}
