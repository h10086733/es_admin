package com.esadmin.service;

import com.esadmin.dto.ExcelImportMetadata;
import com.esadmin.dto.ExcelImportResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.*;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xcontent.XContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Excel 数据导入服务：负责解析Excel、动态建表、写入数据库并同步到Elasticsearch。
 */
@Service
public class ExcelImportService {

    private static final Logger log = LoggerFactory.getLogger(ExcelImportService.class);

    private static final String META_TABLE = "EXCEL_IMPORT_META";
    private static final int DB_BATCH_SIZE = 500;
    private static final int ES_BATCH_SIZE = 500;
    private static final DateTimeFormatter ES_TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final JdbcTemplate jdbcTemplate;
    private final RestHighLevelClient esClient;
    private final ObjectMapper objectMapper;
    private final DataFormatter dataFormatter = new DataFormatter();

    private final Map<String, Map<String, String>> columnLabelCache = new ConcurrentHashMap<>();
    private final Map<String, String> displayNameCache = new ConcurrentHashMap<>();

    public ExcelImportService(JdbcTemplate jdbcTemplate,
                              RestHighLevelClient esClient,
                              ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.esClient = esClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 导入Excel文件并写入数据库与ES。
     */
    public ExcelImportResult importExcel(MultipartFile file,
                                         String customName,
                                         String requestedSheetName,
                                         boolean cover) throws IOException {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }

        ExcelParsedData parsedData = parseExcel(file, requestedSheetName);

        String baseName = StringUtils.defaultIfBlank(customName, parsedData.getDisplayName());
        String tableName = buildTableName(baseName);
        String indexName = buildIndexName(baseName);

        log.info("开始导入Excel：file={}, tableName={}, indexName={}, sheet={}",
                file.getOriginalFilename(), tableName, indexName, parsedData.getSheetName());

        ensureMetadataTable();

        boolean tableExists = checkTableExists(tableName);
        if (tableExists && !cover) {
            throw new IllegalStateException("目标表已存在，如需覆盖请开启覆盖模式");
        }

        if (tableExists) {
            dropTableQuietly(tableName);
        }
        deleteMetadata(tableName);
        deleteIndexQuietly(indexName);
        columnLabelCache.remove(tableName);
        displayNameCache.remove(tableName);

        createTable(tableName, parsedData.getColumns());
        batchInsert(tableName, parsedData.getColumns(), parsedData.getRows());
        upsertMetadata(tableName, indexName, baseName, parsedData);

        recreateIndex(indexName);
        bulkIndex(indexName, tableName, baseName, parsedData);

        ExcelImportResult result = new ExcelImportResult();
        result.setTableName(tableName);
        result.setIndexName(indexName);
        result.setDisplayName(baseName);
        result.setSheetName(parsedData.getSheetName());
        result.setRowCount(parsedData.getRows().size());
        result.setImportTime(LocalDateTime.now());
        result.setColumnLabels(parsedData.getColumnLabelMap());

        columnLabelCache.put(tableName, parsedData.getColumnLabelMap());
        displayNameCache.put(tableName, baseName);

        log.info("Excel 导入完成：tableName={}, rowCount={}", tableName, parsedData.getRows().size());
        return result;
    }

