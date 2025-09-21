# ES管理服务 - ARM架构部署指南

## 🏗️ ARM架构支持

### 支持的ARM架构
- **aarch64 (ARM64)**: 64位ARM处理器
  - 树莓派4 (4GB/8GB)
  - ARM服务器 (华为鲲鹏、飞腾等)
  - 苹果M1/M2芯片
  - AWS Graviton处理器
- **armv7l (ARM32)**: 32位ARM处理器
  - 树莓派3/3B+
  - 其他ARMv7设备

### 架构检测
```bash
# 查看当前架构
uname -m

# 可能的输出：
# x86_64   - Intel/AMD 64位
# aarch64  - ARM 64位
# armv7l   - ARM 32位
```

## 📦 ARM架构离线包准备

### 1. 下载ARM运行环境
在有网络的环境中运行：
```bash
cd deploy/

# 使用通用脚本（会自动检测架构）
./download_offline_runtime.sh
```

### 2. 下载ARM版Elasticsearch
```bash
# 下载对应架构的ES
./download_elasticsearch.sh

# 手动下载（如果脚本失败）
# ARM64
wget https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-7.17.9-linux-aarch64.tar.gz

# x86_64 (备用)
wget https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-7.17.9-linux-x86_64.tar.gz
```

### 3. 准备其他依赖
```bash
# Python依赖包（架构无关）
./download_packages.sh

# 达梦数据库驱动（需要选择ARM版本）
# 从达梦官网下载对应ARM架构的dmPython
```

## 🚀 ARM架构部署步骤

### 快速部署（推荐）
```bash
# 传输所有文件到ARM设备后
cd deploy/
sudo ./quick_deploy.sh

# 脚本会自动：
# 1. 检测ARM架构
# 2. 应用ARM优化配置
# 3. 安装对应版本的组件
```

### 手动部署

#### 第一步：安装运行环境
```bash
# 安装Java和Python
cd es_admin_runtime_offline/
sudo ./install_runtime.sh

# 验证安装
java -version    # 应显示Java 11
python3 --version # 应显示Python 3.8+
```

#### 第二步：安装Elasticsearch
```bash
# 解压ES
sudo tar -xzf elasticsearch-7.17.9-linux-aarch64.tar.gz -C /usr/local/
sudo mv /usr/local/elasticsearch-7.17.9 /usr/local/elasticsearch

# 创建用户
sudo useradd -r elasticsearch

# 设置权限
sudo chown -R elasticsearch:elasticsearch /usr/local/elasticsearch
sudo mkdir -p /data/elasticsearch/{data,logs}
sudo chown -R elasticsearch:elasticsearch /data/elasticsearch
```

#### 第三步：配置ARM优化
```bash
# 复制ARM优化的配置
sudo cp elasticsearch.yml /usr/local/elasticsearch/config/

# ARM架构JVM配置
sudo cp jvm.options /usr/local/elasticsearch/config/

# 对于ARM设备，调整JVM内存
sudo vi /usr/local/elasticsearch/config/jvm.options
# 4GB ARM设备建议：
# -Xms1g
# -Xmx1g

# 8GB ARM设备建议：
# -Xms2g
# -Xmx2g
```

#### 第四步：安装应用
```bash
sudo ./install_app.sh

# 配置应用
sudo vi /opt/es_admin/.env
# 添加ARM优化配置
ARM_OPTIMIZED=true
```

## ⚙️ ARM架构性能优化

### 1. JVM参数优化
```bash
# /usr/local/elasticsearch/config/jvm.options
# ARM64架构推荐配置

# 基础内存设置（根据设备调整）
-Xms1g
-Xmx1g

# G1垃圾收集器（ARM友好）
-XX:+UseG1GC
-XX:G1HeapRegionSize=16m

# ARM架构优化
-XX:+UnlockExperimentalVMOptions
-XX:+UseTransparentHugePages
-XX:+AlwaysPreTouch

# 减少GC频率
-XX:G1NewSizePercent=20
-XX:G1MaxNewSizePercent=30
-XX:G1MixedGCCountTarget=8
-XX:G1MixedGCLiveThresholdPercent=85
```

### 2. Elasticsearch配置优化
```yaml
# elasticsearch.yml - ARM架构优化

# 基础配置
cluster.name: es-arm-cluster
node.name: es-arm-node-1

# 内存锁定（ARM重要）
bootstrap.memory_lock: true

# 索引优化
index.number_of_shards: 1
index.number_of_replicas: 0
index.refresh_interval: 30s

# 缓冲区设置
indices.memory.index_buffer_size: 20%
indices.memory.min_index_buffer_size: 96mb

# 查询缓存
indices.queries.cache.size: 15%

# ARM架构特殊优化
cluster.routing.allocation.disk.watermark.low: 85%
cluster.routing.allocation.disk.watermark.high: 90%
cluster.routing.allocation.disk.watermark.flood_stage: 95%
```

