package com.esadmin.controller;

import com.esadmin.dto.ExcelImportMetadata;
import com.esadmin.dto.ExcelImportResult;
import com.esadmin.service.ExcelImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/excel")
public class ExcelImportController {

    private static final Logger log = LoggerFactory.getLogger(ExcelImportController.class);

    private final ExcelImportService excelImportService;

    public ExcelImportController(ExcelImportService excelImportService) {
        this.excelImportService = excelImportService;
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> previewExcel(@RequestParam("file") MultipartFile file,
                                                            @RequestParam(value = "sheetName", required = false) String sheetName,
                                                            @RequestParam(value = "previewRows", defaultValue = "10") int previewRows) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (previewRows <= 0 || previewRows > 100) {
                previewRows = 10;
            }

            Map<String, Object> previewResult = excelImportService.previewExcel(file, sheetName, previewRows);

            response.put("success", true);
            response.put("data", previewResult);
            response.put("message", "预览成功");

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("Excel预览参数错误: {}", e.getMessage());
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (IOException e) {
            log.error("读取Excel文件失败", e);
            response.put("success", false);
            response.put("message", "读取Excel文件失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        } catch (Exception e) {
            log.error("Excel预览失败", e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> importExcel(@RequestParam("file") MultipartFile file,
                                                           @RequestParam(value = "name", required = false) String name,
                                                           @RequestParam(value = "sheetName", required = false) String sheetName,
                                                           @RequestParam(value = "cover", defaultValue = "true") boolean cover) {
        Map<String, Object> response = new HashMap<>();
        try {
            ExcelImportResult result = excelImportService.importExcel(file, name, sheetName, cover);

            Map<String, Object> data = new HashMap<>();
            data.put("table_name", result.getTableName());
            data.put("index_name", result.getIndexName());
            data.put("display_name", result.getDisplayName());
            data.put("sheet_name", result.getSheetName());
            data.put("row_count", result.getRowCount());
            data.put("import_time", result.getImportTime());
            data.put("column_labels", result.getColumnLabels());

            response.put("success", true);
            response.put("data", data);
            response.put("message", "导入成功");

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("Excel导入参数错误: {}", e.getMessage());
            response.put("success", false);
            response.put("message", e.getMessage());
            
            // 检查是否是表名重复错误
            if (e.getMessage().contains("表名重复")) {
                response.put("error_type", "duplicate_table_name");
                response.put("suggestion", "请修改自定义名称或开启覆盖模式");
            }
            
            return ResponseEntity.badRequest().body(response);
        } catch (IOException e) {
            log.error("读取Excel文件失败", e);
            response.put("success", false);
            response.put("message", "读取Excel文件失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        } catch (Exception e) {
            log.error("Excel导入失败", e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> listImports() {
        try {
            List<ExcelImportMetadata> list = excelImportService.listImports();
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", list);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("查询Excel导入记录失败", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @DeleteMapping("/{tableName}")
    public ResponseEntity<Map<String, Object>> deleteImport(@PathVariable String tableName) {
        Map<String, Object> response = new HashMap<>();
        try {
            excelImportService.deleteImport(tableName);
            
            response.put("success", true);
            response.put("message", "删除成功");
            return ResponseEntity.ok(response);
            
        } catch (IllegalArgumentException e) {
            log.warn("Excel导入删除参数错误: {}", e.getMessage());
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("Excel导入删除失败", e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @GetMapping("/{tableName}/preview")
    public ResponseEntity<Map<String, Object>> previewImportedData(@PathVariable String tableName,
                                                                   @RequestParam(value = "rows", defaultValue = "5") int rows) {
        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, Object> preview = excelImportService.previewImportedData(tableName, rows);
            response.put("success", true);
            response.put("data", preview);
            response.put("message", "预览成功");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("导入数据预览失败", e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }
}
