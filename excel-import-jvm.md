# Excel 导入优化配置

## JVM 启动参数
建议在启动应用时添加以下 JVM 参数以优化 Excel 导入性能：

```bash
java -jar es-admin.jar \
  -Xms512m \
  -Xmx2g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:G1HeapRegionSize=16m \
  -XX:+UnlockExperimentalVMOptions \
  -XX:+UseStringDeduplication \
  -Dpoi.xssf.shared.strings.read.request.limit=50000000
```

## 系统属性配置
以下系统属性已在代码中自动设置：

- `poi.xssf.shared.strings.read.request.limit=50000000`: 限制 POI 读取共享字符串的最大请求数

## 文件大小限制
- 最大文件大小：50MB
- 最大数据行数：100,000 行
- 支持文件格式：.xlsx, .xls, .csv

## 错误处理
系统现在能够正确处理以下异常情况：
1. 文件过大导致的内存溢出
2. POI 解析异常
3. 文件格式错误
4. 行数超限

## 使用建议
1. 确保服务器有足够内存（建议至少 4GB）
2. 大文件建议分批处理
3. CSV 格式处理效率更高，建议优先使用