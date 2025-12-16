#!/bin/bash

# 设置严格模式
set -euo pipefail

# 定义颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 打印带颜色的消息
print_message() {
    local color=$1
    local message=$2
    echo -e "${color}${message}${NC}"
}

# 允许通过环境变量覆盖的配置
MAX_SHARDS_PER_NODE=${MAX_SHARDS_PER_NODE:-2000}


# 检查操作系统
check_os() {
    if [[ ! -f /etc/os-release ]]; then
        print_message $RED "无法确定操作系统版本"
        exit 1
    fi
    
    . /etc/os-release
    if [[ "$ID" != "ubuntu" ]]; then
        print_message $YELLOW "警告: 此脚本主要为Ubuntu设计，在其他发行版上可能需要调整"
    fi
}

# 检测系统架构
detect_architecture() {
    local arch=$(uname -m)
    case $arch in
        x86_64)
            ES_ARCH="x86_64"
            JDK_ARCH="x64"
            ;;
        aarch64)
            ES_ARCH="aarch64"
            JDK_ARCH="aarch64"
            ;;
        *)
            print_message $RED "不支持的架构: $arch"
            exit 1
            ;;
    esac
    print_message $GREEN "检测到系统架构: $arch (ES: $ES_ARCH, JDK: $JDK_ARCH)"
}

# 安装Java环境
install_java() {
    print_message $YELLOW "正在安装Java环境..."
    
    # 检查Java是否已安装
    if command -v java >/dev/null 2>&1; then
        local java_version=$(java -version 2>&1 | head -n1 | cut -d'"' -f2)
        print_message $GREEN "Java已安装，版本: $java_version"
        return 0
    fi
    
    # 查找JDK压缩包
    local jdk_file=""
    for file in ../OpenJDK11U-jdk_${JDK_ARCH}_linux_hotspot_11.0.19_7.tar.gz; do
        if [[ -f "$file" ]]; then
            jdk_file="$file"
            break
        fi
    done
    
    if [[ -z "$jdk_file" ]]; then
        print_message $RED "未找到JDK压缩包，请确保OpenJDK11U-jdk_${JDK_ARCH}_linux_hotspot_11.0.19_7.tar.gz文件存在于项目根目录"
        print_message $YELLOW "请下载对应架构的JDK文件:"
        print_message $YELLOW "x86_64: https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.19%2B7/OpenJDK11U-jdk_x64_linux_hotspot_11.0.19_7.tar.gz"
        print_message $YELLOW "ARM64: https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.19%2B7/OpenJDK11U-jdk_aarch64_linux_hotspot_11.0.19_7.tar.gz"
        exit 1
    fi
    
    print_message $YELLOW "找到JDK文件: $jdk_file"
    
    # 创建Java安装目录
    local java_home="/opt/java-11-openjdk"
    sudo mkdir -p "$java_home"
    
    # 解压JDK
    print_message $YELLOW "正在解压JDK..."
    sudo tar -xzf "$jdk_file" -C "/opt/" --strip-components=0
    sudo mv /opt/jdk-11.0.19+7 "$java_home/jdk-11.0.19+7"
    
    # 设置权限
    sudo chown -R root:root "$java_home"
    
    # 配置环境变量
    print_message $YELLOW "配置Java环境变量..."
    
    # 添加到/etc/environment
    if ! grep -q "JAVA_HOME" /etc/environment 2>/dev/null; then
        echo "JAVA_HOME=\"$java_home/jdk-11.0.19+7\"" | sudo tee -a /etc/environment >/dev/null
        echo "ES_JAVA_HOME=\"$java_home/jdk-11.0.19+7\"" | sudo tee -a /etc/environment >/dev/null
    fi
    
    # 添加到/etc/profile
    if ! grep -q "JAVA_HOME" /etc/profile 2>/dev/null; then
        echo "export JAVA_HOME=$java_home/jdk-11.0.19+7" | sudo tee -a /etc/profile >/dev/null
        echo "export ES_JAVA_HOME=$java_home/jdk-11.0.19+7" | sudo tee -a /etc/profile >/dev/null
        echo "export PATH=\$JAVA_HOME/bin:\$PATH" | sudo tee -a /etc/profile >/dev/null
    fi
    
    # 添加到当前shell
    export JAVA_HOME="$java_home/jdk-11.0.19+7"
    export ES_JAVA_HOME="$java_home/jdk-11.0.19+7"
    export PATH="$JAVA_HOME/bin:$PATH"
    
    # 验证安装
    if "$java_home/jdk-11.0.19+7/bin/java" -version >/dev/null 2>&1; then
        local installed_version=$("$java_home/jdk-11.0.19+7/bin/java" -version 2>&1 | head -n1 | cut -d'"' -f2)
        print_message $GREEN "Java安装成功，版本: $installed_version"
        print_message $YELLOW "请重新登录或运行 'source /etc/profile' 以使环境变量生效"
    else
        print_message $RED "Java安装失败"
        exit 1
    fi
}

