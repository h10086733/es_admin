import dmPython
import os
from elasticsearch import Elasticsearch
import ssl
import threading
from queue import Queue
import time

class DMConnectionPool:
    def __init__(self, pool_size=10):
        self.host = os.getenv('DM_HOST')
        self.port = int(os.getenv('DM_PORT', 5236))
        self.user = os.getenv('DM_USER')
        self.password = os.getenv('DM_PASSWORD')
        self.database = os.getenv('DM_DATABASE')
        self.pool_size = pool_size
        self.pool = Queue(maxsize=pool_size)
        self.lock = threading.Lock()
        
        # 验证必需的环境变量
        required_vars = ['DM_HOST', 'DM_USER', 'DM_PASSWORD', 'DM_DATABASE']
        missing_vars = [var for var in required_vars if not os.getenv(var)]
        if missing_vars:
            raise ValueError(f"缺少必需的环境变量: {', '.join(missing_vars)}")
        
        # 初始化连接池
        self._init_pool()
    
    def _init_pool(self):
        """初始化连接池"""
        for _ in range(self.pool_size):
            conn = self._create_connection()
            if conn:
                self.pool.put(conn)
    
    def _create_connection(self):
        """创建新连接"""
        try:
            return dmPython.connect(
                user=self.user,
                password=self.password,
                server=self.host,
                port=self.port
            )
        except Exception as e:
            print(f"达梦数据库连接失败: {e}")
            return None
    
    def get_connection(self, timeout=30):
        """从连接池获取连接"""
        try:
            # 尝试从池中获取连接
            conn = self.pool.get(timeout=timeout)
            
            # 检查连接是否有效
            if self._is_connection_valid(conn):
                return conn
            else:
                # 连接无效，创建新连接
                new_conn = self._create_connection()
                return new_conn if new_conn else None
                
        except:
            # 池为空或超时，创建新连接
            return self._create_connection()
    
    def return_connection(self, conn):
        """归还连接到池中"""
        if conn and self._is_connection_valid(conn):
            try:
                self.pool.put_nowait(conn)
            except:
                # 池已满，关闭连接
                try:
                    conn.close()
                except:
                    pass
        elif conn:
            # 连接无效，关闭它
            try:
                conn.close()
            except:
                pass
    
    def _is_connection_valid(self, conn):
        """检查连接是否有效"""
        try:
            cursor = conn.cursor()
            cursor.execute("SELECT 1")
            cursor.fetchone()
            cursor.close()
            return True
        except:
            return False
    
    def close_all(self):
        """关闭所有连接"""
        while not self.pool.empty():
            try:
                conn = self.pool.get_nowait()
                conn.close()
            except:
                pass

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
                http_auth=(self.user, self.password),
                verify_certs=False,
                ssl_show_warn=False
            )
            # 测试连接 - 使用info()替代ping()以兼容ES 8.x
            try:
                info = self.client.info()
                print("Elasticsearch连接成功")
                return self.client
            except Exception as test_e:
                print(f"Elasticsearch连接失败: {test_e}")
                return None
        except Exception as e:
            print(f"Elasticsearch连接失败: {e}")
            return None
    
    def close(self):
        if self.client:
            self.client.close()

# 全局连接实例
dm_conn = DMConnection()
dm_pool = DMConnectionPool(pool_size=20)  # 增加连接池用于高并发
es_conn = ESConnection()