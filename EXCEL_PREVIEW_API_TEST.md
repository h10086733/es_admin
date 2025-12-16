# Excel 预览功能 API 测试指南

## 使用 curl 测试

### 1. 预览 Excel 文件

```bash
# 基本预览（默认10行）
curl -X POST http://localhost:8080/api/excel/preview \
  -F "file=@/path/to/your/file.xlsx"

# 指定预览行数
curl -X POST http://localhost:8080/api/excel/preview \
  -F "file=@/path/to/your/file.xlsx" \
  -F "previewRows=5"

# 指定工作表名称
curl -X POST http://localhost:8080/api/excel/preview \
  -F "file=@/path/to/your/file.xlsx" \
  -F "sheetName=Sheet2" \
  -F "previewRows=20"
```

### 2. 确认导入（预览后）

```bash
# 基本导入
curl -X POST http://localhost:8080/api/excel/import \
  -F "file=@/path/to/your/file.xlsx"

# 带自定义名称
curl -X POST http://localhost:8080/api/excel/import \
  -F "file=@/path/to/your/file.xlsx" \
  -F "name=员工信息" \
  -F "cover=true"

# 完整参数
curl -X POST http://localhost:8080/api/excel/import \
  -F "file=@/path/to/your/file.xlsx" \
  -F "name=测试数据" \
  -F "sheetName=Sheet1" \
  -F "cover=false"
```

## 使用 Postman 测试

### 预览接口

1. **请求方式**: POST
2. **URL**: `http://localhost:8080/api/excel/preview`
3. **Headers**: Content-Type 会自动设置为 multipart/form-data
4. **Body**: 
   - 选择 `form-data`
   - 添加字段:
     - `file`: 选择 File 类型，上传 Excel 文件
     - `previewRows`: 输入 10 (可选)
     - `sheetName`: 输入工作表名称 (可选)

### 导入接口

1. **请求方式**: POST
2. **URL**: `http://localhost:8080/api/excel/import`
3. **Headers**: Content-Type 会自动设置为 multipart/form-data
4. **Body**: 
   - 选择 `form-data`
   - 添加字段:
     - `file`: 选择 File 类型，上传 Excel 文件
     - `name`: 输入自定义表名 (可选)
     - `sheetName`: 输入工作表名称 (可选)
     - `cover`: 输入 true 或 false (可选，默认 true)

## 预期响应示例

### 预览成功响应

```json
{
  "success": true,
  "message": "预览成功",
  "data": {
    "fileName": "员工信息.xlsx",
    "sheetName": "员工表",
    "displayName": "员工信息",
    "totalRows": 1500,
    "previewRows": 10,
    "columns": [
      {
        "columnName": "ID",
        "header": "工号"
      },
      {
        "columnName": "NAME",
        "header": "姓名"
      },
      {
        "columnName": "DEPARTMENT",
        "header": "部门"
      },
      {
        "columnName": "POSITION",
        "header": "职位"
      },
      {
        "columnName": "SALARY",
        "header": "薪资"
      }
    ],
    "data": [
      {
        "ID": "E001",
        "NAME": "张三",
        "DEPARTMENT": "技术部",
        "POSITION": "开发工程师",
        "SALARY": "15000"
      },
      {
        "ID": "E002",
        "NAME": "李四",
        "DEPARTMENT": "产品部",
        "POSITION": "产品经理",
        "SALARY": "18000"
      },
      {
        "ID": "E003",
        "NAME": "王五",
        "DEPARTMENT": "技术部",
        "POSITION": "测试工程师",
        "SALARY": "12000"
      }
    ]
  }
}
```

### 预览失败响应

```json
{
  "success": false,
  "message": "文件大小超过限制，最大支持 50 MB"
}
```

或

```json
{
  "success": false,
  "message": "Excel第一行必须包含字段名"
}
```

### 导入成功响应

```json
{
  "success": true,
  "message": "导入成功",
  "data": {
    "table_name": "EXCEL_YUANGONGXINXI",
    "index_name": "excel_yuangongxinxi",
    "display_name": "员工信息",
    "sheet_name": "员工表",
    "row_count": 1500,
    "import_time": "2025-12-16T14:30:00",
    "column_labels": {
      "ID": "工号",
      "NAME": "姓名",
      "DEPARTMENT": "部门",
      "POSITION": "职位",
      "SALARY": "薪资"
    }
  }
}
```

### 导入失败响应

```json
{
  "success": false,
  "message": "表名重复：'员工信息' 对应的表已存在，请修改名称或开启覆盖模式",
  "error_type": "duplicate_table_name",
  "suggestion": "请修改自定义名称或开启覆盖模式"
}
```

## Python 测试脚本