# 创建elasticsearch用户
create_es_user() {
    print_message $YELLOW "创建elasticsearch用户..."
    
    if id "elasticsearch" &>/dev/null; then
        print_message $GREEN "elasticsearch用户已存在"
    else
        sudo useradd -r -s /bin/false elasticsearch
        print_message $GREEN "elasticsearch用户创建成功"
    fi
}

# 安装Elasticsearch
install_elasticsearch() {
    print_message $YELLOW "正在安装Elasticsearch..."
    
    # 查找ES压缩包
    local es_file=""
    for file in ../elasticsearch-7.17.15-linux-${ES_ARCH}.tar.gz; do
        if [[ -f "$file" ]]; then
            es_file="$file"
            break
        fi
    done
    
    if [[ -z "$es_file" ]]; then
        print_message $RED "未找到Elasticsearch压缩包，请确保elasticsearch-7.17.15-linux-${ES_ARCH}.tar.gz文件存在于项目根目录"
        print_message $YELLOW "请下载对应架构的ES文件:"
        print_message $YELLOW "x86_64: https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-7.17.15-linux-x86_64.tar.gz"
        print_message $YELLOW "ARM64: https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-7.17.15-linux-aarch64.tar.gz"
        exit 1
    fi
    
    print_message $YELLOW "找到Elasticsearch文件: $es_file"
    
    # 解压ES
    print_message $YELLOW "正在解压Elasticsearch..."
    sudo tar -xzf "$es_file" -C "/opt/"
    sudo mv "/opt/elasticsearch-7.17.15" "/opt/elasticsearch"
    
    # 设置权限
    sudo chown -R elasticsearch:elasticsearch /opt/elasticsearch
    
    # 创建必要目录
    sudo mkdir -p /opt/elasticsearch/data
    sudo mkdir -p /opt/elasticsearch/logs
    sudo mkdir -p /opt/elasticsearch/tmp
    sudo chown -R elasticsearch:elasticsearch /opt/elasticsearch/data
    sudo chown -R elasticsearch:elasticsearch /opt/elasticsearch/logs
    sudo chown -R elasticsearch:elasticsearch /opt/elasticsearch/tmp
    
    # 修复目录权限
    sudo chown -R elasticsearch:elasticsearch /opt/elasticsearch
    
    print_message $GREEN "Elasticsearch安装完成"
}

