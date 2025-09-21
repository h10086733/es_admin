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
                    "analyzer": "standard"
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
                        "default": {
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
        """同步表单数据到ES (优化版，支持大数据集和附表)"""
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
        sub_tables = FormModel.get_valid_form_sub_tables(form_id)
        
        # 获取字段标签映射用于数据转换
        field_labels = FormModel.get_field_labels(form_id)
        
        # 创建索引映射（包含主表和附表字段）
        all_fields = fields.copy()
        for sub_table in sub_tables:
            all_fields.extend(sub_table['fields'])
        
        if not self.create_index_mapping(form_id, all_fields):
            return {"success": False, "message": "创建索引失败"}
        
        # 使用分批迭代器处理大数据集
        modify_date_after = None if full_sync else FormModel.get_last_sync_time(form_id)
        
        # 预加载成员信息到缓存
        member_cache = self.preload_member_cache(table_name, modify_date_after)
        
        index_name = f"form_{form_id}"
        success_count = 0
        total_count = 0
        batch_size = 2000  # ES bulk操作批次大小，适中的批次确保稳定性
        
        print(f"开始同步表单 {form_id} ({form['name']}) 数据...")
        
        # 使用分批迭代器，每次从数据库读取1000条记录
        for db_batch in FormModel.get_table_data_iterator(
            table_name, 
            batch_size=1000,  # 数据库批次大小，限制为1000条保护数据库
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
                    
                    # 添加主表字段数据，并转换字段名为中文标签
                    for key, value in record.items():
                        if value is not None:
                            # 转换字段名为中文标签
                            display_name = self.get_field_display_name(key, field_labels)
                            
                            # 跳过不需要的系统字段
                            if self.should_skip_field(key):
                                continue
                            
                            # 格式化值（使用缓存）
                            formatted_value = self.format_field_value_cached(key, value, member_cache)
                            if formatted_value is not None:
                                doc[display_name] = formatted_value
                    
                    # 添加附表数据
                    for sub_table in sub_tables:
                        sub_table_data = self.get_sub_table_data(
                            sub_table['table_name'], 
                            record.get('ID', record.get('id')),
                            sub_table,
                            member_cache
                        )
                        if sub_table_data:
                            # 将附表数据添加到文档中，使用附表显示名作为前缀
                            sub_table_prefix = sub_table['display_name']
                            for sub_record in sub_table_data:
                                for key, value in sub_record.items():
                                    if value is not None and not self.should_skip_field(key):
                                        sub_field_labels = FormModel.get_sub_table_field_labels(form_id, sub_table['front_table_name'])
                                        display_name = self.get_field_display_name(key, sub_field_labels)
                                        formatted_value = self.format_field_value_cached(key, value, member_cache)
                                        if formatted_value is not None:
                                            # 使用附表前缀避免字段名冲突
                                            prefixed_name = f"{sub_table_prefix}_{display_name}"
                                            if prefixed_name not in doc:
                                                doc[prefixed_name] = []
                                            if isinstance(doc[prefixed_name], list):
                                                doc[prefixed_name].append(formatted_value)
                                            else:
                                                doc[prefixed_name] = [doc[prefixed_name], formatted_value]
                    
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
    
    def sync_all_forms(self, full_sync=True, sync_members_first=True):
        """同步所有表单数据"""
        results = []
        
        # 优先同步人员数据，确保成员字段能正确显示
        if sync_members_first:
            try:
                from app.services.member_sync_service import MemberSyncService
                member_service = MemberSyncService()
                member_result = member_service.sync_members_to_es(full_sync)
                results.append({
                    "form_name": "系统人员",
                    "form_id": "system_members",
                    "type": "member_sync",
                    **member_result
                })
                print("人员数据同步完成，开始同步表单数据...")
            except Exception as e:
                print(f"人员数据同步失败，继续同步表单数据: {e}")
                results.append({
                    "form_name": "系统人员",
                    "form_id": "system_members", 
                    "type": "member_sync",
                    "success": False,
                    "message": f"人员同步失败: {str(e)}"
                })
        
        # 同步表单数据
        forms = FormModel.get_all_forms()
        for form in forms:
            result = self.sync_form_data(form['id'], full_sync)
            result['form_name'] = form['name']
            result['form_id'] = form['id']
            result['type'] = 'form_sync'
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
            # 缓存表单名称，减少数据库查询
            form_cache = {}
            
            for hit in response['hits']['hits']:
                source = hit['_source']
                form_id = source['form_id']
                
                # 获取表单名称（使用缓存）
                if form_id not in form_cache:
                    try:
                        form = FormModel.get_form_by_id(form_id)
                        form_cache[form_id] = form['name'] if form else "未知表单"
                    except:
                        form_cache[form_id] = "未知表单"
                
                # 提取显示数据（已经是格式化的标签和值）
                display_data = self.extract_display_data(source)
                
                hit_data = {
                    "score": hit['_score'],
                    "form_id": form_id,
                    "form_name": form_cache[form_id],
                    "table_name": source['table_name'],
                    "record_id": source['record_id'],
                    "data": display_data,
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
    
    def extract_display_data(self, source):
        """提取用于显示的数据（数据已在ES中格式化）"""
        # 定义系统字段，不在搜索结果中显示
        system_fields = {
            'form_id', 'table_name', 'record_id', 'sync_time'
        }
        
        display_data = {}
        for key, value in source.items():
            # 跳过系统字段
            if key in system_fields:
                continue
                
            # 跳过空值
            if value is None or value == '':
                continue
                
            display_data[key] = value
        
        return display_data
    
    def get_field_display_name(self, field_name, field_labels):
        """获取字段显示名称"""
        # 优先使用配置的标签
        if field_name in field_labels:
            return field_labels[field_name]
        
        # 处理特殊系统字段
        system_field_mapping = {
            'start_date': '创建时间',
            'modify_date': '修改时间',
            'start_member_id': '创建人',
            'modify_member_id': '修改人',
            'approve_member_id': '审核人',
            'ratify_member_id': '核定人'
        }
        
        return system_field_mapping.get(field_name, field_name)
    
    def should_skip_field(self, field_name):
        """判断是否应该跳过字段"""
        hidden_fields = {
            'ID', 'id', 'form_id', 'table_name', 'record_id', 'sync_time',
            'state', 'sort', 'ratifyflag', 'finishedflag', 'approve_date', 
            'ratify_date'
        }
        return field_name in hidden_fields
    
    def preload_member_cache(self, table_name, modify_date_after=None):
        """预加载成员信息到缓存（优先从ES获取，降级到数据库）"""
        print("正在预加载成员信息...")
        
        try:
            # 收集所有成员ID
            member_ids = set()
            
            for batch in FormModel.get_table_data_iterator(table_name, batch_size=1000, modify_date_after=modify_date_after):
                if not batch:
                    break
                
                for record in batch:
                    for key, value in record.items():
                        if (key.endswith('_member_id') or key.endswith('member_id')) and value and value != 0:
                            member_ids.add(value)
            
            # 批量查询成员信息
            if member_ids:
                # 优先尝试从ES获取成员信息（更快）
                try:
                    from app.services.member_sync_service import MemberSyncService
                    member_service = MemberSyncService()
                    member_cache = member_service.get_member_cache_from_es(list(member_ids))
                    
                    # 检查ES中缺失的成员ID，从数据库补充
                    missing_ids = [mid for mid in member_ids if mid not in member_cache]
                    if missing_ids:
                        print(f"ES中缺失 {len(missing_ids)} 个成员信息，从数据库补充...")
                        db_member_cache = FormModel.get_members_batch(missing_ids)
                        member_cache.update(db_member_cache)
                    
                    print(f"成功预加载 {len(member_cache)} 个成员信息 (ES: {len(member_cache)-len(missing_ids)}, DB: {len(missing_ids) if missing_ids else 0})")
                    return member_cache
                    
                except Exception as es_error:
                    print(f"从ES获取成员信息失败，降级到数据库查询: {es_error}")
                    # 降级到数据库查询
                    member_cache = FormModel.get_members_batch(list(member_ids))
                    print(f"从数据库成功预加载 {len(member_cache)} 个成员信息")
                    return member_cache
            else:
                print("未发现成员字段，跳过成员信息预加载")
                return {}
                
        except Exception as e:
            print(f"预加载成员信息失败: {e}")
            return {}
    
    def format_field_value_cached(self, field_name, value, member_cache):
        """格式化字段值（使用缓存）"""
        # 跳过空值
        if value is None or value == '':
            return None
            
        # 处理日期时间字段
        if isinstance(value, datetime):
            return value.isoformat()
        
        # 处理成员字段，使用缓存
        if field_name.endswith('_member_id') or field_name.endswith('member_id'):
            # 使用字符串键查找，避免大整数精度问题
            member_name = member_cache.get(str(value))
            return member_name if member_name else str(value)
        
        # 格式化日期字段显示
        if field_name in ['start_date', 'modify_date'] and isinstance(value, str):
            try:
                if 'T' in value:
                    date_part = value.split('T')[0]
                    time_part = value.split('T')[1].split('.')[0] if '.' in value.split('T')[1] else value.split('T')[1]
                    return f"{date_part} {time_part}"
            except:
                pass
        
        return str(value)
    
    def format_field_value(self, field_name, value):
        """格式化字段值"""
        # 跳过空值
        if value is None or value == '':
            return None
            
        # 处理日期时间字段
        if isinstance(value, datetime):
            return value.isoformat()
        
        # 处理成员字段，转换为姓名
        if field_name.endswith('_member_id') or field_name.endswith('member_id'):
            try:
                member_name = FormModel.get_member_name(value)
                return member_name if member_name else str(value)
            except:
                return str(value)
        
        # 格式化日期字段显示
        if field_name in ['start_date', 'modify_date'] and isinstance(value, str):
            try:
                if 'T' in value:
                    date_part = value.split('T')[0]
                    time_part = value.split('T')[1].split('.')[0] if '.' in value.split('T')[1] else value.split('T')[1]
                    return f"{date_part} {time_part}"
            except:
                pass
        
        return str(value)    
    def get_sub_table_data(self, sub_table_name, main_record_id, sub_table_config, member_cache):
        """获取附表数据"""
        try:
            # 动态确定外键字段名
            foreign_key_field = self.get_sub_table_foreign_key(sub_table_config)
            
            # 查询附表数据，关联主表记录ID
            sub_data = FormModel.get_table_data_by_foreign_key(
                sub_table_name, 
                foreign_key_field,
                main_record_id,
                limit=100  # 限制每个主表记录的附表数据数量
            )
            return sub_data
        except Exception as e:
            print(f"获取附表数据失败 {sub_table_name}: {e}")
            return []
    
    def get_sub_table_foreign_key(self, sub_table_config):
        """确定附表的外键字段名"""
        # 1. 优先检查配置中是否有明确的外键字段定义
        owner_table = sub_table_config.get('owner_table', '')
        
        # 2. 根据表单系统的常见约定确定外键字段
        # 通常附表通过以下字段关联主表：
        possible_keys = [
            'main_id',           # 最常见的外键字段
            'mainid',            # 无下划线版本
            'parent_id',         # 另一种常见的外键字段
            'parentid',          # 无下划线版本
            'form_main_id',      # 带表单前缀的外键
            'formmain_id',       # 另一种形式
            f'{owner_table}_id'  # 基于主表名的外键（如果有配置）
        ]
        
        # 3. 通过检查附表字段定义来确定正确的外键
        sub_table_fields = sub_table_config.get('fields', [])
        field_names = [field.get('name', field.get('columnName', '')) for field in sub_table_fields]
        
        for key in possible_keys:
            if key in field_names:
                print(f"附表 {sub_table_config.get('table_name')} 使用外键字段: {key}")
                return key
        
        # 4. 如果都没找到，使用默认值并记录警告
        default_key = 'main_id'
        print(f"警告: 附表 {sub_table_config.get('table_name')} 未找到明确的外键字段，使用默认值: {default_key}")
        print(f"  附表字段列表: {field_names}")
        return default_key