```python
import requests

# 预览接口测试
def preview_excel(file_path, preview_rows=10, sheet_name=None):
    url = "http://localhost:8080/api/excel/preview"
    
    files = {'file': open(file_path, 'rb')}
    data = {'previewRows': preview_rows}
    
    if sheet_name:
        data['sheetName'] = sheet_name
    
    response = requests.post(url, files=files, data=data)
    return response.json()

# 导入接口测试
def import_excel(file_path, name=None, sheet_name=None, cover=True):
    url = "http://localhost:8080/api/excel/import"
    
    files = {'file': open(file_path, 'rb')}
    data = {'cover': cover}
    
    if name:
        data['name'] = name
    if sheet_name:
        data['sheetName'] = sheet_name
    
    response = requests.post(url, files=files, data=data)
    return response.json()

# 使用示例
if __name__ == "__main__":
    file_path = "/path/to/your/excel/file.xlsx"
    
    # 1. 先预览
    print("预览 Excel 文件...")
    preview_result = preview_excel(file_path, preview_rows=5)
    
    if preview_result['success']:
        print(f"文件名: {preview_result['data']['fileName']}")
        print(f"总行数: {preview_result['data']['totalRows']}")
        print(f"预览行数: {preview_result['data']['previewRows']}")
        print("\n列信息:")
        for col in preview_result['data']['columns']:
            print(f"  - {col['header']} ({col['columnName']})")
        
        print("\n预览数据:")
        for i, row in enumerate(preview_result['data']['data'], 1):
            print(f"  第{i}行: {row}")
        
        # 2. 确认后导入
        confirm = input("\n是否确认导入? (y/n): ")
        if confirm.lower() == 'y':
            print("\n开始导入...")
            import_result = import_excel(file_path, name="测试导入", cover=True)
            
            if import_result['success']:
                print("导入成功!")
                print(f"表名: {import_result['data']['display_name']}")
                print(f"行数: {import_result['data']['row_count']}")
            else:
                print(f"导入失败: {import_result['message']}")
    else:
        print(f"预览失败: {preview_result['message']}")
```

## JavaScript/Node.js 测试脚本

```javascript
const FormData = require('form-data');
const fs = require('fs');
const axios = require('axios');

// 预览接口测试
async function previewExcel(filePath, previewRows = 10, sheetName = null) {
  const formData = new FormData();
  formData.append('file', fs.createReadStream(filePath));
  formData.append('previewRows', previewRows);
  
  if (sheetName) {
    formData.append('sheetName', sheetName);
  }
  
  try {
    const response = await axios.post(
      'http://localhost:8080/api/excel/preview',
      formData,
      { headers: formData.getHeaders() }
    );
    return response.data;
  } catch (error) {
    return { success: false, message: error.message };
  }
}

// 导入接口测试
async function importExcel(filePath, name = null, sheetName = null, cover = true) {
  const formData = new FormData();
  formData.append('file', fs.createReadStream(filePath));
  formData.append('cover', cover);
  
  if (name) {
    formData.append('name', name);
  }
  if (sheetName) {
    formData.append('sheetName', sheetName);
  }
  
  try {
    const response = await axios.post(
      'http://localhost:8080/api/excel/import',
      formData,
      { headers: formData.getHeaders() }
    );
    return response.data;
  } catch (error) {
    return { success: false, message: error.message };
  }
}

// 使用示例
(async () => {
  const filePath = '/path/to/your/excel/file.xlsx';
  
  // 1. 先预览
  console.log('预览 Excel 文件...');
  const previewResult = await previewExcel(filePath, 5);
  
  if (previewResult.success) {
    console.log(`文件名: ${previewResult.data.fileName}`);
    console.log(`总行数: ${previewResult.data.totalRows}`);
    console.log(`预览行数: ${previewResult.data.previewRows}`);
    
    console.log('\n列信息:');
    previewResult.data.columns.forEach(col => {
      console.log(`  - ${col.header} (${col.columnName})`);
    });
    
    console.log('\n预览数据:');
    previewResult.data.data.forEach((row, i) => {
      console.log(`  第${i+1}行:`, row);
    });
    
    // 2. 确认后导入
    console.log('\n开始导入...');
    const importResult = await importExcel(filePath, '测试导入', null, true);
    
    if (importResult.success) {
      console.log('导入成功!');
      console.log(`表名: ${importResult.data.display_name}`);
      console.log(`行数: ${importResult.data.row_count}`);
    } else {
      console.log(`导入失败: ${importResult.message}`);
    }
  } else {
    console.log(`预览失败: ${previewResult.message}`);
  }
})();
```

## 常见问题排查

### 1. 文件上传失败

**问题**: `400 Bad Request` 或 `File is empty`

**解决方案**:
- 检查文件路径是否正确
- 确认文件不为空
- 检查 Content-Type 是否为 multipart/form-data
- 确认参数名为 "file"

### 2. 预览超时

**问题**: 请求超时或响应慢

**解决方案**:
- 减少 previewRows 数量（建议5-10行）
- 检查文件大小（建议小于10MB时预览）
- 对于大文件，直接使用导入接口

### 3. 编码问题（CSV）

**问题**: CSV文件中文显示乱码

**解决方案**:
- 系统会自动尝试多种编码（UTF-8, GBK, GB2312）
- 如果仍有问题，建议转换为 .xlsx 格式
- 确保 CSV 文件使用 UTF-8 或 GBK 编码保存

### 4. 内存溢出

**问题**: 大文件导致内存不足

**解决方案**:
- 文件大小限制在50MB以内
- 总行数不超过100,000行
- 如需导入更大文件，建议分批导入

## 性能建议

1. **预览行数**: 建议使用5-10行，足够查看数据格式
2. **文件大小**: 小文件（<10MB）适合预览，大文件建议直接导入
3. **并发限制**: 避免同时上传多个大文件
4. **超时设置**: 建议设置60秒以上的请求超时时间