# 配置Elasticsearch
configure_elasticsearch() {
    print_message $YELLOW "配置Elasticsearch..."
    
    # 备份原配置文件
    if [[ -f /opt/elasticsearch/config/elasticsearch.yml ]]; then
        sudo cp /opt/elasticsearch/config/elasticsearch.yml /opt/elasticsearch/config/elasticsearch.yml.backup
    fi
    
    # 写入配置文件
    sudo tee /opt/elasticsearch/config/elasticsearch.yml > /dev/null <<EOF
# ======================== Elasticsearch Configuration =========================

# 集群名称
cluster.name: es-admin-cluster

# 节点名称  
node.name: node-1

# 数据和日志路径
path.data: /opt/elasticsearch/data
path.logs: /opt/elasticsearch/logs

# 网络配置
network.host: 0.0.0.0
http.port: 9200

# 集群配置（单节点模式）
discovery.type: single-node
cluster.max_shards_per_node: ${MAX_SHARDS_PER_NODE}

# 安全配置（适配ES 7.17.15）
xpack.security.enabled: true
xpack.ml.enabled: false
xpack.monitoring.enabled: false
xpack.watcher.enabled: false

ingest.geoip.downloader.enabled: false


# 性能配置
bootstrap.memory_lock: false
indices.fielddata.cache.size: 40%
indices.breaker.fielddata.limit: 60%
indices.breaker.request.limit: 40%
indices.breaker.total.limit: 95%

http.max_initial_line_length: 32k
http.max_header_size: 32k

thread_pool.write.queue_size: 2000
thread_pool.search.queue_size: 2000

# 其他配置
action.auto_create_index: true
cluster.routing.allocation.disk.threshold_enabled: true
cluster.routing.allocation.disk.watermark.low: 85%
cluster.routing.allocation.disk.watermark.high: 90%
cluster.routing.allocation.disk.watermark.flood_stage: 95%
EOF

    # 配置JVM参数
    print_message $YELLOW "配置JVM参数..."
    
    # 备份原JVM配置
    if [[ -f /opt/elasticsearch/config/jvm.options ]]; then
        sudo cp /opt/elasticsearch/config/jvm.options /opt/elasticsearch/config/jvm.options.backup
    fi
    
    # 获取系统内存
    local total_mem=$(free -m | awk 'NR==2{print $2}')
    local heap_size="1g"
    
    # 检测是否为WSL2环境
    local is_wsl2=false
    if grep -qi "microsoft.*wsl" /proc/version 2>/dev/null; then
        is_wsl2=true
        print_message $YELLOW "检测到WSL2环境，使用保守的内存设置"
    fi
    
    # WSL2环境使用更保守的内存分配策略
    if [[ "$is_wsl2" == true ]]; then
        if [[ $total_mem -gt 16000 ]]; then
            heap_size="4g"  # WSL2环境下最大4GB
        elif [[ $total_mem -gt 8000 ]]; then
            heap_size="3g"
        elif [[ $total_mem -gt 4000 ]]; then
            heap_size="2g"
        elif [[ $total_mem -gt 2000 ]]; then
            heap_size="1g"
        else
            heap_size="512m"
        fi
    else
        # 原生Linux环境可以使用更大内存
        if [[ $total_mem -gt 32000 ]]; then
            heap_size="8g"
        elif [[ $total_mem -gt 16000 ]]; then
            heap_size="6g"
        elif [[ $total_mem -gt 8000 ]]; then
            heap_size="4g"
        elif [[ $total_mem -gt 4000 ]]; then
            heap_size="2g"
        elif [[ $total_mem -gt 2000 ]]; then
            heap_size="1g"
        else
            heap_size="512m"
        fi
    fi
    
    print_message $YELLOW "系统内存: ${total_mem}MB, 设置JVM堆内存: $heap_size"
    
    # 写入JVM配置
    sudo tee /opt/elasticsearch/config/jvm.options > /dev/null <<EOF
# JVM heap size
-Xms$heap_size
-Xmx$heap_size

# GC configuration
-XX:+UseG1GC
-XX:G1HeapRegionSize=16m
-XX:+DisableExplicitGC

# Temp directory
-Djava.io.tmpdir=/opt/elasticsearch/tmp

# Log4j2 configuration
-Dlog4j.shutdownHookEnabled=false
-Dlog4j2.disable.jmx=true
-Dlog4j2.formatMsgNoLookups=true

# Network and security
-Djava.security.policy=all.policy
-Djava.awt.headless=true
-Dfile.encoding=UTF-8
-Djna.nosys=true
-XX:-OmitStackTraceInFastThrow
-Dio.netty.noUnsafe=true
-Dio.netty.noKeySetOptimization=true
-Dio.netty.recycler.maxCapacityPerThread=0
-Dio.netty.allocator.numDirectArenas=0

# JVM options
-Xss1m
-XX:+AlwaysPreTouch
-server
-Djava.locale.providers=SPI,COMPAT
--add-opens=java.base/java.io=ALL-UNNAMED

# Architecture specific optimizations
EOF

    # ARM64特定优化
    if [[ "$ES_ARCH" == "aarch64" ]]; then
        sudo tee -a /opt/elasticsearch/config/jvm.options > /dev/null <<EOF

# ARM64 specific optimizations
-XX:+UnlockExperimentalVMOptions
-XX:+UseTransparentHugePages
EOF
    fi
    
    print_message $GREEN "Elasticsearch配置完成"
}

