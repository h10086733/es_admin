from datetime import datetime
from config.database import es_conn, dm_pool
from app.models.form_model import FormModel
import time

class MemberSyncService:
    def __init__(self):
        self.es_client = es_conn.connect()
        if not self.es_client:
            raise Exception("无法连接到Elasticsearch")
        self.member_index = "system_members"
    
    def create_member_index_mapping(self):
        """创建人员表ES索引映射"""
        mapping = {
            "mappings": {
                "properties": {
                    "member_id": {"type": "keyword"},
                    "name": {
                        "type": "text",
                        "analyzer": "standard",
                        "fields": {
                            "keyword": {"type": "keyword"}
                        }
                    },
                    "department": {
                        "type": "text",
                        "analyzer": "standard"
                    },
                    "position": {
                        "type": "text", 
                        "analyzer": "standard"
                    },
                    "email": {"type": "keyword"},
                    "phone": {"type": "keyword"},
                    "status": {"type": "keyword"},
                    "create_time": {"type": "date"},
                    "update_time": {"type": "date"},
                    "sync_time": {"type": "date"}
                }
            },
            "settings": {
                "number_of_shards": 1,
                "number_of_replicas": 0,
                "refresh_interval": "30s",
                "analysis": {
                    "analyzer": {
                        "default": {"type": "standard"}
                    }
                }
            }
        }
        
        try:
            # 删除已存在的索引
            if self.es_client.indices.exists(index=self.member_index):
                self.es_client.indices.delete(index=self.member_index)
            
            # 创建新索引
            self.es_client.indices.create(index=self.member_index, body=mapping)
            print(f"创建人员索引 {self.member_index} 成功")
            return True
        except Exception as e:
            print(f"创建人员索引失败: {e}")
            return False
    
    def sync_members_to_es(self, full_sync=True):
        """同步人员数据到ES"""
        start_time = time.time()
        
        # 创建索引映射
        if not self.create_member_index_mapping():
            return {"success": False, "message": "创建人员索引失败"}
        
        print("开始同步人员数据到ES...")
        
        # 获取人员数据
        members = self.get_all_members()
        if not members:
            return {"success": False, "message": "未找到人员数据"}
        
        # 批量同步到ES
        batch_size = 1000
        success_count = 0
        total_count = len(members)
        
        for i in range(0, total_count, batch_size):
            batch = members[i:i + batch_size]
            bulk_body = []
            
            for member in batch:
                doc_id = str(member['ID'])
                doc = {
                    "member_id": str(member['ID']),
                    "name": member.get('NAME', ''),
                    "department": member.get('DEPARTMENT', ''),
                    "position": member.get('POSITION', ''),
                    "email": member.get('EMAIL', ''),
                    "phone": member.get('PHONE', ''),
                    "status": member.get('STATUS', 'active'),
                    "create_time": member.get('CREATE_TIME'),
                    "update_time": member.get('UPDATE_TIME'),
                    "sync_time": datetime.now().isoformat()
                }
                
                bulk_body.extend([
                    {"index": {"_index": self.member_index, "_id": doc_id}},
                    doc
                ])
            
            try:
                response = self.es_client.bulk(body=bulk_body, timeout='60s')
                
                # 统计成功数量
                for item in response['items']:
                    if 'index' in item and item['index']['status'] in [200, 201]:
                        success_count += 1
                
                print(f"同步人员进度: {success_count}/{total_count}")
                
            except Exception as e:
                print(f"批量同步人员失败: {e}")
                return {"success": False, "message": f"同步失败: {str(e)}"}
        
        # 刷新索引
        self.es_client.indices.refresh(index=self.member_index)
        
        elapsed_time = time.time() - start_time
        rate = success_count / elapsed_time if elapsed_time > 0 else 0
        
        return {
            "success": True,
            "message": f"人员数据同步完成，成功同步 {success_count} 条记录，耗时 {elapsed_time:.1f} 秒",
            "count": success_count,
            "total": total_count,
            "elapsed_time": elapsed_time,
            "rate": rate
        }
    
    def get_all_members(self):
        """获取所有人员数据"""
        conn = dm_pool.get_connection()
        if not conn:
            print("无法获取数据库连接")
            return []
        
        try:
            cursor = conn.cursor()
            
            # 先检查表是否存在
            has_dept_table = self._check_table_exists(cursor, 'ORG_DEPARTMENT')
            has_position_table = self._check_table_exists(cursor, 'ORG_POSITION')
            
            if has_dept_table and has_position_table:
                # 完整查询
                sql = """
                SELECT m.ID, m.NAME, m.EMAIL, m.PHONE, m.STATUS,
                       m.CREATE_TIME, m.UPDATE_TIME,
                       d.NAME as DEPARTMENT, p.NAME as POSITION
                FROM ORG_MEMBER m
                LEFT JOIN ORG_DEPARTMENT d ON m.DEPARTMENT_ID = d.ID
                LEFT JOIN ORG_POSITION p ON m.POSITION_ID = p.ID
                WHERE m.DELETE_FLAG = 0
                ORDER BY m.ID
                """
                print("使用完整人员查询（包含部门和职位）")
            else:
                # 简化查询
                sql = """
                SELECT ID, NAME, EMAIL, PHONE, STATUS, CREATE_TIME, UPDATE_TIME
                FROM ORG_MEMBER
                WHERE DELETE_FLAG = 0
                ORDER BY ID
                """
                print(f"使用简化人员查询（缺失表: {'ORG_DEPARTMENT' if not has_dept_table else ''} {'ORG_POSITION' if not has_position_table else ''}）")
            
            cursor.execute(sql)
            columns = [desc[0] for desc in cursor.description]
            rows = cursor.fetchall()
            
            result = []
            for row in rows:
                record = {}
                for i, col in enumerate(columns):
                    record[col] = row[i]
                
                # 为简化查询添加默认值
                if not has_dept_table:
                    record['DEPARTMENT'] = ''
                if not has_position_table:
                    record['POSITION'] = ''
                    
                result.append(record)
            
            cursor.close()
            print(f"成功获取 {len(result)} 条人员记录")
            return result
            
        except Exception as e:
            print(f"获取人员数据失败: {e}")
            return []
        finally:
            dm_pool.return_connection(conn)
    
    def _check_table_exists(self, cursor, table_name):
        """检查表是否存在"""
        try:
            cursor.execute(f"SELECT 1 FROM {table_name} LIMIT 1")
            return True
        except Exception:
            return False
    
    def search_members(self, query, size=20):
        """搜索人员信息"""
        if not query.strip():
            return {"hits": [], "total": 0}
        
        search_body = {
            "query": {
                "bool": {
                    "should": [
                        {
                            "multi_match": {
                                "query": query,
                                "fields": ["name^3", "department^2", "position"],
                                "type": "phrase_prefix"
                            }
                        },
                        {
                            "multi_match": {
                                "query": query,
                                "fields": ["name", "department", "position", "email", "phone"],
                                "type": "best_fields",
                                "fuzziness": "AUTO"
                            }
                        }
                    ]
                }
            },
            "highlight": {
                "fields": {
                    "name": {},
                    "department": {},
                    "position": {}
                }
            },
            "size": size,
            "sort": [
                {"_score": {"order": "desc"}},
                {"name.keyword": {"order": "asc"}}
            ]
        }
        
        try:
            response = self.es_client.search(
                index=self.member_index,
                body=search_body
            )
            
            hits = []
            for hit in response['hits']['hits']:
                hit_data = {
                    "score": hit['_score'],
                    "member_id": hit['_source']['member_id'],
                    "name": hit['_source']['name'],
                    "department": hit['_source']['department'],
                    "position": hit['_source']['position'],
                    "email": hit['_source']['email'],
                    "phone": hit['_source']['phone'],
                    "highlight": hit.get('highlight', {})
                }
                hits.append(hit_data)
            
            return {
                "hits": hits,
                "total": response['hits']['total']['value']
            }
            
        except Exception as e:
            print(f"搜索人员失败: {e}")
            return {"hits": [], "total": 0, "error": str(e)}
    
    def get_member_cache_from_es(self, member_ids):
        """从ES批量获取成员信息（替代数据库查询）"""
        if not member_ids:
            return {}
        
        # 转换为字符串ID列表
        str_member_ids = [str(mid) for mid in member_ids if mid and mid != 0]
        if not str_member_ids:
            return {}
        
        try:
            # 使用terms查询批量获取
            search_body = {
                "query": {
                    "terms": {
                        "member_id": str_member_ids
                    }
                },
                "_source": ["member_id", "name"],
                "size": len(str_member_ids)
            }
            
            response = self.es_client.search(
                index=self.member_index,
                body=search_body
            )
            
            # 构建ID到姓名的映射
            member_cache = {}
            for hit in response['hits']['hits']:
                source = hit['_source']
                # 使用字符串作为键，避免大整数精度丢失
                member_cache[str(source['member_id'])] = source['name']
            
            return member_cache
            
        except Exception as e:
            print(f"从ES获取成员信息失败: {e}")
            return {}