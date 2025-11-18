package com.esadmin.service;

import com.esadmin.dto.ExcelImportMetadata;
import com.esadmin.dto.ExcelImportResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.*;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB
    private static final int MAX_ROWS = 100000; // 最大行数限制
    private static final int STREAMING_BATCH_SIZE = 1000; // 流式处理批量大小
    private static final long LARGE_FILE_THRESHOLD = 10 * 1024 * 1024; // 大文件阈值10MB

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

        // 检查文件大小
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(String.format("文件大小超过限制，最大支持 %d MB", MAX_FILE_SIZE / 1024 / 1024));
        }

        // 根据文件大小选择处理方式
        ExcelParsedData parsedData;
        if (file.getSize() > LARGE_FILE_THRESHOLD) {
            log.info("检测到大文件({} bytes)，使用流式处理", file.getSize());
            parsedData = parseExcelStreaming(file, requestedSheetName);
        } else {
            parsedData = parseExcel(file, requestedSheetName);
        }

        String baseName = StringUtils.defaultIfBlank(customName, parsedData.getDisplayName());
        String tableName = buildTableName(baseName);
        String indexName = buildIndexName(baseName);

        log.info("开始导入Excel：file={}, tableName={}, indexName={}, sheet={}",
                file.getOriginalFilename(), tableName, indexName, parsedData.getSheetName());

        ensureMetadataTable();

        boolean tableExists = checkTableExists(tableName);
        if (tableExists && !cover) {
            throw new IllegalArgumentException("表名重复：'" + baseName + "' 对应的表已存在，请修改名称或开启覆盖模式");
        }

        if (tableExists) {
            dropTableQuietly(tableName);
        }
        deleteMetadata(tableName);
        deleteIndexQuietly(indexName);
        columnLabelCache.remove(tableName);
        displayNameCache.remove(tableName);

        createTable(tableName, parsedData.getColumns());
        
        // 根据数据量选择插入方式
        if (parsedData.getRows().size() > STREAMING_BATCH_SIZE * 2) {
            log.info("数据量较大({}行)，使用流式批量插入", parsedData.getRows().size());
            streamingBatchInsert(tableName, parsedData.getColumns(), parsedData.getRows());
        } else {
            batchInsert(tableName, parsedData.getColumns(), parsedData.getRows());
        }
        
        upsertMetadata(tableName, indexName, baseName, parsedData);

        recreateIndex(indexName);
        
        // 根据数据量选择ES索引方式
        if (parsedData.getRows().size() > ES_BATCH_SIZE * 2) {
            log.info("数据量较大({}行)，使用流式ES索引", parsedData.getRows().size());
            streamingBulkIndex(indexName, tableName, baseName, parsedData);
        } else {
            bulkIndex(indexName, tableName, baseName, parsedData);
        }

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

    /**
     * 删除Excel导入的表和相关数据。
     */
    public void deleteImport(String tableName) {
        if (StringUtils.isBlank(tableName)) {
            throw new IllegalArgumentException("表名不能为空");
        }

        log.info("开始删除Excel导入表: {}", tableName);

        // 获取索引名
        String indexName = null;
        try {
            String sql = "SELECT INDEX_NAME FROM " + META_TABLE + " WHERE TABLE_NAME = ?";
            indexName = jdbcTemplate.queryForObject(sql, String.class, tableName);
        } catch (EmptyResultDataAccessException e) {
            log.warn("未找到表的元数据信息: {}", tableName);
        }

        // 删除数据库表
        if (checkTableExists(tableName)) {
            try {
                jdbcTemplate.execute("DROP TABLE " + tableName);
                log.info("已删除数据库表: {}", tableName);
            } catch (Exception e) {
                log.error("删除数据库表失败: tableName={}, error={}", tableName, e.getMessage());
                throw new IllegalStateException("删除数据库表失败: " + e.getMessage());
            }
        }

        // 删除ES索引
        if (StringUtils.isNotBlank(indexName)) {
            try {
                deleteIndexQuietly(indexName);
                log.info("已删除ES索引: {}", indexName);
            } catch (Exception e) {
                log.warn("删除ES索引失败(忽略): indexName={}, error={}", indexName, e.getMessage());
            }
        }

        // 删除元数据
        try {
            String sql = "DELETE FROM " + META_TABLE + " WHERE TABLE_NAME = ?";
            int deletedRows = jdbcTemplate.update(sql, tableName);
            if (deletedRows > 0) {
                log.info("已删除元数据记录: {}", tableName);
            } else {
                log.warn("未找到要删除的元数据记录: {}", tableName);
            }
        } catch (Exception e) {
            log.error("删除元数据失败: tableName={}, error={}", tableName, e.getMessage());
            throw new IllegalStateException("删除元数据失败: " + e.getMessage());
        }

        // 清除缓存
        columnLabelCache.remove(tableName);
        displayNameCache.remove(tableName);

        log.info("Excel导入表删除完成: {}", tableName);
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
        String filename = file.getOriginalFilename();
        if (filename != null && (filename.toLowerCase().endsWith(".csv"))) {
            return parseCsv(file);
        }
        
        try (InputStream inputStream = file.getInputStream()) {
            // 设置POI的内存限制以防止OOM
            System.setProperty("poi.xssf.shared.strings.read.request.limit", "50000000");
            Workbook workbook = WorkbookFactory.create(inputStream);
            Sheet sheet = StringUtils.isNotBlank(requestedSheetName)
                    ? workbook.getSheet(requestedSheetName)
                    : workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;

            if (sheet == null) {
                throw new IllegalArgumentException("未找到要导入的工作表");
            }

            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new IllegalArgumentException("Excel第一行必须包含字段名");
            }

            List<ExcelColumn> columns = parseHeader(headerRow);
            
            // 检查行数限制
            if (sheet.getLastRowNum() > MAX_ROWS) {
                throw new IllegalArgumentException(String.format("文件行数超过限制，最大支持 %d 行数据", MAX_ROWS));
            }
            
            List<Map<String, String>> rows = parseRows(sheet, columns, 1);

            if (rows.isEmpty()) {
                throw new IllegalArgumentException("Excel中没有检测到有效数据");
            }
            
            // 二次检查实际解析行数
            if (rows.size() > MAX_ROWS) {
                throw new IllegalArgumentException(String.format("实际数据行数超过限制，最大支持 %d 行数据", MAX_ROWS));
            }

            ExcelParsedData data = new ExcelParsedData();
            data.setColumns(columns);
            data.setRows(rows);
            data.setSheetName(sheet.getSheetName());
            data.setDisplayName(extractDisplayName(file));

            try {
                workbook.close();
            } catch (IOException e) {
                log.warn("关闭工作簿失败: {}", e.getMessage());
            }

            return data;

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (org.apache.poi.ooxml.POIXMLException e) {
            if (e.getMessage() != null && e.getMessage().contains("array of length")) {
                log.error("Excel文件包含过大数据导致内存溢出", e);
                throw new IllegalArgumentException("文件过大或包含异常数据，请减小文件大小后重试");
            }
            log.error("Excel解析POI异常", e);
            throw new IllegalArgumentException("Excel解析失败: " + e.getMessage());
        } catch (OutOfMemoryError e) {
            log.error("Excel解析内存不足", e);
            throw new IllegalArgumentException("文件过大导致内存不足，请减小文件大小后重试");
        } catch (Exception e) {
            log.error("解析Excel失败", e);
            throw new IllegalArgumentException("Excel解析失败: " + e.getMessage());
        }
    }

    /**
     * 流式解析Excel文件，适用于大文件处理
     */
    private ExcelParsedData parseExcelStreaming(MultipartFile file, String requestedSheetName) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename != null && (filename.toLowerCase().endsWith(".csv"))) {
            return parseCsvStreaming(file);
        }
        
        try (InputStream inputStream = file.getInputStream()) {
            // 设置POI的内存限制
            System.setProperty("poi.xssf.shared.strings.read.request.limit", "50000000");
            
            // 使用XSSF事件模式进行流式读取
            if (filename != null && filename.toLowerCase().endsWith(".xlsx")) {
                return parseXlsxStreaming(inputStream, requestedSheetName, file);
            } else {
                // 对于.xls文件，仍使用常规方式但分批处理
                Workbook workbook = WorkbookFactory.create(inputStream);
                try {
                    return parseWorkbookInBatches(workbook, requestedSheetName, file);
                } finally {
                    workbook.close();
                }
            }
            
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (org.apache.poi.ooxml.POIXMLException e) {
            if (e.getMessage() != null && e.getMessage().contains("array of length")) {
                log.error("Excel文件包含过大数据导致内存溢出", e);
                throw new IllegalArgumentException("文件过大或包含异常数据，请减小文件大小后重试");
            }
            log.error("Excel解析POI异常", e);
            throw new IllegalArgumentException("Excel解析失败: " + e.getMessage());
        } catch (OutOfMemoryError e) {
            log.error("Excel解析内存不足", e);
            throw new IllegalArgumentException("文件过大导致内存不足，请减小文件大小后重试");
        } catch (Exception e) {
            log.error("解析Excel失败", e);
            throw new IllegalArgumentException("Excel解析失败: " + e.getMessage());
        }
    }

    /**
     * 使用事件模式解析XLSX文件
     */
    private ExcelParsedData parseXlsxStreaming(InputStream inputStream, String requestedSheetName, MultipartFile file) throws IOException {
        // 由于XSSF事件模式实现复杂，这里先用分批方式处理
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            return parseWorkbookInBatches(workbook, requestedSheetName, file);
        } catch (Exception e) {
            log.error("XLSX流式解析失败", e);
            throw new IllegalArgumentException("XLSX解析失败: " + e.getMessage());
        }
    }

    /**
     * 分批处理工作簿数据
     */
    private ExcelParsedData parseWorkbookInBatches(Workbook workbook, String requestedSheetName, MultipartFile file) {
        Sheet sheet = StringUtils.isNotBlank(requestedSheetName)
                ? workbook.getSheet(requestedSheetName)
                : workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;

        if (sheet == null) {
            throw new IllegalArgumentException("未找到要导入的工作表");
        }

        Row headerRow = sheet.getRow(0);
        if (headerRow == null) {
            throw new IllegalArgumentException("Excel第一行必须包含字段名");
        }

        List<ExcelColumn> columns = parseHeader(headerRow);
        
        // 检查行数限制
        int totalRows = sheet.getLastRowNum();
        if (totalRows > MAX_ROWS) {
            throw new IllegalArgumentException(String.format("文件行数超过限制，最大支持 %d 行数据", MAX_ROWS));
        }

        log.info("开始分批解析Excel，总行数: {}, 批量大小: {}", totalRows, STREAMING_BATCH_SIZE);

        List<Map<String, String>> allRows = new ArrayList<>();
        int processedRows = 0;
        
        // 分批读取数据行
        for (int startRow = 1; startRow <= totalRows; startRow += STREAMING_BATCH_SIZE) {
            int endRow = Math.min(startRow + STREAMING_BATCH_SIZE - 1, totalRows);
            
            List<Map<String, String>> batchRows = parseRowsBatch(sheet, columns, startRow, endRow);
            allRows.addAll(batchRows);
            processedRows += batchRows.size();
            
            log.debug("已处理批次: 行 {}-{}, 有效数据: {} 行", startRow, endRow, batchRows.size());
            
            // 强制垃圾回收释放内存
            if (processedRows % (STREAMING_BATCH_SIZE * 5) == 0) {
                System.gc();
            }
        }

        if (allRows.isEmpty()) {
            throw new IllegalArgumentException("Excel中没有检测到有效数据");
        }

        log.info("Excel分批解析完成，总有效数据行数: {}", allRows.size());

        ExcelParsedData data = new ExcelParsedData();
        data.setColumns(columns);
        data.setRows(allRows);
        data.setSheetName(sheet.getSheetName());
        data.setDisplayName(extractDisplayName(file));

        return data;
    }

    /**
     * 分批解析行数据
     */
    private List<Map<String, String>> parseRowsBatch(Sheet sheet, List<ExcelColumn> columns, int startRow, int endRow) {
        List<Map<String, String>> rows = new ArrayList<>();

        for (int rowIndex = startRow; rowIndex <= endRow; rowIndex++) {
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

    /**
     * 流式解析CSV文件
     */
    private ExcelParsedData parseCsvStreaming(MultipartFile file) throws IOException {
        String[] encodings = {"UTF-8", "GBK", "GB2312", "ISO-8859-1"};
        Exception lastException = null;
        
        for (String encoding : encodings) {
            try (InputStream inputStream = file.getInputStream();
                 InputStreamReader reader = new InputStreamReader(inputStream, encoding);
                 CSVReader csvReader = new CSVReaderBuilder(reader).build()) {
            
                String[] headers = csvReader.readNext();
                if (headers == null || headers.length == 0) {
                    throw new IllegalArgumentException("CSV文件为空");
                }
                
                // 检查编码
                boolean hasValidChinese = false;
                for (String header : headers) {
                    if (header != null && isValidChinese(header)) {
                        hasValidChinese = true;
                        break;
                    }
                }
                
                if (!hasValidChinese && !encoding.equals("ISO-8859-1")) {
                    log.debug("编码 {} 可能存在乱码，尝试下一个编码", encoding);
                    continue;
                }
                
                List<ExcelColumn> columns = new ArrayList<>();
                Map<String, Integer> nameCounter = new HashMap<>();
                
                for (int i = 0; i < headers.length; i++) {
                    String header = StringUtils.trimToEmpty(headers[i]);
                    if (header.isEmpty()) {
                        header = "列" + (i + 1);
                    }
                    String columnName = sanitizeColumnName(header, i, nameCounter);
                    columns.add(new ExcelColumn(columnName, header));
                }
                
                // 流式读取CSV数据
                List<Map<String, String>> allRows = new ArrayList<>();
                String[] line;
                int rowCount = 0;
                
                while ((line = csvReader.readNext()) != null && rowCount < MAX_ROWS) {
                    Map<String, String> rowData = new LinkedHashMap<>();
                    boolean hasValue = false;
                    
                    for (int cellIndex = 0; cellIndex < columns.size(); cellIndex++) {
                        String value = "";
                        if (cellIndex < line.length) {
                            value = StringUtils.trimToEmpty(line[cellIndex]);
                        }
                        if (!value.isEmpty()) {
                            hasValue = true;
                        }
                        rowData.put(columns.get(cellIndex).getColumnName(), value);
                    }
                    
                    if (hasValue) {
                        allRows.add(rowData);
                        rowCount++;
                    }
                    
                    // 定期释放内存
                    if (rowCount % (STREAMING_BATCH_SIZE * 10) == 0) {
                        System.gc();
                        log.debug("CSV流式处理进度: {} 行", rowCount);
                    }
                }
                
                if (allRows.isEmpty()) {
                    throw new IllegalArgumentException("CSV中没有检测到有效数据");
                }
                
                if (rowCount >= MAX_ROWS) {
                    log.warn("CSV数据行数达到限制 {} 行，已截断处理", MAX_ROWS);
                }
                
                ExcelParsedData data = new ExcelParsedData();
                data.setColumns(columns);
                data.setRows(allRows);
                data.setSheetName("CSV");
                data.setDisplayName(extractDisplayName(file));
                
                log.info("CSV流式解析成功，使用编码: {}, 处理行数: {}", encoding, allRows.size());
                return data;
                
            } catch (Exception e) {
                lastException = e;
                log.debug("编码 {} 解析失败: {}", encoding, e.getMessage());
            }
        }
        
        log.error("所有编码尝试均失败", lastException);
        throw new IllegalArgumentException("CSV解析失败，无法识别文件编码: " + 
            (lastException != null ? lastException.getMessage() : "未知错误"));
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

    private ExcelParsedData parseCsv(MultipartFile file) throws IOException {
        // 尝试多种字符编码
        String[] encodings = {"UTF-8", "GBK", "GB2312", "ISO-8859-1"};
        Exception lastException = null;
        
        for (String encoding : encodings) {
            try (InputStream inputStream = file.getInputStream();
                 InputStreamReader reader = new InputStreamReader(inputStream, encoding);
                 CSVReader csvReader = new CSVReaderBuilder(reader).build()) {
            
                List<String[]> allData = csvReader.readAll();
                if (allData.isEmpty()) {
                    throw new IllegalArgumentException("CSV文件为空");
                }
                
                String[] headers = allData.get(0);
                
                // 检查是否有中文乱码
                boolean hasValidChinese = false;
                for (String header : headers) {
                    if (header != null && isValidChinese(header)) {
                        hasValidChinese = true;
                        break;
                    }
                }
                
                // 如果检测到乱码且不是UTF-8编码，继续尝试下一个编码
                if (!hasValidChinese && !encoding.equals("ISO-8859-1")) {
                    log.debug("编码 {} 可能存在乱码，尝试下一个编码", encoding);
                    continue;
                }
                
                List<ExcelColumn> columns = new ArrayList<>();
                Map<String, Integer> nameCounter = new HashMap<>();
                
                for (int i = 0; i < headers.length; i++) {
                    String header = StringUtils.trimToEmpty(headers[i]);
                    if (header.isEmpty()) {
                        header = "列" + (i + 1);
                    }
                    String columnName = sanitizeColumnName(header, i, nameCounter);
                    columns.add(new ExcelColumn(columnName, header));
                }
                
                List<Map<String, String>> rows = new ArrayList<>();
                for (int rowIndex = 1; rowIndex < allData.size(); rowIndex++) {
                    String[] row = allData.get(rowIndex);
                    Map<String, String> rowData = new LinkedHashMap<>();
                    boolean hasValue = false;
                    
                    for (int cellIndex = 0; cellIndex < columns.size(); cellIndex++) {
                        String value = "";
                        if (cellIndex < row.length) {
                            value = StringUtils.trimToEmpty(row[cellIndex]);
                        }
                        if (!value.isEmpty()) {
                            hasValue = true;
                        }
                        rowData.put(columns.get(cellIndex).getColumnName(), value);
                    }
                    
                    if (hasValue) {
                        rows.add(rowData);
                    }
                }
                
                if (rows.isEmpty()) {
                    throw new IllegalArgumentException("CSV中没有检测到有效数据");
                }
                
                // 检查CSV行数限制
                if (rows.size() > MAX_ROWS) {
                    throw new IllegalArgumentException(String.format("CSV数据行数超过限制，最大支持 %d 行数据", MAX_ROWS));
                }
                
                ExcelParsedData data = new ExcelParsedData();
                data.setColumns(columns);
                data.setRows(rows);
                data.setSheetName("CSV");
                data.setDisplayName(extractDisplayName(file));
                
                log.info("CSV解析成功，使用编码: {}", encoding);
                return data;
                
            } catch (Exception e) {
                lastException = e;
                log.debug("编码 {} 解析失败: {}", encoding, e.getMessage());
                // 继续尝试下一个编码
            }
        }
        
        // 所有编码都失败了
        log.error("所有编码尝试均失败", lastException);
        throw new IllegalArgumentException("CSV解析失败，无法识别文件编码: " + 
            (lastException != null ? lastException.getMessage() : "未知错误"));
    }
    
    private boolean isValidChinese(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        // 检查是否包含常见的乱码字符
        if (text.contains("��") || text.contains("?")) {
            return false;
        }
        // 检查是否包含正常的中文字符
        for (char c : text.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                return true;
            }
        }
        return true; // 如果没有中文字符但也没有乱码，认为是有效的
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
            // 为达梦数据库优化：使用TEXT类型支持长文本
            sb.append("    ").append(column.getColumnName()).append(" TEXT");
            if (i < columns.size() - 1) {
                sb.append(",\n");
            } else {
                sb.append("\n");
            }
        }
        sb.append(")");

        jdbcTemplate.execute(sb.toString());

        // 为达梦数据库启用超长记录功能，解决行记录过长问题
        try {
            String enableLongRowSql = "ALTER TABLE " + tableName + " ENABLE USING LONG ROW";
            jdbcTemplate.execute(enableLongRowSql);
            log.info("已为表 {} 启用超长记录功能", tableName);
        } catch (Exception e) {
            log.warn("启用超长记录功能失败(可能不是达梦数据库): tableName={}, error={}", tableName, e.getMessage());
        }

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

    /**
     * 流式批量插入，适用于大数据量
     */
    private void streamingBatchInsert(String tableName, List<ExcelColumn> columns, List<Map<String, String>> rows) {
        if (rows.isEmpty()) {
            return;
        }

        String columnNames = buildColumnList(columns);
        String placeholders = buildPlaceholders(columns.size());
        String insertSql = "INSERT INTO " + tableName + " (" + columnNames + ") VALUES (" + placeholders + ")";

        int totalRows = rows.size();
        int processedRows = 0;
        
        // 分批处理，避免内存占用过大
        for (int i = 0; i < totalRows; i += DB_BATCH_SIZE) {
            int endIndex = Math.min(i + DB_BATCH_SIZE, totalRows);
            List<Map<String, String>> batch = rows.subList(i, endIndex);
            
            List<Object[]> batchValues = new ArrayList<>();
            for (Map<String, String> row : batch) {
                Object[] values = new Object[columns.size()];
                for (int j = 0; j < columns.size(); j++) {
                    String columnName = columns.get(j).getColumnName();
                    values[j] = StringUtils.defaultString(row.get(columnName));
                }
                batchValues.add(values);
            }
            
            try {
                jdbcTemplate.batchUpdate(insertSql, batchValues);
                processedRows += batchValues.size();
                
                log.debug("流式插入进度: {}/{} 行 ({:.1f}%)", 
                    processedRows, totalRows, (double)processedRows / totalRows * 100);
                
                // 定期释放内存
                if (processedRows % (DB_BATCH_SIZE * 10) == 0) {
                    System.gc();
                }
                
            } catch (Exception e) {
                log.error("流式插入失败: 批次 {}-{}", i, endIndex, e);
                throw new IllegalStateException("数据库写入失败: " + e.getMessage());
            }
        }

        log.info("流式插入完成: 表 {}, 总行数 {}", tableName, processedRows);
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
                    "\"dynamic_templates\":[" +
                    "{\"primary_values\":{\"match\":\"_primary_value_*\",\"mapping\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\",\"ignore_above\":256}}}}}," +
                    "{\"primary_fields\":{\"match\":\"_primary_field_*\",\"mapping\":{\"type\":\"keyword\"}}}," +
                    "{\"strings\":{\"match_mapping_type\":\"string\",\"mapping\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\",\"ignore_above\":256}}}}}" +
                    "]," +
                    "\"properties\":{" +
                    "\"table_name\":{\"type\":\"keyword\"}," +
                    "\"excel_name\":{\"type\":\"keyword\"}," +
                    "\"sheet_name\":{\"type\":\"keyword\"}," +
                    "\"source_type\":{\"type\":\"keyword\"}," +
                    "\"record_id\":{\"type\":\"keyword\"}," +
                    "\"sync_time\":{\"type\":\"date\",\"format\":\"strict_date_optional_time||epoch_millis\"}," +
                    "\"column_labels\":{\"type\":\"object\",\"enabled\":false}" +
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

    /**
     * 流式ES批量索引，适用于大数据量
     */
    private void streamingBulkIndex(String indexName, String tableName, String displayName, ExcelParsedData parsedData) {
        Map<String, String> labelMap = parsedData.getColumnLabelMap();
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, String>> rows = parsedData.getRows();
        int totalRows = rows.size();
        int processedRows = 0;
        int recordId = 1;

        log.info("开始流式ES索引，总行数: {}", totalRows);

        for (int i = 0; i < totalRows; i += ES_BATCH_SIZE) {
            int endIndex = Math.min(i + ES_BATCH_SIZE, totalRows);
            List<Map<String, String>> batch = rows.subList(i, endIndex);
            
            BulkRequest bulkRequest = new BulkRequest();
            bulkRequest.setRefreshPolicy(WriteRequest.RefreshPolicy.WAIT_UNTIL);

            for (Map<String, String> row : batch) {
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
                
                recordId++;
            }

            try {
                executeBulk(bulkRequest);
                processedRows += batch.size();
                
                log.debug("流式ES索引进度: {}/{} 行 ({:.1f}%)", 
                    processedRows, totalRows, (double)processedRows / totalRows * 100);
                
                // 定期释放内存
                if (processedRows % (ES_BATCH_SIZE * 5) == 0) {
                    System.gc();
                }
                
            } catch (Exception e) {
                log.error("流式ES索引失败: 批次 {}-{}", i, endIndex, e);
                throw new IllegalStateException("ES索引失败: " + e.getMessage());
            }
        }

        log.info("流式ES索引完成，总行数: {}", processedRows);
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