    /**
     * 查询所有导入记录。
     */
    public List<ExcelImportMetadata> listImports() {
        ensureMetadataTable();

        String sql = "SELECT ID, TABLE_NAME, INDEX_NAME, DISPLAY_NAME, SHEET_NAME, COLUMN_INFO, ROW_COUNT, IMPORT_TIME " +
                     "FROM " + META_TABLE + " ORDER BY IMPORT_TIME DESC";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        List<ExcelImportMetadata> result = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            ExcelImportMetadata metadata = new ExcelImportMetadata();
            metadata.setId(row.get("ID") != null ? ((Number) row.get("ID")).longValue() : null);
            metadata.setTableName((String) row.get("TABLE_NAME"));
            metadata.setIndexName((String) row.get("INDEX_NAME"));
            metadata.setDisplayName((String) row.get("DISPLAY_NAME"));
            metadata.setSheetName((String) row.get("SHEET_NAME"));
            metadata.setRowCount(row.get("ROW_COUNT") != null ? ((Number) row.get("ROW_COUNT")).intValue() : 0);

            Object importTimeObj = row.get("IMPORT_TIME");
            if (importTimeObj instanceof Timestamp) {
                metadata.setImportTime(((Timestamp) importTimeObj).toLocalDateTime());
            } else if (importTimeObj instanceof LocalDateTime) {
                metadata.setImportTime((LocalDateTime) importTimeObj);
            }

            String columnInfo = readClob(row.get("COLUMN_INFO"));
            if (StringUtils.isNotBlank(columnInfo)) {
                try {
                    Map<String, String> columnLabels = objectMapper.readValue(columnInfo,
                            new TypeReference<LinkedHashMap<String, String>>() {});
                    metadata.setColumnLabels(columnLabels);
                    if (metadata.getTableName() != null) {
                        columnLabelCache.putIfAbsent(metadata.getTableName(), columnLabels);
                    }
                } catch (IOException e) {
                    log.warn("解析列信息失败: tableName={}, error={}", metadata.getTableName(), e.getMessage());
                }
            }

            if (metadata.getTableName() != null && metadata.getDisplayName() != null) {
                displayNameCache.putIfAbsent(metadata.getTableName(), metadata.getDisplayName());
            }

            result.add(metadata);
        }

