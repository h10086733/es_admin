import json
from datetime import datetime
from config.database import es_conn
from app.models.form_model import FormModel

class SyncService:
    def __init__(self):
        self.es_client = es_conn.connect()
        if not self.es_client:
            raise Exception("无法连接到Elasticsearch")
    
    def create_index_mapping(self, form_id, fields):
        """为表单创建ES索引映射"""
        index_name = f"form_{form_id}"
        
        # 构建字段映射
        properties = {
            "form_id": {"type": "keyword"},
            "table_name": {"type": "keyword"},
            "record_id": {"type": "keyword"},
            "sync_time": {"type": "date"}
        }
        
        # 添加表单字段映射
        for field in fields:
            field_name = field.get('name', field.get('columnName', ''))
            field_type = field.get('type', field.get('fieldType', 'text'))
            
            if field_type in ['text', 'VARCHAR']:
                properties[field_name] = {
                    "type": "text",
                    "analyzer": "ik_max_word",
                    "search_analyzer": "ik_smart"
                }
            elif field_type in ['datetime', 'TIMESTAMP', 'date', 'DATE']:
                properties[field_name] = {"type": "date"}
            elif field_type in ['DECIMAL', 'INTEGER']:
                properties[field_name] = {"type": "double"}
            elif field_type == 'member':
                properties[field_name] = {"type": "keyword"}
            else:
                properties[field_name] = {"type": "text"}
        
        mapping = {
            "mappings": {
                "properties": properties
            },
            "settings": {
                "analysis": {
                    "analyzer": {
                        "ik_max_word": {
                            "type": "standard"
                        },
                        "ik_smart": {
                            "type": "standard"
                        }
                    }
                }
            }
        }
        
        try:
            # 删除已存在的索引
            if self.es_client.indices.exists(index=index_name):
                self.es_client.indices.delete(index=index_name)
            
            # 创建新索引
            self.es_client.indices.create(index=index_name, body=mapping)
            print(f"创建索引 {index_name} 成功")
            return True
        except Exception as e:
            print(f"创建索引失败: {e}")
            return False
    
    def sync_form_data(self, form_id, full_sync=True):
        """同步表单数据到ES (优化版，支持大数据集)"""
        import time
        start_time = time.time()
        
        # 获取表单配置
        form = FormModel.get_form_by_id(form_id)
        if not form:
            return {"success": False, "message": "表单不存在"}
        
        table_name = FormModel.get_form_table_name(form_id)
        if not table_name:
            return {"success": False, "message": "无法获取表名"}
        
        fields = FormModel.get_form_fields(form_id)
        
        # 创建索引映射
        if not self.create_index_mapping(form_id, fields):
            return {"success": False, "message": "创建索引失败"}
        
        # 使用分批迭代器处理大数据集
        modify_date_after = None if full_sync else FormModel.get_last_sync_time(form_id)
        
        index_name = f"form_{form_id}"
        success_count = 0
        total_count = 0
        batch_size = 1000  # ES bulk操作批次大小
        
        print(f"开始同步表单 {form_id} ({form['name']}) 数据...")
        
        # 使用分批迭代器，每次从数据库读取5000条记录
        for db_batch in FormModel.get_table_data_iterator(
            table_name, 
            batch_size=5000,  # 数据库批次大小
            modify_date_after=modify_date_after
        ):
            if not db_batch:
                break
            
            total_count += len(db_batch)
            
            # 将数据库批次再分为ES批次进行处理
            for i in range(0, len(db_batch), batch_size):
                es_batch = db_batch[i:i + batch_size]
                bulk_body = []
                
                for record in es_batch:
                    doc_id = f"{form_id}_{record.get('ID', record.get('id', ''))}"
                    doc = {
                        "form_id": form_id,
                        "table_name": table_name,
                        "record_id": str(record.get('ID', record.get('id', ''))),
                        "sync_time": datetime.now().isoformat()
                    }
                    
                    # 添加所有字段数据
                    for key, value in record.items():
                        if value is not None:
                            if isinstance(value, datetime):
                                doc[key] = value.isoformat()
                            else:
                                doc[key] = str(value)
                    
                    # 添加到bulk操作
                    bulk_body.extend([
                        {"index": {"_index": index_name, "_id": doc_id}},
                        doc
                    ])
                
                try:
                    # 执行批量插入
                    response = self.es_client.bulk(body=bulk_body, timeout='60s')
                    
                    # 统计成功数量
                    for item in response['items']:
                        if 'index' in item and item['index']['status'] in [200, 201]:
                            success_count += 1
                        else:
                            print(f"批量插入失败: {item}")
                    
                    # 打印进度
                    elapsed_time = time.time() - start_time
                    rate = success_count / elapsed_time if elapsed_time > 0 else 0
                    print(f"同步进度: {success_count}/{total_count} 条记录 | 耗时: {elapsed_time:.1f}s | 速度: {rate:.1f} 条/秒")
                            
                except Exception as e:
                    print(f"批量同步失败: {e}")
                    return {"success": False, "message": f"同步失败: {str(e)}"}
        
        # 刷新索引
        self.es_client.indices.refresh(index=index_name)
        
        elapsed_time = time.time() - start_time
        rate = success_count / elapsed_time if elapsed_time > 0 else 0
        
        return {
            "success": True,
            "message": f"同步完成，成功同步 {success_count} 条记录，耗时 {elapsed_time:.1f} 秒，平均速度 {rate:.1f} 条/秒",
            "count": success_count,
            "total": total_count,
            "elapsed_time": elapsed_time,
            "rate": rate
        }
    
    def sync_all_forms(self, full_sync=True):
        """同步所有表单数据"""
        forms = FormModel.get_all_forms()
        results = []
        
        for form in forms:
            result = self.sync_form_data(form['id'], full_sync)
            result['form_name'] = form['name']
            result['form_id'] = form['id']
            results.append(result)
        
        return results
    
    def search_data(self, query, form_ids=None, size=10, from_=0):
        """搜索数据 (优化版，支持大数据集和高性能搜索)"""
        import time
        start_time = time.time()
        
        if not query.strip():
            return {"hits": [], "total": 0}
        
        # 构建索引名称
        if form_ids:
            indices = [f"form_{fid}" for fid in form_ids]
        else:
            try:
                # 搜索所有form索引，添加错误处理
                all_indices = self.es_client.indices.get_alias(index="form_*")
                indices = list(all_indices.keys())
            except Exception as e:
                print(f"获取索引列表失败: {e}")
                return {"hits": [], "total": 0, "error": "无法获取索引列表"}
        
        if not indices:
            return {"hits": [], "total": 0}
        
        # 优化搜索查询，针对大数据集进行性能调优
        search_body = {
            "query": {
                "bool": {
                    "should": [
                        # 精确匹配得分更高
                        {
                            "multi_match": {
                                "query": query,
                                "fields": ["*"],
                                "type": "phrase",
                                "boost": 3.0
                            }
                        },
                        # 前缀匹配
                        {
                            "multi_match": {
                                "query": query,
                                "fields": ["*"],
                                "type": "phrase_prefix",
                                "boost": 2.0
                            }
                        },
                        # 模糊匹配
                        {
                            "multi_match": {
                                "query": query,
                                "fields": ["*"],
                                "type": "best_fields",
                                "fuzziness": "AUTO",
                                "boost": 1.0
                            }
                        }
                    ],
                    "minimum_should_match": 1
                }
            },
            "highlight": {
                "fields": {
                    "*": {
                        "fragment_size": 150,
                        "number_of_fragments": 3
                    }
                },
                "pre_tags": ["<mark>"],
                "post_tags": ["</mark>"]
            },
            "size": min(size, 100),  # 限制单次返回最大数量
            "from": from_,
            "sort": [
                {"_score": {"order": "desc"}},
                {"sync_time": {"order": "desc"}}
            ],
            # 性能优化设置
            "_source": {
                "excludes": ["sync_time"]  # 排除不必要的字段减少传输量
            },
            "track_total_hits": True,
            "timeout": "30s"  # 设置超时避免长时间查询
        }
        
        try:
            response = self.es_client.search(
                index=','.join(indices),
                body=search_body,
                request_timeout=30  # 客户端超时设置
            )
            
            hits = []
            # 批量获取表单名称，减少数据库查询次数
            form_names_cache = {}
            
            for hit in response['hits']['hits']:
                source = hit['_source']
                form_id = source['form_id']
                
                # 使用缓存获取表单名称
                if form_id not in form_names_cache:
                    try:
                        form = FormModel.get_form_by_id(form_id)
                        form_names_cache[form_id] = form['name'] if form else "未知表单"
                    except:
                        form_names_cache[form_id] = "未知表单"
                
                hit_data = {
                    "score": hit['_score'],
                    "form_id": form_id,
                    "form_name": form_names_cache[form_id],
                    "table_name": source['table_name'],
                    "record_id": source['record_id'],
                    "data": source,
                    "highlight": hit.get('highlight', {})
                }
                hits.append(hit_data)
            
            elapsed_time = time.time() - start_time
            total_hits = response['hits']['total']['value']
            
            print(f"搜索完成: 关键词='{query}', 结果={total_hits}条, 耗时={elapsed_time:.3f}秒")
            
            return {
                "hits": hits,
                "total": total_hits,
                "max_score": response['hits']['max_score'],
                "elapsed_time": elapsed_time,
                "took": response.get('took', 0)
            }
            
        except Exception as e:
            elapsed_time = time.time() - start_time
            error_msg = str(e)
            print(f"搜索失败: {error_msg}, 耗时={elapsed_time:.3f}秒")
            
            # 提供更友好的错误信息
            if "timeout" in error_msg.lower():
                error_msg = "搜索超时，请尝试更具体的关键词"
            elif "index_not_found" in error_msg.lower():
                error_msg = "索引不存在，请先同步数据"
            
            return {"hits": [], "total": 0, "error": error_msg}