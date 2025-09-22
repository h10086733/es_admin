package com.esadmin.service;

import com.esadmin.dto.FormDto;
import com.esadmin.entity.FormDefinition;
import com.esadmin.repository.FormDefinitionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class FormService {

    private static final Logger log = LoggerFactory.getLogger(FormService.class);
    
    private final FormDefinitionRepository formRepository;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    
    public FormService(FormDefinitionRepository formRepository, ObjectMapper objectMapper, JdbcTemplate jdbcTemplate) {
        this.formRepository = formRepository;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<FormDto> getAllForms() {
        try {
            List<FormDefinition> forms = formRepository.findByDeleteFlag(0);
            return forms.stream().map(this::convertToDto).collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            log.warn("数据库连接失败，返回模拟数据: {}", e.getMessage());
            return createMockFormList();
        }
    }

    public Page<FormDto> getForms(Pageable pageable, String search) {
        try {
            Page<FormDefinition> forms;
            if (StringUtils.isNotBlank(search)) {
                forms = formRepository.findByDeleteFlagAndNameContaining(search, pageable);
            } else {
                forms = formRepository.findByDeleteFlag(0, pageable);
            }
            return forms.map(this::convertToDto);
        } catch (Exception e) {
            log.warn("数据库连接失败，返回模拟数据: {}", e.getMessage());
            List<FormDto> mockForms = createMockFormList();
            return new PageImpl<>(mockForms, pageable, mockForms.size());
        }
    }

    public FormDto getFormById(String formId) {
        try {
            Optional<FormDefinition> form = formRepository.findById(Long.valueOf(formId));
            return form.map(this::convertToDto).orElse(null);
        } catch (Exception e) {
            log.warn("数据库连接失败，返回模拟表单: {}", e.getMessage());
            return createMockForm(formId);
        }
    }

    public String getFormTableName(String formId) {
        FormDto form = getFormById(formId);
        if (form == null) return null;

        try {
            Map<String, Object> fieldInfo = form.getFieldInfo();
            if (fieldInfo.containsKey("front_formmain")) {
                Map<String, Object> frontFormMain = (Map<String, Object>) fieldInfo.get("front_formmain");
                return (String) frontFormMain.get("tableName");
            }
            return null;
        } catch (Exception e) {
            log.error("提取表名失败", e);
            return null;
        }
    }

    public List<Map<String, Object>> getFormFields(String formId) {
        FormDto form = getFormById(formId);
        if (form == null) return new ArrayList<>();

        try {
            Map<String, Object> fieldInfo = form.getFieldInfo();
            if (fieldInfo.containsKey("front_formmain")) {
                Map<String, Object> frontFormMain = (Map<String, Object>) fieldInfo.get("front_formmain");
                Object fieldInfoObj = frontFormMain.get("fieldInfo");
                if (fieldInfoObj instanceof List) {
                    return (List<Map<String, Object>>) fieldInfoObj;
                }
            }
            return new ArrayList<>();
        } catch (Exception e) {
            log.error("获取字段定义失败", e);
            return new ArrayList<>();
        }
    }

    public Map<String, String> getFieldLabels(String formId) {
        List<Map<String, Object>> fields = getFormFields(formId);
        Map<String, String> labels = new HashMap<>();

        for (Map<String, Object> field : fields) {
            String fieldName = getFieldName(field);
            String fieldLabel = getFieldLabel(field);
            if (StringUtils.isNotBlank(fieldName) && StringUtils.isNotBlank(fieldLabel)) {
                labels.put(fieldName, fieldLabel);
            }
        }

        return labels;
    }

    /**
     * 获取字段标签
     */
    public String getFieldLabel(Map<String, Object> field) {
        // 优先使用title，然后是label，最后是display
        String label = (String) field.get("title");
        if (label == null || label.trim().isEmpty()) {
            label = (String) field.get("label");
        }
        if (label == null || label.trim().isEmpty()) {
            label = (String) field.get("display");
        }
        return label;
    }

    /**
     * 获取表单的附表信息（从formsons数组中解析）
     */
    public List<Map<String, Object>> getFormSubTables(String formId) {
        FormDto form = getFormById(formId);
        if (form == null) return new ArrayList<>();

        try {
            Map<String, Object> fieldInfo = form.getFieldInfo();
            List<Map<String, Object>> subTables = new ArrayList<>();
            
            // 检查是否有formsons字段
            if (fieldInfo.containsKey("formsons")) {
                Object formSonsObj = fieldInfo.get("formsons");
                if (formSonsObj instanceof List) {
                    List<Map<String, Object>> formSonsList = (List<Map<String, Object>>) formSonsObj;
                    for (Map<String, Object> formSon : formSonsList) {
                        if (formSon != null) {
                            subTables.add(formSon);
                        }
                    }
                }
            }
            
            log.info("表单 {} 发现 {} 个附表", formId, subTables.size());
            return subTables;
        } catch (Exception e) {
            log.error("获取附表信息失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 获取附表的表名（从formsons结构中）
     */
    public String getSubTableName(Map<String, Object> subTable) {
        try {
            // formsons中的表名可能存储在tableName或其他字段中
            String tableName = (String) subTable.get("tableName");
            if (tableName == null) {
                // 尝试其他可能的字段名
                tableName = (String) subTable.get("table_name");
            }
            if (tableName == null) {
                tableName = (String) subTable.get("name");
            }
            return tableName;
        } catch (Exception e) {
            log.error("获取附表表名失败", e);
            return null;
        }
    }

    /**
     * 获取附表的字段信息（从formsons结构中）
     */
    public List<Map<String, Object>> getSubTableFields(Map<String, Object> subTable) {
        try {
            // formsons中的字段信息可能存储在fieldInfo或fields字段中
            Object fieldInfoObj = subTable.get("fieldInfo");
            if (fieldInfoObj instanceof List) {
                return (List<Map<String, Object>>) fieldInfoObj;
            }
            
            // 尝试其他可能的字段名
            fieldInfoObj = subTable.get("fields");
            if (fieldInfoObj instanceof List) {
                return (List<Map<String, Object>>) fieldInfoObj;
            }
            
            return new ArrayList<>();
        } catch (Exception e) {
            log.error("获取附表字段信息失败", e);
            return new ArrayList<>();
        }
    }

    public List<Map<String, Object>> getTableDataBatch(String tableName, int limit, int offset, LocalDateTime modifyDateAfter) {
        StringBuilder sql = new StringBuilder("SELECT * FROM " + tableName);
        List<Object> params = new ArrayList<>();

        if (modifyDateAfter != null) {
            sql.append(" WHERE modify_date >= ?");
            params.add(modifyDateAfter);
        }

        sql.append(" ORDER BY ID LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        try {
            return jdbcTemplate.queryForList(sql.toString(), params.toArray());
        } catch (Exception e) {
            log.error("获取表数据失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * 基于最小ID获取数据批次（用于ES增量同步）
     */
    public List<Map<String, Object>> getTableDataBatchByMinId(String tableName, int limit, int offset, Long minId) {
        StringBuilder sql = new StringBuilder("SELECT * FROM " + tableName);
        List<Object> params = new ArrayList<>();
        
        if (minId != null) {
            sql.append(" WHERE ID > ?");
            params.add(minId);
        }
        
        sql.append(" ORDER BY ID LIMIT ?");
        params.add(limit);
        // 不使用OFFSET，纯游标分页
        
        try {
            log.debug("执行SQL: {}, 参数: {}", sql.toString(), params);
            List<Map<String, Object>> result = jdbcTemplate.queryForList(sql.toString(), params.toArray());
            log.debug("查询结果: {} 条记录", result.size());
            if (!result.isEmpty()) {
                log.debug("第一条记录ID: {}, 最后一条记录ID: {}", 
                    result.get(0).get("ID"), result.get(result.size()-1).get("ID"));
            }
            return result;
        } catch (Exception e) {
            log.error("基于ID获取表数据失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * 基于修改时间和记录ID的安全增量查询（避免同一时间多条记录丢失）
     */
    public List<Map<String, Object>> getTableDataBatchSafeIncremental(String tableName, int limit, int offset, 
                                                                      LocalDateTime lastModifyDate, Long lastRecordId) {
        StringBuilder sql = new StringBuilder("SELECT * FROM " + tableName);
        List<Object> params = new ArrayList<>();

        if (lastModifyDate != null && lastRecordId != null) {
            // 使用安全的增量查询条件：
            // WHERE modify_date > lastModifyDate OR (modify_date = lastModifyDate AND id > lastRecordId)
            sql.append(" WHERE modify_date > ? OR (modify_date = ? AND ID > ?)");
            params.add(lastModifyDate);
            params.add(lastModifyDate);
            params.add(lastRecordId);
        } else if (lastModifyDate != null) {
            // 如果只有时间没有ID，使用 >= 避免丢失同一时间的记录
            sql.append(" WHERE modify_date >= ?");
            params.add(lastModifyDate);
        }

        // 按时间和ID排序确保顺序稳定
        sql.append(" ORDER BY modify_date, ID LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        try {
            List<Map<String, Object>> result = jdbcTemplate.queryForList(sql.toString(), params.toArray());
            log.debug("安全增量查询: 表={}, 条件时间={}, 条件ID={}, 返回记录数={}", 
                     tableName, lastModifyDate, lastRecordId, result.size());
            return result;
        } catch (Exception e) {
            log.error("安全增量获取表数据失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public boolean checkTableExists(String tableName) {
        try {
            jdbcTemplate.queryForObject("SELECT 1 FROM " + tableName + " LIMIT 1", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private FormDto convertToDto(FormDefinition entity) {
        FormDto dto = new FormDto();
        dto.setId(String.valueOf(entity.getId()));
        dto.setName(entity.getName());

        try {
            if (StringUtils.isNotBlank(entity.getFieldInfo())) {
                dto.setFieldInfo(objectMapper.readValue(entity.getFieldInfo(), new TypeReference<Map<String, Object>>() {}));
            }
            if (StringUtils.isNotBlank(entity.getViewInfo())) {
                dto.setViewInfo(objectMapper.readValue(entity.getViewInfo(), new TypeReference<Map<String, Object>>() {}));
            }
            if (StringUtils.isNotBlank(entity.getAppbindInfo())) {
                dto.setAppbindInfo(objectMapper.readValue(entity.getAppbindInfo(), new TypeReference<Map<String, Object>>() {}));
            }
            if (StringUtils.isNotBlank(entity.getExtensionsInfo())) {
                dto.setExtensionsInfo(objectMapper.readValue(entity.getExtensionsInfo(), new TypeReference<Map<String, Object>>() {}));
            }
        } catch (Exception e) {
            log.error("JSON解析失败", e);
        }

        return dto;
    }

    private String getFieldName(Map<String, Object> field) {
        String name = (String) field.get("name");
        if (StringUtils.isBlank(name)) {
            name = (String) field.get("columnName");
        }
        return name;
    }


    private List<FormDto> createMockFormList() {
        List<FormDto> mockForms = new ArrayList<>();
        
        FormDto form1 = new FormDto();
        form1.setId("1");
        form1.setName("示例表单1（数据库连接失败）");
        form1.setFieldInfo(new HashMap<>());
        mockForms.add(form1);
        
        FormDto form2 = new FormDto();
        form2.setId("2");
        form2.setName("示例表单2（数据库连接失败）");
        form2.setFieldInfo(new HashMap<>());
        mockForms.add(form2);
        
        return mockForms;
    }

    private FormDto createMockForm(String formId) {
        FormDto mockForm = new FormDto();
        mockForm.setId(formId);
        mockForm.setName("模拟表单 " + formId + "（数据库连接失败）");
        mockForm.setFieldInfo(new HashMap<>());
        return mockForm;
    }
    
    public JdbcTemplate getJdbcTemplate() {
        return jdbcTemplate;
    }
    
    /**
     * 为表单表的ID字段创建索引（如果不存在）
     */
    public boolean ensureTableIdIndex(String tableName) {
        try {
            String indexName = "IDX_" + tableName + "_ID";
            
            // 检查索引是否已存在
            String checkIndexSql = "SELECT COUNT(*) FROM USER_INDEXES WHERE INDEX_NAME = ? AND TABLE_NAME = ?";
            Integer indexCount = jdbcTemplate.queryForObject(checkIndexSql, Integer.class, indexName, tableName.toUpperCase());
            
            if (indexCount != null && indexCount > 0) {
                log.debug("索引已存在: {}", indexName);
                return true;
            }
            
            // 创建索引
            String createIndexSql = "CREATE INDEX " + indexName + " ON " + tableName + " (ID)";
            jdbcTemplate.execute(createIndexSql);
            
            log.info("成功为表 {} 的ID字段创建索引: {}", tableName, indexName);
            return true;
            
        } catch (Exception e) {
            log.error("为表 {} 创建ID索引失败", tableName, e);
            return false;
        }
    }
    
    /**
     * 为表单表创建增量同步所需的索引
     */
    public boolean ensureIncrementalSyncIndexes(String tableName) {
        boolean success = true;
        
        try {
            // 1. 为modify_date字段创建索引
            String modifyDateIndexName = "IDX_" + tableName + "_MODIFY_DATE";
            String checkModifyDateIndexSql = "SELECT COUNT(*) FROM USER_INDEXES WHERE INDEX_NAME = ? AND TABLE_NAME = ?";
            Integer modifyDateIndexCount = jdbcTemplate.queryForObject(checkModifyDateIndexSql, Integer.class, 
                    modifyDateIndexName, tableName.toUpperCase());
            
            if (modifyDateIndexCount == null || modifyDateIndexCount == 0) {
                String createModifyDateIndexSql = "CREATE INDEX " + modifyDateIndexName + " ON " + tableName + " (modify_date)";
                jdbcTemplate.execute(createModifyDateIndexSql);
                log.info("成功为表 {} 的modify_date字段创建索引: {}", tableName, modifyDateIndexName);
            }
            
            // 2. 创建组合索引 (modify_date, ID) 用于高效的增量查询
            String compositeIndexName = "IDX_" + tableName + "_MODIFY_ID";
            String checkCompositeIndexSql = "SELECT COUNT(*) FROM USER_INDEXES WHERE INDEX_NAME = ? AND TABLE_NAME = ?";
            Integer compositeIndexCount = jdbcTemplate.queryForObject(checkCompositeIndexSql, Integer.class, 
                    compositeIndexName, tableName.toUpperCase());
            
            if (compositeIndexCount == null || compositeIndexCount == 0) {
                String createCompositeIndexSql = "CREATE INDEX " + compositeIndexName + " ON " + tableName + " (modify_date, ID)";
                jdbcTemplate.execute(createCompositeIndexSql);
                log.info("成功为表 {} 创建组合索引: {} (modify_date, ID)", tableName, compositeIndexName);
            }
            
        } catch (Exception e) {
            log.error("为表 {} 创建增量同步索引失败", tableName, e);
            success = false;
        }
        
        return success;
    }
    
    /**
     * 高效的增量查询 - 使用游标分页避免大OFFSET
     */
    public List<Map<String, Object>> getTableDataIncrementalCursor(String tableName, int limit, 
                                                                   LocalDateTime lastModifyDate, Long lastId) {
        StringBuilder sql = new StringBuilder("SELECT * FROM " + tableName);
        List<Object> params = new ArrayList<>();
        
        if (lastModifyDate != null) {
            if (lastId != null) {
                // 使用游标分页：(modify_date > lastModifyDate) OR (modify_date = lastModifyDate AND ID > lastId)
                sql.append(" WHERE (modify_date > ? OR (modify_date = ? AND ID > ?))");
                params.add(lastModifyDate);
                params.add(lastModifyDate);
                params.add(lastId);
            } else {
                // 首次查询或没有ID信息
                sql.append(" WHERE modify_date >= ?");
                params.add(lastModifyDate);
            }
        }
        
        // 重要：按组合索引顺序排序，确保能使用索引
        sql.append(" ORDER BY modify_date, ID LIMIT ?");
        params.add(limit);
        
        try {
            return jdbcTemplate.queryForList(sql.toString(), params.toArray());
        } catch (Exception e) {
            log.error("增量查询失败: tableName={}, lastModifyDate={}, lastId={}", 
                    tableName, lastModifyDate, lastId, e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 无索引增量同步：基于ES已同步ID集合的差集查询
     * 适用于大表无modify_date索引的场景
     */
    public List<Map<String, Object>> getTableDataByIdBatch(String tableName, int limit, int offset) {
        String sql = "SELECT * FROM " + tableName + " ORDER BY ID LIMIT ? OFFSET ?";
        
        try {
            return jdbcTemplate.queryForList(sql, limit, offset);
        } catch (Exception e) {
            log.error("按ID批量查询失败: tableName={}, limit={}, offset={}", tableName, limit, offset, e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 获取表的总记录数
     */
    public long getTableTotalCount(String tableName) {
        try {
            String sql = "SELECT COUNT(*) FROM " + tableName;
            Long count = jdbcTemplate.queryForObject(sql, Long.class);
            return count != null ? count : 0L;
        } catch (Exception e) {
            log.error("获取表记录数失败: tableName={}", tableName, e);
            return 0L;
        }
    }
    
    /**
     * 根据ID列表查询特定记录（用于检查更新）
     */
    public List<Map<String, Object>> getTableDataByIds(String tableName, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        
        try {
            String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
            String sql = "SELECT * FROM " + tableName + " WHERE ID IN (" + placeholders + ")";
            
            return jdbcTemplate.queryForList(sql, ids.toArray());
        } catch (Exception e) {
            log.error("根据ID列表查询失败: tableName={}, ids={}", tableName, ids, e);
            return new ArrayList<>();
        }
    }
}