# 配置系统参数
configure_system() {
    print_message $YELLOW "配置系统参数..."
    
    # 配置文件描述符限制
    if ! grep -q "elasticsearch.*nofile" /etc/security/limits.conf; then
        sudo tee -a /etc/security/limits.conf > /dev/null <<EOF
elasticsearch soft nofile 65536
elasticsearch hard nofile 65536
elasticsearch soft nproc 4096
elasticsearch hard nproc 4096
EOF
    fi
    
    # 配置内核参数
    if ! grep -q "vm.max_map_count" /etc/sysctl.conf; then
        echo "vm.max_map_count=262144" | sudo tee -a /etc/sysctl.conf
        sudo sysctl -p
    fi
    
    print_message $GREEN "系统参数配置完成"
}

# 创建systemd服务
create_systemd_service() {
    print_message $YELLOW "创建systemd服务..."
    
    sudo tee /etc/systemd/system/elasticsearch.service > /dev/null <<EOF
[Unit]
Description=Elasticsearch
Documentation=https://www.elastic.co
Wants=network-online.target
After=network-online.target

[Service]
Type=exec
RuntimeDirectory=elasticsearch
PrivateTmp=true
Environment=ES_HOME=/opt/elasticsearch
Environment=ES_PATH_CONF=/opt/elasticsearch/config
Environment=PID_DIR=/var/run/elasticsearch
Environment=ES_JAVA_HOME=/opt/java-11-openjdk/jdk-11.0.19+7
Environment=JAVA_HOME=/opt/java-11-openjdk/jdk-11.0.19+7
WorkingDirectory=/opt/elasticsearch
User=elasticsearch
Group=elasticsearch
ExecStart=/opt/elasticsearch/bin/elasticsearch
StandardOutput=journal
StandardError=inherit
LimitNOFILE=65536
LimitNPROC=4096
LimitAS=infinity
LimitFSIZE=infinity
TimeoutStartSec=300
TimeoutStopSec=180
KillSignal=SIGTERM
KillMode=process
SendSIGKILL=no
SuccessExitStatus=143
Restart=on-failure
RestartSec=30

[Install]
WantedBy=multi-user.target
EOF

    # 重载systemd配置
    sudo systemctl daemon-reload
    
    print_message $GREEN "systemd服务创建完成"
}

# 启动和验证Elasticsearch
start_and_verify() {
    print_message $YELLOW "启动Elasticsearch..."
    
    # 启用并启动服务
    sudo systemctl enable elasticsearch
    sudo systemctl start elasticsearch
    
    # 等待启动
    print_message $YELLOW "等待Elasticsearch启动..."
    local retry_count=0
    while [[ $retry_count -lt 60 ]]; do
        if curl -s http://localhost:9200 >/dev/null 2>&1; then
            break
        fi
        sleep 2
        ((retry_count++))
    done
    
    # 验证启动状态
    if curl -s http://localhost:9200 >/dev/null 2>&1; then
        print_message $GREEN "Elasticsearch启动成功"
        print_message $GREEN "版本信息:"
        curl -s http://localhost:9200 | python3 -m json.tool 2>/dev/null || curl -s http://localhost:9200
    else
        print_message $RED "Elasticsearch启动失败"
        print_message $YELLOW "检查日志: sudo journalctl -u elasticsearch -f"
        exit 1
    fi
}

