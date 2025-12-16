# Excel 导入预览功能使用说明

## 功能概述

新增了Excel导入预览功能，用户可以在正式导入前查看Excel的前几行数据，确认数据正确后再点击确认导入。

## API 接口

### 1. 预览接口

**接口地址**: `POST /api/excel/preview`

**请求参数**:
- `file` (必填): MultipartFile - Excel文件
- `sheetName` (可选): String - 指定工作表名称，不填则使用第一个工作表
- `previewRows` (可选): int - 预览行数，默认10行，最多100行

**响应示例**:
```json
{
  "success": true,
  "message": "预览成功",
  "data": {
    "fileName": "员工信息表.xlsx",
    "sheetName": "Sheet1",
    "displayName": "员工信息表",
    "totalRows": 500,
    "previewRows": 10,
    "columns": [
      {
        "columnName": "NAME",
        "header": "姓名"
      },
      {
        "columnName": "AGE",
        "header": "年龄"
      }
    ],
    "data": [
      {
        "NAME": "张三",
        "AGE": "28"
      },
      {
        "NAME": "李四",
        "AGE": "32"
      }
    ]
  }
}
```

### 2. 确认导入接口（已有）

**接口地址**: `POST /api/excel/import`

**请求参数**:
- `file` (必填): MultipartFile - Excel文件
- `name` (可选): String - 自定义表名
- `sheetName` (可选): String - 指定工作表名称
- `cover` (可选): boolean - 是否覆盖已存在的表，默认true

## 前端集成示例

### HTML 结构

```html
<!-- Excel 导入区域 -->
<div class="excel-import-section">
  <h3>Excel 数据导入</h3>
  
  <!-- 文件选择 -->
  <div class="file-upload">
    <input type="file" id="excelFile" accept=".xlsx,.xls,.csv" />
    <button onclick="previewExcel()">预览数据</button>
  </div>
  
  <!-- 预览区域 -->
  <div id="previewSection" style="display: none;">
    <h4>数据预览</h4>
    <div id="previewInfo"></div>
    <div id="previewTable"></div>
    
    <div class="import-options">
      <label>
        自定义表名: <input type="text" id="customName" placeholder="可选" />
      </label>
      <label>
        <input type="checkbox" id="coverExisting" checked /> 覆盖已存在的表
      </label>
    </div>
    
    <div class="actions">
      <button onclick="confirmImport()">确认导入</button>
      <button onclick="cancelPreview()">取消</button>
    </div>
  </div>
  
  <!-- 导入结果 -->
  <div id="importResult"></div>
</div>
```

### JavaScript 实现

```javascript
let currentFile = null;
let currentSheetName = null;

// 预览Excel
async function previewExcel() {
  const fileInput = document.getElementById('excelFile');
  const file = fileInput.files[0];
  
  if (!file) {
    alert('请选择文件');
    return;
  }
  
  currentFile = file;
  
  const formData = new FormData();
  formData.append('file', file);
  formData.append('previewRows', '10'); // 预览10行
  
  try {
    const response = await fetch('/api/excel/preview', {
      method: 'POST',
      body: formData
    });
    
    const result = await response.json();
    
    if (result.success) {
      currentSheetName = result.data.sheetName;
      displayPreview(result.data);
    } else {
      alert('预览失败: ' + result.message);
    }
  } catch (error) {
    alert('预览失败: ' + error.message);
  }
}

// 显示预览数据
function displayPreview(previewData) {
  const previewSection = document.getElementById('previewSection');
  const previewInfo = document.getElementById('previewInfo');
  const previewTable = document.getElementById('previewTable');
  
  // 显示文件信息
  previewInfo.innerHTML = `
    <p><strong>文件名:</strong> ${previewData.fileName}</p>
    <p><strong>工作表:</strong> ${previewData.sheetName}</p>
    <p><strong>总行数:</strong> ${previewData.totalRows} 行</p>
    <p><strong>预览:</strong> 前 ${previewData.previewRows} 行</p>
  `;
  
  // 生成预览表格
  let tableHtml = '<table class="preview-table"><thead><tr>';
  
  // 表头
  previewData.columns.forEach(col => {
    tableHtml += `<th>${col.header}</th>`;
  });
  tableHtml += '</tr></thead><tbody>';
  
  // 数据行
  previewData.data.forEach(row => {
    tableHtml += '<tr>';
    previewData.columns.forEach(col => {
      const value = row[col.columnName] || '';
      tableHtml += `<td>${value}</td>`;
    });
    tableHtml += '</tr>';
  });
  
  tableHtml += '</tbody></table>';
  previewTable.innerHTML = tableHtml;
  
  // 显示预览区域
  previewSection.style.display = 'block';
}

// 确认导入
async function confirmImport() {
  if (!currentFile) {
    alert('请先预览文件');
    return;
  }
  
  const customName = document.getElementById('customName').value;
  const coverExisting = document.getElementById('coverExisting').checked;
  
  const formData = new FormData();
  formData.append('file', currentFile);
  if (customName) {
    formData.append('name', customName);
  }
  if (currentSheetName) {
    formData.append('sheetName', currentSheetName);
  }
  formData.append('cover', coverExisting);
  
  try {
    // 显示加载状态
    document.getElementById('importResult').innerHTML = '<p>正在导入，请稍候...</p>';
    
    const response = await fetch('/api/excel/import', {
      method: 'POST',
      body: formData
    });
    
    const result = await response.json();
    
    if (result.success) {
      document.getElementById('importResult').innerHTML = `
        <div class="success">
          <h4>导入成功！</h4>
          <p>表名: ${result.data.display_name}</p>
          <p>导入行数: ${result.data.row_count}</p>
          <p>导入时间: ${result.data.import_time}</p>
        </div>
      `;
      
      // 清空表单
      cancelPreview();
      document.getElementById('excelFile').value = '';
    } else {
      document.getElementById('importResult').innerHTML = `
        <div class="error">
          <h4>导入失败</h4>
          <p>${result.message}</p>
        </div>
      `;
    }
  } catch (error) {
    document.getElementById('importResult').innerHTML = `
      <div class="error">
        <h4>导入失败</h4>
        <p>${error.message}</p>
      </div>
    `;
  }
}

// 取消预览
function cancelPreview() {
  document.getElementById('previewSection').style.display = 'none';
  document.getElementById('customName').value = '';
  currentFile = null;
  currentSheetName = null;
}
```

