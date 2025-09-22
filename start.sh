#!/bin/bash

# 设置严格模式
set -euo pipefail

# 设置Java和Maven环境变量
export JAVA_HOME=/opt/java-11-openjdk/jdk-11.0.19+7
export M2_HOME=~/maven
export PATH=$JAVA_HOME/bin:$M2_HOME/bin:$PATH

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

# 加载环境变量
load_env() {
    if [[ -f .env ]]; then
        print_message $GREEN "加载环境变量文件: .env"
        set -o allexport
        source .env
        set +o allexport
    else
        print_message $YELLOW "未找到.env文件，请先创建配置文件"
        print_message $YELLOW "可以复制模板: cp .env.example .env"
        exit 1
    fi
}

# 检查Java环境
check_java() {
    if ! command -v java >/dev/null 2>&1; then
        print_message $RED "未找到Java环境"
        print_message $YELLOW "请先安装Java或运行: source /etc/profile"
        exit 1
    fi
    
    local java_version=$(java -version 2>&1 | head -n1 | cut -d'"' -f2)
    print_message $GREEN "Java版本: $java_version"
}

# 检查JAR文件
check_jar() {
    local jar_file=""
    
    # 查找JAR文件
    if [[ -f "target/es-admin-1.0.0.jar" ]]; then
        jar_file="target/es-admin-1.0.0.jar"
    elif [[ -f "es-admin-1.0.0.jar" ]]; then
        jar_file="es-admin-1.0.0.jar"
    else
        print_message $RED "未找到JAR文件"
        print_message $YELLOW "请先构建项目: mvn clean package -DskipTests"
        exit 1
    fi
    
    print_message $GREEN "找到JAR文件: $jar_file"
    JAR_FILE="$jar_file"
}

# 检查Elasticsearch连接
check_elasticsearch() {
    local es_host=${ES_HOST:-localhost:9200}
    print_message $YELLOW "检查Elasticsearch连接: $es_host"
    
    if curl -f -s -u "${ES_USER:-elastic}:${ES_PASSWORD:-}" "http://$es_host" >/dev/null 2>&1; then
        print_message $GREEN "Elasticsearch连接正常"
    else
        print_message $RED "无法连接到Elasticsearch: $es_host"
        print_message $YELLOW "请确保Elasticsearch服务已启动并配置正确的认证信息"
        exit 1
    fi
}

# 创建日志目录
create_log_dir() {
    local log_dir="logs"
    if [[ ! -d "$log_dir" ]]; then
        mkdir -p "$log_dir"
        print_message $GREEN "创建日志目录: $log_dir"
    fi
}

# 获取系统架构并设置JVM参数
get_jvm_options() {
    local arch=$(uname -m)
    local total_mem=$(free -m | awk 'NR==2{print $2}')
    
    # 根据内存大小设置堆内存
    local heap_size="1g"
    if [[ $total_mem -gt 8000 ]]; then
        heap_size="4g"
    elif [[ $total_mem -gt 4000 ]]; then
        heap_size="2g"
    fi
    
    print_message $YELLOW "系统架构: $arch, 内存: ${total_mem}MB, JVM堆内存: $heap_size"
    
    # 基础JVM参数
    JVM_OPTS="-Xmx$heap_size -Xms$heap_size"
    JVM_OPTS="$JVM_OPTS -Dfile.encoding=UTF-8"
    JVM_OPTS="$JVM_OPTS -XX:+UseG1GC"
    JVM_OPTS="$JVM_OPTS -XX:+UseContainerSupport"
    JVM_OPTS="$JVM_OPTS -XX:MaxRAMPercentage=75.0"
    JVM_OPTS="$JVM_OPTS -Djava.security.egd=file:/dev/./urandom"
    
    # ARM64特定优化
    if [[ "$arch" == "aarch64" ]]; then
        JVM_OPTS="$JVM_OPTS -XX:+UnlockExperimentalVMOptions"
        JVM_OPTS="$JVM_OPTS -XX:+UseTransparentHugePages"
    fi
    
    # 添加日志配置
    JVM_OPTS="$JVM_OPTS -Dlogging.file.path=./logs"
    JVM_OPTS="$JVM_OPTS -Dlogging.level.root=INFO"
}

# 显示配置信息
show_config() {
    print_message $GREEN "=== 启动配置 ==="
    print_message $YELLOW "服务端口: ${SERVER_PORT:-5000}"
    print_message $YELLOW "数据库: ${DM_HOST:-localhost}:${DM_PORT:-5236}"
    print_message $YELLOW "ES地址: ${ES_HOST:-localhost:9200}"
    print_message $YELLOW "JAR文件: $JAR_FILE"
    print_message $YELLOW "JVM参数: $JVM_OPTS"
    print_message $GREEN "==================="
}

# 启动应用
start_application() {
    print_message $GREEN "启动ES数据管理系统..."
    
    # 设置应用参数
    local app_opts=""
    app_opts="$app_opts --server.port=${SERVER_PORT:-5000}"
    
    # 构建完整的启动命令
    local start_cmd="java $JVM_OPTS -jar $JAR_FILE $app_opts"
    
    print_message $YELLOW "执行命令: $start_cmd"
    print_message $GREEN "应用启动中，访问地址: http://localhost:${SERVER_PORT:-5000}"
    print_message $YELLOW "按 Ctrl+C 停止服务"
    
    # 启动应用
    exec $start_cmd
}

# 主函数
main() {
    print_message $GREEN "ES数据管理系统启动脚本"
    print_message $GREEN "================================"
    
    load_env
    check_java
    check_jar
    check_elasticsearch
    create_log_dir
    get_jvm_options
    show_config
    start_application
}

# 运行主函数
main "$@"