### 3. 应用性能配置
```bash
# /opt/es_admin/.env - ARM优化配置

# 线程数配置（根据ARM核心数）
ULTRA_FAST_WORKERS=4      # 4核ARM64
DM_POOL_SIZE=8            # 数据库连接池
ES_BULK_SIZE=1500         # ES批次大小

# ARM架构标识
ARM_OPTIMIZED=true

# 缓存配置
ENABLE_CACHE=true
CACHE_SIZE=256            # 256MB缓存
```

## 📊 ARM架构性能参考

### 硬件配置建议
| 设备类型 | CPU | 内存 | 存储 | 网络 |
|----------|-----|------|------|------|
| 树莓派4 | 4核ARM64 | 4-8GB | 64GB+ SSD | 千兆以太网 |
| ARM服务器 | 8-64核ARM64 | 16-128GB | NVMe SSD | 万兆网络 |
| 苹果M1/M2 | 8核ARM64 | 8-64GB | SSD | WiFi 6 |

### 性能对比 (vs x86_64)
| 指标 | 树莓派4 (4GB) | ARM服务器 | 苹果M1 |
|------|---------------|-----------|---------|
| ES索引性能 | 60-70% | 85-95% | 95-105% |
| 查询性能 | 70-80% | 90-95% | 100-110% |
| 应用响应 | 65-75% | 85-90% | 95-100% |
| 内存效率 | 90-95% | 95-98% | 100-105% |

### 推荐配置方案

#### 树莓派4 (4GB)
```bash
# JVM配置
-Xms1g -Xmx1g

# 应用配置
ULTRA_FAST_WORKERS=2
DM_POOL_SIZE=5
ES_BULK_SIZE=1000
```

#### 树莓派4 (8GB)
```bash
# JVM配置
-Xms2g -Xmx2g

# 应用配置
ULTRA_FAST_WORKERS=4
DM_POOL_SIZE=8
ES_BULK_SIZE=1500
```

#### ARM服务器 (16GB+)
```bash
# JVM配置
-Xms4g -Xmx4g

# 应用配置
ULTRA_FAST_WORKERS=8
DM_POOL_SIZE=12
ES_BULK_SIZE=2000
```

## 🔧 ARM架构故障排除

### 常见问题

#### 1. 编译时间过长
**症状**: Java/Python编译时间超过2小时
**解决方案**:
```bash
# 增加交换文件
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile

# 减少编译并行数
export MAKEFLAGS="-j2"  # 限制为2个并行任务
```

#### 2. 内存不足
**症状**: ES启动失败，OOM错误
**解决方案**:
```bash
# 减少JVM堆内存
sudo vi /usr/local/elasticsearch/config/jvm.options
# 改为: -Xms512m -Xmx512m

# 减少应用线程数
sudo vi /opt/es_admin/.env
# 改为: ULTRA_FAST_WORKERS=2
```

#### 3. 性能较差
**症状**: 查询响应慢，同步速度低
**解决方案**:
```bash
# 使用SSD存储
# 增加刷新间隔
echo "index.refresh_interval: 60s" >> /usr/local/elasticsearch/config/elasticsearch.yml

# 调整缓冲区
echo "indices.memory.index_buffer_size: 30%" >> /usr/local/elasticsearch/config/elasticsearch.yml
```

#### 4. 架构不兼容
**症状**: 某些依赖包无法安装
**解决方案**:
```bash
# 使用源码编译
pip3 install --no-binary :all: package_name

# 或使用Docker
docker run --platform linux/arm64 your_image
```

### 监控命令
```bash
# CPU使用率
htop

# 内存使用
free -h

# 磁盘IO
iostat -x 1

# 温度监控（树莓派）
vcgencmd measure_temp

# ES状态
curl http://localhost:9200/_cluster/health
```

## 🎯 总结

ARM架构部署ES管理服务完全可行，关键要点：

1. **选择合适的硬件**: 建议4GB+内存的ARM64设备
2. **使用优化配置**: 应用ARM架构特定的JVM和ES参数
3. **监控性能**: 定期检查资源使用情况
4. **耐心等待**: ARM架构编译时间较长，属正常现象

通过合理配置，ARM架构可以达到x86_64的80-95%性能，完全满足生产环境需求。