### CSS 样式

```css
.excel-import-section {
  background: #fff;
  padding: 20px;
  border-radius: 8px;
  margin: 20px 0;
}

.file-upload {
  margin: 20px 0;
  display: flex;
  gap: 10px;
  align-items: center;
}

#previewSection {
  margin-top: 20px;
  padding: 20px;
  background: #f8f9fa;
  border-radius: 6px;
}

#previewInfo {
  margin-bottom: 15px;
  padding: 10px;
  background: #e9ecef;
  border-radius: 4px;
}

.preview-table {
  width: 100%;
  border-collapse: collapse;
  margin: 15px 0;
  font-size: 14px;
}

.preview-table th,
.preview-table td {
  border: 1px solid #dee2e6;
  padding: 8px 12px;
  text-align: left;
}

.preview-table th {
  background: #007bff;
  color: white;
  font-weight: 600;
}

.preview-table tr:nth-child(even) {
  background: #f8f9fa;
}

.import-options {
  margin: 20px 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.import-options label {
  display: flex;
  align-items: center;
  gap: 8px;
}

.actions {
  display: flex;
  gap: 10px;
  margin-top: 15px;
}

button {
  padding: 10px 20px;
  background: #007bff;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
}

button:hover {
  background: #0056b3;
}

button:disabled {
  background: #6c757d;
  cursor: not-allowed;
}

.success {
  padding: 15px;
  background: #d4edda;
  border: 1px solid #c3e6cb;
  border-radius: 4px;
  color: #155724;
  margin-top: 15px;
}

.error {
  padding: 15px;
  background: #f8d7da;
  border: 1px solid #f5c6cb;
  border-radius: 4px;
  color: #721c24;
  margin-top: 15px;
}
```

## 使用流程

1. **选择文件**: 用户点击文件上传按钮，选择Excel文件
2. **预览数据**: 点击"预览数据"按钮，系统会解析Excel并显示前10行数据
3. **确认信息**: 用户查看预览的数据，确认：
   - 文件是否正确
   - 列名是否正确
   - 数据格式是否正确
   - 总行数是否符合预期
4. **设置选项**: 
   - 可选填写自定义表名
   - 选择是否覆盖已存在的表
5. **确认导入**: 点击"确认导入"按钮，正式导入数据到数据库和ES
6. **查看结果**: 导入完成后显示结果信息

## 注意事项

1. **文件大小限制**: 最大支持50MB的Excel文件
2. **行数限制**: 最多支持100,000行数据
3. **预览行数**: 预览最多显示100行，建议使用10-20行
4. **文件格式**: 支持.xlsx、.xls、.csv格式
5. **编码支持**: CSV文件自动检测UTF-8、GBK、GB2312编码
6. **大文件处理**: 超过10MB的文件会使用流式处理，导入速度较慢

## 错误处理

- 如果文件格式不正确，预览时会提示错误
- 如果表名重复且未开启覆盖模式，导入时会失败
- 如果数据行数超过限制，会在预览或导入时提示错误
- 网络错误或服务器错误会在界面上显示具体错误信息

## 技术优势

1. **提前验证**: 用户可以在导入前确认数据正确性，避免错误导入
2. **性能优化**: 预览只解析部分数据，速度快
3. **用户友好**: 可视化的表格展示，清晰直观
4. **容错机制**: 完善的错误提示和处理
