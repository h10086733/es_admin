# ES数据管理系统 - Java版

这是ES数据管理系统的Java版本，使用Spring Boot框架重新实现，避免了Python环境的依赖问题，实现纯内网部署。

## 系统要求

- Java 8 或更高版本
- Maven 3.6+
- 达梦数据库
- Elasticsearch 7.17+

## 技术栈

- **框架**: Spring Boot 2.7.18
- **数据库**: 达梦数据库 (DM 8)
- **搜索引擎**: Elasticsearch 7.17
- **构建工具**: Maven
- **前端**: HTML + JavaScript (轻量化)

## 快速开始

### 1. 环境配置

复制环境变量模板：
```bash
cp .env.example .env
```

编辑 `.env` 文件配置数据库和ES连接信息：
```bash
# 达梦数据库配置
DM_HOST=你的数据库地址
DM_PORT=5236
DM_USER=数据库用户名
DM_PASSWORD=数据库密码
DM_DATABASE=数据库名

# Elasticsearch配置
ES_HOST=你的ES地址:9200
ES_USER=ES用户名
ES_PASSWORD=ES密码
```

### 2. 构建项目

```bash
# 编译打包
mvn clean package -DskipTests

# 或者包含测试
mvn clean package
```

### 3. 启动应用

使用启动脚本：
```bash
./start.sh
```

或直接运行JAR：
```bash
java -jar target/es-admin-1.0.0.jar
```

### 4. 访问系统

打开浏览器访问：http://localhost:5000

## 功能特性

### 数据同步
- 支持单个表单和批量表单同步
- 增量同步和全量同步模式
- 自动字段映射和数据转换
- 成员信息缓存优化
- 分批处理大数据量

### 数据搜索
- 全文搜索，支持中文
- 多字段匹配，智能评分
- 搜索结果高亮显示
- 分页浏览
- 搜索建议

### 管理界面
- 表单列表管理
- 同步进度监控
- 搜索结果展示
- 响应式设计

## 核心优势

### 1. 环境简化
- 无需Python运行时
- 无需pip包管理
- 单一JAR文件部署
- 自包含所有依赖

### 2. 性能优化
- 连接池管理
- 批量操作优化
- 异步处理支持
- 内存使用优化

### 3. 部署友好
- 纯内网部署
- 配置文件管理
- 启动脚本自动化
- 日志输出规范

### 4. 扩展性
- 模块化设计
- RESTful API
- 易于二次开发
- 支持集群部署

## 配置说明

### application.yml
```yaml
# 服务器配置
server:
  port: 5000

# 数据源配置
spring:
  datasource:
    url: jdbc:dm://localhost:5236/DAMENG
    username: SYSDBA
    password: SYSDBA

# ES配置
elasticsearch:
  host: localhost:9200
  username: elastic
  password: changeme
```

### 环境变量
- `SERVER_PORT`: 服务端口（默认5000）
- `DM_HOST`: 达梦数据库主机
- `DM_PORT`: 达梦数据库端口
- `DM_USER`: 数据库用户名
- `DM_PASSWORD`: 数据库密码
- `ES_HOST`: Elasticsearch地址
- `ES_USER`: ES用户名
- `ES_PASSWORD`: ES密码

## API接口

### 搜索接口
- `POST /api/search/search` - 执行搜索
- `GET /api/search/record/{formId}/{recordId}` - 获取记录详情

### 同步接口
- `GET /api/sync/forms` - 获取表单列表
- `POST /api/sync/sync/{formId}` - 同步单个表单
- `POST /api/sync/sync/all` - 同步所有表单

## 故障排查

### 常见问题

1. **无法连接数据库**
   - 检查达梦数据库是否运行
   - 验证连接参数是否正确
   - 确认网络连通性

2. **无法连接Elasticsearch**
   - 检查ES服务状态
   - 验证认证信息
   - 确认SSL配置

3. **内存不足**
   - 调整JVM参数：`-Xmx4g -Xms2g`
   - 减少批处理大小
   - 优化查询条件

### 日志查看
```bash
# 查看应用日志
tail -f logs/es-admin.log

# 查看启动日志
journalctl -u es-admin -f
```

## 开发指南

### 项目结构
```
src/main/java/com/esadmin/
├── config/          # 配置类
├── controller/      # 控制器
├── dto/            # 数据传输对象
├── entity/         # 实体类
├── repository/     # 数据访问层
├── service/        # 业务逻辑层
└── EsAdminApplication.java  # 启动类
```

### 扩展开发
1. 添加新的搜索功能
2. 支持更多数据源
3. 增加数据可视化
4. 集成权限管理

## 迁移指南

从Python版本迁移到Java版本：

1. **停止Python服务**
2. **备份数据**（如有必要）
3. **部署Java版本**
4. **验证功能**
5. **更新部署文档**

Java版本完全兼容Python版本的数据格式和API接口，可无缝替换。