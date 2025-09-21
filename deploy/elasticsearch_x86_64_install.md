# Elasticsearch 7.17.9 - x86_64 架构安装说明

## 文件信息
- 架构: x86_64
- 版本: 7.17.9
- 文件: elasticsearch-7.17.9-linux-x86_64.tar.gz

## ARM架构特别注意事项

### 1. 内存要求
- 最小内存: 2GB
- 推荐内存: 4GB+
- JVM堆内存建议设置为物理内存的25-50%

### 2. 性能调优
ARM架构建议的JVM参数：
```
# ARM优化的JVM参数
-Xms1g
-Xmx1g
-XX:+UseG1GC
-XX:+UnlockExperimentalVMOptions
-XX:+UseJVMCICompiler  # 如果支持GraalVM
```

### 3. 安装步骤
```bash
# 解压
tar -xzf elasticsearch-7.17.9-linux-x86_64.tar.gz -C /usr/local/
cd /usr/local/elasticsearch-7.17.9

# 设置权限
useradd elasticsearch
chown -R elasticsearch:elasticsearch .

# 启动测试
sudo -u elasticsearch ./bin/elasticsearch
```

### 4. 配置优化
针对ARM架构的elasticsearch.yml配置：
```yaml
# 集群配置
cluster.name: es-arm-cluster
node.name: es-arm-node-1

# 内存锁定（ARM架构重要）
bootstrap.memory_lock: true

# 网络配置
network.host: 0.0.0.0
http.port: 9200

# 数据路径
path.data: /data/elasticsearch/data
path.logs: /data/elasticsearch/logs

# ARM架构优化
index.number_of_shards: 1
index.number_of_replicas: 0
indices.memory.index_buffer_size: 20%

# 刷新频率调整（ARM性能优化）
index.refresh_interval: 30s
```

### 5. 性能预期
| 指标 | x86_64 | aarch64 | 说明 |
|------|--------|---------|------|
| 索引速度 | 100% | 80-90% | ARM性能略低 |
| 查询速度 | 100% | 85-95% | 查询性能较好 |
| 内存使用 | 标准 | 相似 | 内存效率相当 |
| 启动时间 | 快 | 稍慢 | JVM预热时间长 |

### 6. 故障排除
常见ARM架构问题：
1. **内存不足**: 减少JVM堆内存设置
2. **启动慢**: 正常现象，ARM架构JVM启动较慢
3. **CPU使用率高**: 调整refresh_interval和批处理参数
4. **磁盘IO**: 使用SSD存储提升性能

### 7. 兼容性
- ✅ 支持所有ES核心功能
- ✅ 支持中文分词插件
- ✅ 支持X-Pack安全功能
- ⚠️  某些第三方插件可能不兼容ARM
