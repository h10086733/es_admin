# ES数据管理系统

这是一个用于将达梦数据库中的表单数据同步到Elasticsearch并提供搜索功能的管理系统。

## 功能特性

- **数据同步**: 支持全量同步和增量同步达梦数据库中的表单数据到Elasticsearch
- **智能搜索**: 提供全文搜索功能，支持模糊匹配和高亮显示
- **表单管理**: 自动解析表单配置，支持多个表单数据管理
- **Web界面**: 简洁易用的Web管理界面

## 系统架构

```
├── app/                    # 应用主目录
│   ├── models/            # 数据模型
│   │   └── form_model.py  # 表单数据模型
│   ├── services/          # 业务服务
│   │   └── sync_service.py # 数据同步服务
│   └── views/             # 视图控制器
│       ├── sync_views.py  # 同步相关API
│       └── search_views.py # 搜索相关API
├── config/                # 配置文件
│   └── database.py        # 数据库连接配置
├── static/                # 静态文件
│   └── index.html         # Web管理界面
└── .env                   # 环境配置文件
```

## 安装和运行

### 1. 安装依赖

```bash
pip install -r requirements.txt
```

### 2. 配置环境变量

编辑 `.env` 文件，配置数据库和Elasticsearch连接信息：

```env
# 达梦数据库配置
DM_HOST=192.168.31.157
DM_PORT=5236
DM_USER=SEEYON0725
DM_PASSWORD=seeyon@123
DM_DATABASE=SEEYON0725

# Elasticsearch配置
ES_HOST=https://192.168.31.141:9200
ES_USER=elastic
ES_PASSWORD=dyWYSbEAXTdkgWtCkiBh
```

### 3. 启动应用

```bash
python run.py
```

或者：

```bash
python app.py
```

### 4. 访问系统

打开浏览器访问: http://localhost:5000

## API接口

### 同步相关接口

- `GET /api/sync/forms` - 获取所有表单列表
- `POST /api/sync/sync/<form_id>` - 同步指定表单数据
- `POST /api/sync/sync/all` - 同步所有表单数据
- `GET /api/sync/status` - 获取同步状态

### 搜索相关接口

- `POST /api/search/search` - 搜索数据
- `POST /api/search/suggest` - 搜索建议
- `GET /api/search/record/<form_id>/<record_id>` - 获取记录详情

## 使用说明

### 数据同步

1. 在Web界面中切换到"数据同步"标签页
2. 点击"刷新表单列表"加载所有可用表单
3. 选择需要同步的表单，点击"全量同步"或"增量同步"
4. 可以点击"同步所有表单"一次性同步所有数据

### 数据搜索

1. 在Web界面中切换到"数据搜索"标签页
2. 在搜索框中输入关键词，如"台式电脑2"
3. 点击"搜索"按钮或按回车键执行搜索
4. 搜索结果会显示匹配的记录，包括：
   - 表单名称和匹配度
   - 表单ID、表名、记录ID
   - 完整的记录数据（高亮显示匹配内容）

## 技术实现

### 数据同步机制

1. **表单配置解析**: 从`CAP_FORM_DEFINITION`表中读取表单配置，解析字段定义和表名
2. **索引映射创建**: 根据表单字段类型自动创建Elasticsearch索引映射
3. **数据转换同步**: 将达梦数据库中的数据转换为JSON格式同步到ES
4. **增量同步**: 基于`modify_date`字段实现增量数据同步

### 搜索功能

1. **全文搜索**: 使用Elasticsearch的multi_match查询实现全文搜索
2. **模糊匹配**: 支持AUTO模糊匹配，提高搜索准确性
3. **高亮显示**: 搜索结果中匹配的内容会高亮显示
4. **相关性排序**: 按照匹配度和时间进行排序

## 注意事项

1. 确保达梦数据库和Elasticsearch服务正常运行
2. 首次使用建议先进行全量同步
3. 增量同步依赖于数据表中的`modify_date`字段
4. 搜索功能需要先完成数据同步

## 故障排除

### 常见问题

1. **数据库连接失败**: 检查数据库配置信息和网络连通性
2. **ES连接失败**: 检查ES服务状态和认证信息
3. **同步失败**: 检查表单配置是否正确，数据表是否存在
4. **搜索无结果**: 确认数据已经同步到ES，检查索引是否创建成功# es_admin
# es_admin