# 设置用户密码
setup_passwords() {
    print_message $YELLOW "设置Elasticsearch用户密码..."
    
    # 设置环境变量
    export ES_PATH_CONF=/opt/elasticsearch/config
    export JAVA_HOME=/opt/java-11-openjdk/jdk-11.0.19+7
    
    # 创建密码文件
    local password_file="/opt/elasticsearch/passwords.txt"
    
    # 使用elasticsearch用户运行密码设置命令
    sudo -u elasticsearch /opt/elasticsearch/bin/elasticsearch-setup-passwords auto -b > "$password_file" 2>&1
    
    if [[ $? -eq 0 ]]; then
        print_message $GREEN "密码设置成功!"
        print_message $GREEN "密码信息已保存到: $password_file"
        print_message $YELLOW "请记录以下密码信息:"
        sudo cat "$password_file"
        
        # 提取elastic用户密码
        local elastic_password=$(sudo grep "PASSWORD elastic" "$password_file" | awk '{print $4}')
        
        if [[ -n "$elastic_password" ]]; then
            print_message $GREEN "测试elastic用户认证..."
            if curl -u "elastic:$elastic_password" -s http://localhost:9200/_cluster/health >/dev/null 2>&1; then
                print_message $GREEN "elastic用户认证成功!"
                print_message $YELLOW "主要登录信息:"
                print_message $YELLOW "  用户名: elastic"
                print_message $YELLOW "  密码: $elastic_password"
                print_message $YELLOW "  测试命令: curl -u elastic:$elastic_password http://localhost:9200"
            else
                print_message $RED "认证测试失败"
            fi
        fi
    else
        print_message $RED "密码设置失败"
        print_message $YELLOW "错误信息:"
        sudo cat "$password_file"
        print_message $YELLOW "你可以稍后手动设置密码:"
        print_message $YELLOW "  sudo -u elasticsearch /opt/elasticsearch/bin/elasticsearch-setup-passwords interactive"
    fi
}

# 主函数
main() {
    print_message $GREEN "开始安装Elasticsearch..."
    
    check_os
    detect_architecture
    install_java
    create_es_user
    install_elasticsearch
    configure_elasticsearch
    configure_system
    create_systemd_service
    start_and_verify
    setup_passwords
    
    print_message $GREEN "Elasticsearch安装完成!"
    print_message $YELLOW ""
    print_message $YELLOW "=== 服务管理命令 ==="
    print_message $YELLOW "  启动: sudo systemctl start elasticsearch"
    print_message $YELLOW "  停止: sudo systemctl stop elasticsearch"
    print_message $YELLOW "  重启: sudo systemctl restart elasticsearch"
    print_message $YELLOW "  状态: sudo systemctl status elasticsearch"
    print_message $YELLOW "  日志: sudo journalctl -u elasticsearch -f"
    print_message $YELLOW ""
    print_message $YELLOW "=== 访问信息 ==="
    print_message $YELLOW "  地址: http://localhost:9200"
    print_message $YELLOW "  密码文件: /opt/elasticsearch/passwords.txt"
    print_message $YELLOW "  每节点最大分片: ${MAX_SHARDS_PER_NODE} (运行脚本前可通过 export MAX_SHARDS_PER_NODE=值 覆盖)"
    print_message $YELLOW ""
    print_message $YELLOW "=== 环境变量 ==="
    print_message $YELLOW "如需手动运行ES命令，请先设置:"
    print_message $YELLOW "  export ES_JAVA_HOME=/opt/java-11-openjdk/jdk-11.0.19+7"
    print_message $YELLOW "  export JAVA_HOME=/opt/java-11-openjdk/jdk-11.0.19+7"
}

# 运行主函数
main "$@"
