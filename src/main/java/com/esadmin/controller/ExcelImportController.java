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
}
