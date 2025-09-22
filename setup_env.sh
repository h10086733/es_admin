#!/bin/bash

# ES数据管理系统环境设置脚本
echo "设置Java和Maven环境变量..."

export JAVA_HOME=/opt/java-11-openjdk/jdk-11.0.19+7
export M2_HOME=~/maven
export PATH=$JAVA_HOME/bin:$M2_HOME/bin:$PATH

echo "Java版本："
java -version

echo "Maven版本："
mvn -version

echo "环境变量设置完成！"
echo "现在可以运行 mvn clean package -DskipTests 来构建项目"