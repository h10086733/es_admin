import dmPython
import os
from elasticsearch import Elasticsearch
import ssl

class DMConnection:
    def __init__(self):
        self.host = os.getenv('DM_HOST')
        self.port = int(os.getenv('DM_PORT', 5236))
        self.user = os.getenv('DM_USER')
        self.password = os.getenv('DM_PASSWORD')
        self.database = os.getenv('DM_DATABASE')
        self.connection = None
        
        # 验证必需的环境变量
        required_vars = ['DM_HOST', 'DM_USER', 'DM_PASSWORD', 'DM_DATABASE']
        missing_vars = [var for var in required_vars if not os.getenv(var)]
        if missing_vars:
            raise ValueError(f"缺少必需的环境变量: {', '.join(missing_vars)}")
    
    def connect(self):
        try:
            self.connection = dmPython.connect(
                user=self.user,
                password=self.password,
                server=self.host,
                port=self.port
            )
            return self.connection
        except Exception as e:
            print(f"达梦数据库连接失败: {e}")
            return None
    
    def close(self):
        if self.connection:
            self.connection.close()

class ESConnection:
    def __init__(self):
        self.host = os.getenv('ES_HOST')
        self.user = os.getenv('ES_USER')
        self.password = os.getenv('ES_PASSWORD')
        self.client = None
        
        # 验证必需的环境变量
        required_vars = ['ES_HOST', 'ES_USER', 'ES_PASSWORD']
        missing_vars = [var for var in required_vars if not os.getenv(var)]
        if missing_vars:
            raise ValueError(f"缺少必需的环境变量: {', '.join(missing_vars)}")
    
    def connect(self):
        try:
            # 忽略SSL证书验证
            ssl_context = ssl.create_default_context()
            ssl_context.check_hostname = False
            ssl_context.verify_mode = ssl.CERT_NONE
            
            self.client = Elasticsearch(
                [self.host],
                basic_auth=(self.user, self.password),
                ssl_context=ssl_context,
                verify_certs=False
            )
            # 测试连接
            if self.client.ping():
                print("Elasticsearch连接成功")
                return self.client
            else:
                print("Elasticsearch连接失败")
                return None
        except Exception as e:
            print(f"Elasticsearch连接失败: {e}")
            return None
    
    def close(self):
        if self.client:
            self.client.close()

# 全局连接实例
dm_conn = DMConnection()
es_conn = ESConnection()