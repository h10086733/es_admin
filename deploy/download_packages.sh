#!/bin/bash
# Python离线依赖包下载脚本
# 使用方法：在有网络的环境中运行此脚本，生成离线安装包

echo "=== ES管理服务 - Python离线依赖包下载脚本 ==="

# 创建离线包目录
OFFLINE_DIR="es_admin_offline_packages"
mkdir -p $OFFLINE_DIR

echo "创建离线包目录: $OFFLINE_DIR"

# 下载Python依赖包
echo "开始下载Python依赖包..."

pip download \
    flask==2.3.3 \
    flask-cors==4.0.0 \
    python-dotenv==1.0.0 \
    elasticsearch==7.17.0 \
    schedule==1.2.0 \
    certifi \
    charset-normalizer \
    click \
    idna \
    itsdangerous \
    Jinja2 \
    MarkupSafe \
    requests \
    urllib3 \
    Werkzeug \
    --dest $OFFLINE_DIR

echo "Python依赖包下载完成！"

# 创建离线安装脚本
cat > $OFFLINE_DIR/install_offline.sh << 'EOF'
#!/bin/bash
# 离线安装脚本

echo "=== ES管理服务 - 离线安装脚本 ==="

# 检查pip是否存在
if ! command -v pip &> /dev/null; then
    echo "错误: 未找到pip命令，请先安装Python和pip"
    exit 1
fi

echo "开始离线安装Python依赖包..."

# 安装所有下载的包
pip install --no-index --find-links . *.whl *.tar.gz

echo "离线安装完成！"

# 验证关键包是否安装成功
echo "验证安装结果："
python -c "import flask; print('Flask版本:', flask.__version__)"
python -c "import elasticsearch; print('Elasticsearch版本:', elasticsearch.__version__)"
python -c "import schedule; print('Schedule安装成功')"

echo "所有依赖包安装完成！"
EOF

chmod +x $OFFLINE_DIR/install_offline.sh

# 创建达梦数据库驱动说明
cat > $OFFLINE_DIR/dmPython_README.md << 'EOF'
# 达梦数据库驱动安装说明

## dmPython驱动下载
由于dmPython是达梦数据库的商业驱动，需要从达梦官网下载：

1. 访问达梦官网：https://www.dameng.com/
2. 进入下载中心 -> 驱动下载
3. 选择对应的Python版本和操作系统版本
4. 下载dmPython安装包

## 安装方法

### 方法1：使用whl文件安装
```bash
pip install dmPython-xxx.whl
```

### 方法2：使用tar.gz文件安装
```bash
tar -xzf dmPython-xxx.tar.gz
cd dmPython-xxx
python setup.py install
```

## 验证安装
```python
import dmPython
print("dmPython安装成功")
```

## 注意事项
1. 确保选择与您的Python版本匹配的dmPython版本
2. 某些版本可能需要安装Visual C++运行库
3. 如遇到问题，请参考达梦官方文档
EOF

# 生成版本信息文件
cat > $OFFLINE_DIR/package_versions.txt << 'EOF'
# ES管理服务依赖包版本信息

flask==2.3.3
flask-cors==4.0.0
python-dotenv==1.0.0
elasticsearch==7.17.0
schedule==1.2.0

# Python版本要求
Python >= 3.7

# 达梦数据库驱动
dmPython (请从达梦官网下载对应版本)

# 构建时间
EOF

date >> $OFFLINE_DIR/package_versions.txt

echo ""
echo "=== 下载完成 ==="
echo "离线包目录: $OFFLINE_DIR"
echo "文件列表:"
ls -la $OFFLINE_DIR
echo ""
echo "请将整个 $OFFLINE_DIR 目录复制到内网环境中进行安装"
echo "在内网环境中运行: cd $OFFLINE_DIR && ./install_offline.sh"