        return result;
    }

    /**
     * 根据表名获取显示名称。
     */
    public String getDisplayName(String tableName) {
        if (tableName == null) {
            return null;
        }
        return displayNameCache.computeIfAbsent(tableName, this::loadDisplayNameFromDb);
    }

    /**
     * 获取列标签映射。
     */
    public Map<String, String> getColumnLabels(String tableName) {
        if (tableName == null) {
            return Collections.emptyMap();
        }
        return columnLabelCache.computeIfAbsent(tableName, this::loadColumnLabelsFromDb);
    }

    private String loadDisplayNameFromDb(String tableName) {
        try {
            String sql = "SELECT DISPLAY_NAME FROM " + META_TABLE + " WHERE TABLE_NAME = ?";
            return jdbcTemplate.queryForObject(sql, String.class, tableName);
        } catch (EmptyResultDataAccessException ex) {
            return tableName;
        }
    }

    private Map<String, String> loadColumnLabelsFromDb(String tableName) {
        try {
            String sql = "SELECT COLUMN_INFO FROM " + META_TABLE + " WHERE TABLE_NAME = ?";
            String columnInfo = jdbcTemplate.queryForObject(sql, String.class, tableName);
            if (StringUtils.isBlank(columnInfo)) {
                return Collections.emptyMap();
            }
            return objectMapper.readValue(columnInfo, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (EmptyResultDataAccessException ex) {
            return Collections.emptyMap();
        } catch (IOException e) {
            log.warn("解析列信息失败: tableName={}, error={}", tableName, e.getMessage());
            return Collections.emptyMap();
        }
    }

    private ExcelParsedData parseExcel(MultipartFile file, String requestedSheetName) throws IOException {
        try (InputStream inputStream = file.getInputStream(); Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = StringUtils.isNotBlank(requestedSheetName)
                    ? workbook.getSheet(requestedSheetName)
                    : workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;

            if (sheet == null) {
                throw new IllegalArgumentException("未找到要导入的工作表");
            }

            Row tableNameRow = sheet.getRow(0);
            String tableDisplayName = extractTableDisplayName(tableNameRow);

            Row headerRow = sheet.getRow(1);
            if (headerRow == null) {
                throw new IllegalArgumentException("Excel第二行必须包含字段名");
            }

            List<ExcelColumn> columns = parseHeader(headerRow);
            List<Map<String, String>> rows = parseRows(sheet, columns, 2);

            if (rows.isEmpty()) {
                throw new IllegalArgumentException("Excel中没有检测到有效数据");
            }

            ExcelParsedData data = new ExcelParsedData();
            data.setColumns(columns);
            data.setRows(rows);
            data.setSheetName(sheet.getSheetName());
            if (StringUtils.isBlank(tableDisplayName)) {
                tableDisplayName = extractDisplayName(file);
            }
            data.setDisplayName(tableDisplayName);

            return data;

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析Excel失败", e);
            throw new IllegalArgumentException("Excel解析失败: " + e.getMessage());
        }
    }

    private List<ExcelColumn> parseHeader(Row headerRow) {
        List<ExcelColumn> columns = new ArrayList<>();
        Map<String, Integer> nameCounter = new HashMap<>();

        short lastCellNum = headerRow.getLastCellNum();
        if (lastCellNum <= 0) {
            throw new IllegalArgumentException("Excel首行缺少列定义");
        }

        for (int i = 0; i < lastCellNum; i++) {
            Cell cell = headerRow.getCell(i, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            String header = StringUtils.trimToEmpty(dataFormatter.formatCellValue(cell));
            if (header.isEmpty()) {
                header = "列" + (i + 1);
            }

            String columnName = sanitizeColumnName(header, i, nameCounter);
            columns.add(new ExcelColumn(columnName, header));
        }

        return columns;
    }

    private List<Map<String, String>> parseRows(Sheet sheet, List<ExcelColumn> columns, int startRowIndex) {
        List<Map<String, String>> rows = new ArrayList<>();

        for (int rowIndex = startRowIndex; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }

            Map<String, String> rowData = new LinkedHashMap<>();
            boolean hasValue = false;

            for (int cellIndex = 0; cellIndex < columns.size(); cellIndex++) {
                Cell cell = row.getCell(cellIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String value = cell != null ? StringUtils.trimToEmpty(dataFormatter.formatCellValue(cell)) : "";
                if (!value.isEmpty()) {
                    hasValue = true;
                }
                rowData.put(columns.get(cellIndex).getColumnName(), value);
            }

            if (hasValue) {
                rows.add(rowData);
            }
        }

        return rows;
    }

    private String extractTableDisplayName(Row row) {
        if (row == null) {
            return null;
        }
        short lastCellNum = row.getLastCellNum();
        if (lastCellNum < 0) {
            return null;
        }
        for (int i = 0; i < lastCellNum; i++) {
            Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell == null) {
                continue;
            }
            String value = StringUtils.trimToEmpty(dataFormatter.formatCellValue(cell));
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private void createTable(String tableName, List<ExcelColumn> columns) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(tableName).append(" (\n")
          .append("    ID BIGINT IDENTITY(1,1) PRIMARY KEY,\n");

        for (int i = 0; i < columns.size(); i++) {
            ExcelColumn column = columns.get(i);
            sb.append("    ").append(column.getColumnName()).append(" VARCHAR(2000)");
            if (i < columns.size() - 1) {
                sb.append(",\n");
            } else {
                sb.append("\n");
            }
        }
        sb.append(")");

        jdbcTemplate.execute(sb.toString());

        try {
            String indexSql = "CREATE INDEX IDX_" + tableName + "_ID ON " + tableName + " (ID)";
            jdbcTemplate.execute(indexSql);
        } catch (Exception e) {
            log.warn("创建ID索引失败: tableName={}, error={}", tableName, e.getMessage());
        }
    }

    private void batchInsert(String tableName, List<ExcelColumn> columns, List<Map<String, String>> rows) {
        if (rows.isEmpty()) {
            return;
        }

        String columnNames = buildColumnList(columns);
        String placeholders = buildPlaceholders(columns.size());
        String insertSql = "INSERT INTO " + tableName + " (" + columnNames + ") VALUES (" + placeholders + ")";

        List<Object[]> batch = new ArrayList<>();
        int counter = 0;

        for (Map<String, String> row : rows) {
            Object[] values = new Object[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                String columnName = columns.get(i).getColumnName();
                values[i] = StringUtils.defaultString(row.get(columnName));
            }
            batch.add(values);
            counter++;

            if (batch.size() >= DB_BATCH_SIZE) {
                jdbcTemplate.batchUpdate(insertSql, batch);
                batch.clear();
            }
        }

        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(insertSql, batch);
        }

        log.info("已向表 {} 写入 {} 行数据", tableName, counter);
    }

    private void upsertMetadata(String tableName, String indexName, String displayName, ExcelParsedData parsedData) {
        try {
            String columnJson = objectMapper.writeValueAsString(parsedData.getColumnLabelMap());
            LocalDateTime now = LocalDateTime.now();

            String sql = "INSERT INTO " + META_TABLE +
                    " (TABLE_NAME, INDEX_NAME, DISPLAY_NAME, SHEET_NAME, COLUMN_INFO, ROW_COUNT, IMPORT_TIME) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)";

            jdbcTemplate.update(sql,
                    tableName,
                    indexName,
                    displayName,
                    parsedData.getSheetName(),
                    columnJson,
                    parsedData.getRows().size(),
                    Timestamp.valueOf(now));

        } catch (Exception e) {
            log.error("写入Excel导入元数据失败", e);
            throw new IllegalStateException("写入导入元数据失败: " + e.getMessage());
        }
    }

    private void recreateIndex(String indexName) {
        deleteIndexQuietly(indexName);

        try {
            CreateIndexRequest request = new CreateIndexRequest(indexName);
            request.settings(Settings.builder()
                    .put("index.number_of_shards", 1)
                    .put("index.number_of_replicas", 0));

            request.mapping("{" +
                    "\"dynamic_templates\":[{" +
                    "\"strings\":{\"match_mapping_type\":\"string\",\"mapping\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\",\"ignore_above\":256}}}}}" +
                    "]," +
                    "\"properties\":{" +
                    "\"table_name\":{\"type\":\"keyword\"}," +
                    "\"excel_name\":{\"type\":\"keyword\"}," +
                    "\"sheet_name\":{\"type\":\"keyword\"}," +
                    "\"source_type\":{\"type\":\"keyword\"}," +
                    "\"record_id\":{\"type\":\"keyword\"}," +
                    "\"sync_time\":{\"type\":\"date\",\"format\":\"strict_date_optional_time||epoch_millis\"}" +
                    "}}",
                    XContentType.JSON);

            esClient.indices().create(request, RequestOptions.DEFAULT);

        } catch (Exception e) {
            log.error("创建索引失败: indexName={}", indexName, e);
            throw new IllegalStateException("创建索引失败: " + e.getMessage());
        }
    }

    private void bulkIndex(String indexName,
                           String tableName,
                           String displayName,
                           ExcelParsedData parsedData) {
        BulkRequest bulkRequest = new BulkRequest();
        bulkRequest.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);

        Map<String, String> labelMap = parsedData.getColumnLabelMap();
        LocalDateTime now = LocalDateTime.now();
        int recordId = 1;

        for (Map<String, String> row : parsedData.getRows()) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("source_type", "excel");
            doc.put("table_name", tableName);
            doc.put("excel_name", displayName);
            doc.put("sheet_name", parsedData.getSheetName());
            doc.put("record_id", recordId);
            doc.put("sync_time", now.format(ES_TIME_FORMATTER));

            int primaryCounter = 0;
            for (ExcelColumn column : parsedData.getColumns()) {
                String columnName = column.getColumnName();
                String header = column.getHeader();
                String value = StringUtils.defaultString(row.get(columnName));

                if (StringUtils.isBlank(value)) {
                    continue;
                }

                if (isSystemField(header)) {
                    // 避免覆盖系统字段
                    header = header + "_字段";
                }

                doc.put(header, value);

                if (primaryCounter < 6) {
                    doc.put("_primary_field_" + primaryCounter, header);
                    doc.put("_primary_value_" + primaryCounter, value);
                    primaryCounter++;
                }
            }

            doc.put("column_labels", labelMap);

            bulkRequest.add(new IndexRequest(indexName)
                    .id(tableName + "-" + recordId)
                    .source(doc, XContentType.JSON));

            if (bulkRequest.numberOfActions() >= ES_BATCH_SIZE) {
                executeBulk(bulkRequest);
                bulkRequest = new BulkRequest().setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);
            }

            recordId++;
        }

        if (bulkRequest.numberOfActions() > 0) {
            executeBulk(bulkRequest);
        }
    }

    private void executeBulk(BulkRequest bulkRequest) {
        try {
            BulkResponse response = esClient.bulk(bulkRequest, RequestOptions.DEFAULT);
            if (response.hasFailures()) {
                throw new IllegalStateException("ES批量写入失败: " + response.buildFailureMessage());
            }
        } catch (Exception e) {
            log.error("ES批量写入失败", e);
            throw new IllegalStateException("ES批量写入失败: " + e.getMessage());
        }
    }

    private void ensureMetadataTable() {
        if (checkTableExists(META_TABLE)) {
            return;
        }

        String sql = "CREATE TABLE " + META_TABLE + " (\n" +
                "    ID BIGINT IDENTITY(1,1) PRIMARY KEY,\n" +
                "    TABLE_NAME VARCHAR(128) NOT NULL,\n" +
                "    INDEX_NAME VARCHAR(128) NOT NULL,\n" +
                "    DISPLAY_NAME VARCHAR(256),\n" +
                "    SHEET_NAME VARCHAR(128),\n" +
                "    COLUMN_INFO CLOB,\n" +
                "    ROW_COUNT INT,\n" +
                "    IMPORT_TIME TIMESTAMP DEFAULT CURRENT_TIMESTAMP\n" +
                ")";

        jdbcTemplate.execute(sql);

        try {
            String indexSql = "CREATE UNIQUE INDEX IDX_" + META_TABLE + "_TABLE ON " + META_TABLE + " (TABLE_NAME)";
            jdbcTemplate.execute(indexSql);
        } catch (Exception e) {
            log.warn("创建元数据索引失败: {}", e.getMessage());
        }
    }

    private boolean checkTableExists(String tableName) {
        String sql = "SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, tableName.toUpperCase(Locale.ROOT));
        return count != null && count > 0;
    }

    private void dropTableQuietly(String tableName) {
        try {
            jdbcTemplate.execute("DROP TABLE " + tableName);
        } catch (Exception e) {
            log.warn("删除表失败(忽略): tableName={}, error={}", tableName, e.getMessage());
        }
    }

    private void deleteMetadata(String tableName) {
        String sql = "DELETE FROM " + META_TABLE + " WHERE TABLE_NAME = ?";
        try {
            jdbcTemplate.update(sql, tableName);
        } catch (Exception e) {
            log.warn("删除元数据失败(忽略): tableName={}, error={}", tableName, e.getMessage());
        }
    }

    private void deleteIndexQuietly(String indexName) {
        try {
            boolean exists = esClient.indices().exists(new GetIndexRequest(indexName), RequestOptions.DEFAULT);
            if (exists) {
                Request request = new Request("DELETE", "/" + indexName);
                esClient.getLowLevelClient().performRequest(request);
            }
        } catch (Exception e) {
            log.warn("删除索引失败(忽略): indexName={}, error={}", indexName, e.getMessage());
        }
    }

    private String buildTableName(String baseName) {
        String sanitized = sanitizeIdentifier(baseName, "EXCEL_TABLE");
        return "EXCEL_" + sanitized;
    }

    private String buildIndexName(String baseName) {
        String sanitized = sanitizeIdentifier(baseName, "excel_index");
        return "excel_" + sanitized.toLowerCase(Locale.ROOT);
    }

    private String sanitizeIdentifier(String input, String defaultValue) {
        String name = StringUtils.defaultIfBlank(input, defaultValue);
        String sanitized = name.replaceAll("[^\\p{Alnum}]+", "_");
        sanitized = sanitized.replaceAll("_+", "_");
        sanitized = sanitized.replaceAll("^_+|_+$", "");

        if (sanitized.isEmpty()) {
            sanitized = defaultValue + "_" + Integer.toUnsignedString(name.hashCode(), 16);
        }
        if (!Character.isLetter(sanitized.charAt(0))) {
            sanitized = "T_" + sanitized;
        }
        if (sanitized.length() > 40) {
            sanitized = sanitized.substring(0, 40);
        }
        return sanitized.toUpperCase(Locale.ROOT);
    }

    private String sanitizeColumnName(String header, int index, Map<String, Integer> counter) {
        String base = header.replaceAll("[^\\p{IsAlphabetic}0-9]+", "_");
        base = base.replaceAll("_+", "_");
        base = base.replaceAll("^_+|_+$", "");
        if (StringUtils.isBlank(base)) {
            base = "COLUMN_" + (index + 1);
        }
        if (!Character.isLetter(base.charAt(0))) {
            base = "C_" + base;
        }

        base = base.toUpperCase(Locale.ROOT);

        if (RESERVED_COLUMNS.contains(base)) {
            base = base + "_C";
        }

        int seen = counter.getOrDefault(base, 0);
        counter.put(base, seen + 1);

        if (seen > 0) {
            return base + "_" + (seen + 1);
        }
        return base;
    }

    private boolean isSystemField(String name) {
        return SYSTEM_FIELDS.contains(name.toLowerCase(Locale.ROOT));
    }

    private String buildColumnList(List<ExcelColumn> columns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            sb.append(columns.get(i).getColumnName());
            if (i < columns.size() - 1) {
                sb.append(", ");
            }
        }
        return sb.toString();
    }

    private String buildPlaceholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append("?");
            if (i < count - 1) {
                sb.append(", ");
            }
        }
        return sb.toString();
    }

    private String extractDisplayName(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (StringUtils.isBlank(filename)) {
            return "Excel导入";
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private String readClob(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof Clob) {
            try {
                Clob clob = (Clob) value;
                StringBuilder sb = new StringBuilder();
                try (Reader reader = clob.getCharacterStream(); BufferedReader br = new BufferedReader(reader)) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }
                }
                return sb.toString();
            } catch (SQLException | IOException e) {
                log.warn("读取CLOB失败", e);
            }
        }
        return value.toString();
    }

    private static final Set<String> SYSTEM_FIELDS = Set.of(
            "table_name", "excel_name", "sheet_name", "record_id", "sync_time", "source_type"
    );

    private static final Set<String> RESERVED_COLUMNS = Set.of(
            "ID", "SELECT", "WHERE", "GROUP", "ORDER", "TABLE", "INDEX", "DATE", "FROM", "TO", "AND", "OR"
    );

    private static class ExcelParsedData {
        private List<ExcelColumn> columns;
        private List<Map<String, String>> rows;
        private String sheetName;
        private String displayName;

        public List<ExcelColumn> getColumns() {
            return columns;
        }

        public void setColumns(List<ExcelColumn> columns) {
            this.columns = columns;
        }

        public List<Map<String, String>> getRows() {
            return rows;
        }

        public void setRows(List<Map<String, String>> rows) {
            this.rows = rows;
        }

        public String getSheetName() {
            return sheetName;
        }

        public void setSheetName(String sheetName) {
            this.sheetName = sheetName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public Map<String, String> getColumnLabelMap() {
            LinkedHashMap<String, String> map = new LinkedHashMap<>();
            if (columns != null) {
                for (ExcelColumn column : columns) {
                    map.put(column.getColumnName(), column.getHeader());
                }
            }
            return map;
        }
    }

    private static class ExcelColumn {
        private final String columnName;
        private final String header;

        ExcelColumn(String columnName, String header) {
            this.columnName = columnName;
            this.header = header;
        }

        public String getColumnName() {
            return columnName;
        }

        public String getHeader() {
            return header;
        }
    }
}
