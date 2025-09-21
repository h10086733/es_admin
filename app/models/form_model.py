import json
from config.database import dm_conn, dm_pool

class FormModel:
    @staticmethod
    def get_all_forms():
        """获取所有有效的表单配置"""
        conn = dm_conn.connect()
        if not conn:
            return []
        
        try:
            cursor = conn.cursor()
            sql = """
            SELECT ID, NAME, FIELD_INFO, VIEW_INFO, APPBIND_INFO, EXTENSIONS_INFO
            FROM CAP_FORM_DEFINITION 
            WHERE DELETE_FLAG = 0
            """
            cursor.execute(sql)
            forms = cursor.fetchall()
            
            result = []
            for form in forms:
                form_data = {
                    'id': str(form[0]),  # 转换为字符串避免JavaScript精度丢失
                    'name': form[1],
                    'field_info': json.loads(form[2]) if form[2] else {},
                    'view_info': json.loads(form[3]) if form[3] else {},
                    'appbind_info': json.loads(form[4]) if form[4] else {},
                    'extensions_info': json.loads(form[5]) if form[5] else {}
                }
                result.append(form_data)
            
            cursor.close()
            return result
            
        except Exception as e:
            print(f"获取表单配置失败: {e}")
            return []
        finally:
            conn.close()
    
    @staticmethod
    def get_form_by_id(form_id):
        """根据ID获取表单配置"""
        conn = dm_conn.connect()
        if not conn:
            return None
        
        try:
            cursor = conn.cursor()
            sql = """
            SELECT ID, NAME, FIELD_INFO, VIEW_INFO, APPBIND_INFO, EXTENSIONS_INFO
            FROM CAP_FORM_DEFINITION 
            WHERE ID = ? AND DELETE_FLAG = 0
            """
            cursor.execute(sql, (form_id,))
            form = cursor.fetchone()
            
            if form:
                return {
                    'id': str(form[0]),  # 转换为字符串避免JavaScript精度丢失
                    'name': form[1],
                    'field_info': json.loads(form[2]) if form[2] else {},
                    'view_info': json.loads(form[3]) if form[3] else {},
                    'appbind_info': json.loads(form[4]) if form[4] else {},
                    'extensions_info': json.loads(form[5]) if form[5] else {}
                }
            
            cursor.close()
            return None
            
        except Exception as e:
            print(f"获取表单配置失败: {e}")
            return None
        finally:
            conn.close()
    
    @staticmethod
    def get_form_table_name(form_id):
        """从表单配置中提取数据表名"""
        form = FormModel.get_form_by_id(form_id)
        if not form:
            return None
        
        try:
            field_info = form['field_info']
            if 'front_formmain' in field_info:
                return field_info['front_formmain'].get('tableName')
            return None
        except Exception as e:
            print(f"提取表名失败: {e}")
            return None
    
    @staticmethod
    def get_form_fields(form_id):
        """获取表单字段定义"""
        form = FormModel.get_form_by_id(form_id)
        if not form:
            return []
        
        try:
            field_info = form['field_info']
            if 'front_formmain' in field_info:
                return field_info['front_formmain'].get('fieldInfo', [])
            return []
        except Exception as e:
            print(f"获取字段定义失败: {e}")
            return []
    
    @staticmethod
    def get_form_sub_tables(form_id):
        """获取表单的附表信息"""
        form = FormModel.get_form_by_id(form_id)
        if not form:
            return []
        
        try:
            field_info = form['field_info']
            sub_tables = []
            
            # 检查是否有formsons附表配置
            if 'formsons' in field_info:
                for formson in field_info['formsons']:
                    raw_table_name = formson.get('dbTableName', '')
                    front_table_name = formson.get('frontTableName', '')
                    
                    # 规范化表名 - 移除多余的table_前缀
                    normalized_table_name = FormModel._normalize_table_name(raw_table_name, front_table_name)
                    
                    sub_table_info = {
                        'table_name': normalized_table_name,
                        'raw_table_name': raw_table_name,  # 保留原始配置用于调试
                        'display_name': formson.get('display', ''),
                        'front_table_name': front_table_name,
                        'fields': formson.get('fieldInfo', []),
                        'owner_table': formson.get('ownerTable', ''),
                        'table_type': formson.get('tableType', 'slave')
                    }
                    if sub_table_info['table_name']:
                        sub_tables.append(sub_table_info)
            
            return sub_tables
        except Exception as e:
            print(f"获取附表信息失败: {e}")
            return []
    
    @staticmethod
    def _normalize_table_name(db_table_name, front_table_name):
        """规范化表名，处理table_前缀问题"""
        if not db_table_name and not front_table_name:
            return ''
        
        # 优先使用front_table_name，它通常是正确的表名
        if front_table_name:
            table_name = front_table_name
        else:
            table_name = db_table_name
        
        # 如果表名以table_开头，但实际表名不应该有这个前缀，则移除
        if table_name.startswith('table_') and table_name != 'table_' and len(table_name) > 6:
            # 检查是否是formson类型的表名
            potential_name = table_name[6:]  # 移除"table_"前缀
            if potential_name.startswith('formson_'):
                print(f"规范化表名: {table_name} -> {potential_name}")
                return potential_name
        
        return table_name
    
    @staticmethod
    def get_valid_form_sub_tables(form_id):
        """获取表单的有效附表信息（排除不存在的表）"""
        sub_tables = FormModel.get_form_sub_tables(form_id)
        if not sub_tables:
            return []
        
        conn = dm_conn.connect()
        if not conn:
            return sub_tables  # 无法连接时返回所有配置
        
        try:
            cursor = conn.cursor()
            valid_sub_tables = []
            
            for sub_table in sub_tables:
                table_name = sub_table['table_name']
                if FormModel._check_table_exists(cursor, table_name):
                    valid_sub_tables.append(sub_table)
                else:
                    print(f"跳过不存在的附表: {table_name} ({sub_table['display_name']})")
            
            cursor.close()
            return valid_sub_tables
            
        except Exception as e:
            print(f"验证附表存在性失败: {e}")
            return sub_tables  # 出错时返回所有配置
        finally:
            conn.close()
    
    @staticmethod
    def get_sub_table_field_labels(form_id, sub_table_name):
        """获取附表字段的中文标签映射"""
        sub_tables = FormModel.get_form_sub_tables(form_id)
        
        for sub_table in sub_tables:
            if sub_table['front_table_name'] == sub_table_name or sub_table['table_name'] == sub_table_name:
                field_labels = {}
                for field in sub_table['fields']:
                    field_name = field.get('name', field.get('columnName', ''))
                    field_label = field.get('display', field.get('label', field.get('title', '')))
                    if field_name and field_label:
                        field_labels[field_name] = field_label
                return field_labels
        
        return {}
    
    @staticmethod
    def get_table_data(table_name, limit=None, offset=0, modify_date_after=None):
        """获取表单数据表中的数据（支持分批处理）"""
        conn = dm_conn.connect()
        if not conn:
            return []
        
        try:
            cursor = conn.cursor()
            
            # 构建SQL
            sql = f"SELECT * FROM {table_name}"
            params = []
            
            # 增量同步条件
            if modify_date_after:
                sql += " WHERE modify_date > ?"
                params.append(modify_date_after)
            
            # 分页 - 使用ID排序提高性能
            sql += " ORDER BY ID"
            if limit:
                sql += f" LIMIT {limit} OFFSET {offset}"
            
            cursor.execute(sql, params)
            columns = [desc[0] for desc in cursor.description]
            rows = cursor.fetchall()
            
            result = []
            for row in rows:
                record = {}
                for i, col in enumerate(columns):
                    record[col] = row[i]
                result.append(record)
            
            cursor.close()
            return result
            
        except Exception as e:
            print(f"获取表数据失败: {e}")
            return []
        finally:
            conn.close()
    
    @staticmethod
    def get_table_data_iterator(table_name, batch_size=1000, modify_date_after=None):
        """分批迭代获取表数据，适用于大数据量（使用连接池优化）"""
        offset = 0
        while True:
            batch_data = FormModel.get_table_data_pooled(
                table_name, 
                limit=batch_size, 
                offset=offset, 
                modify_date_after=modify_date_after
            )
            
            if not batch_data:
                break
                
            yield batch_data
            offset += batch_size
            
            # 如果返回的数据少于batch_size，说明已经是最后一批
            if len(batch_data) < batch_size:
                break
    
    @staticmethod
    def get_table_data_pooled(table_name, limit=None, offset=0, modify_date_after=None):
        """使用连接池获取表单数据表中的数据（高性能版本）"""
        conn = dm_pool.get_connection()
        if not conn:
            return []
        
        try:
            cursor = conn.cursor()
            
            # 先检查表是否存在
            if not FormModel._check_table_exists(cursor, table_name):
                print(f"表 {table_name} 不存在，跳过查询")
                return []
            
            # 构建SQL
            sql = f"SELECT * FROM {table_name}"
            params = []
            
            # 增量同步条件
            if modify_date_after:
                sql += " WHERE modify_date > ?"
                params.append(modify_date_after)
            
            # 分页 - 使用ID排序提高性能
            sql += " ORDER BY ID"
            if limit:
                sql += f" LIMIT {limit} OFFSET {offset}"
            
            cursor.execute(sql, params)
            columns = [desc[0] for desc in cursor.description]
            rows = cursor.fetchall()
            
            result = []
            for row in rows:
                record = {}
                for i, col in enumerate(columns):
                    record[col] = row[i]
                result.append(record)
            
            cursor.close()
            return result
            
        except Exception as e:
            error_msg = str(e)
            if "Invalid table or view name" in error_msg:
                print(f"表 {table_name} 不存在或无效: {error_msg}")
            else:
                print(f"获取表数据失败: {error_msg}")
            return []
        finally:
            dm_pool.return_connection(conn)
    
    @staticmethod
    def get_last_sync_time(form_id):
        """获取最后同步时间"""
        try:
            import json
            import os
            from datetime import datetime
            
            sync_status_file = "/tmp/es_sync_status.json"
            if not os.path.exists(sync_status_file):
                return None
                
            with open(sync_status_file, 'r', encoding='utf-8') as f:
                status_data = json.load(f)
                
            sync_time_str = status_data.get('form_sync_times', {}).get(str(form_id))
            if sync_time_str:
                return datetime.fromisoformat(sync_time_str)
            return None
            
        except Exception as e:
            print(f"获取最后同步时间失败: {e}")
            return None
    
    @staticmethod
    def get_field_labels(form_id):
        """获取表单字段的中文标签映射"""
        form = FormModel.get_form_by_id(form_id)
        if not form:
            return {}
        
        try:
            field_info = form['field_info']
            field_labels = {}
            
            if 'front_formmain' in field_info:
                fields = field_info['front_formmain'].get('fieldInfo', [])
                for field in fields:
                    field_name = field.get('name', field.get('columnName', ''))
                    # 优先使用 display 字段，然后是 label 和 title
                    field_label = field.get('display', field.get('label', field.get('title', '')))
                    if field_name and field_label:
                        field_labels[field_name] = field_label
            
            return field_labels
        except Exception as e:
            print(f"获取字段标签失败: {e}")
            return {}
    
    @staticmethod
    def get_member_name(member_id):
        """根据成员ID获取成员姓名"""
        if not member_id or member_id == 0:
            return None
            
        conn = dm_conn.connect()
        if not conn:
            return None
        
        try:
            cursor = conn.cursor()
            sql = "SELECT NAME FROM ORG_MEMBER WHERE ID = ?"
            cursor.execute(sql, (member_id,))
            result = cursor.fetchone()
            cursor.close()
            
            if result:
                return result[0]
            return None
            
        except Exception as e:
            print(f"获取成员姓名失败: {e}")
            return None
        finally:
            conn.close()
    
    @staticmethod
    def get_members_batch(member_ids):
        """批量获取成员姓名映射"""
        if not member_ids:
            return {}
        
        # 过滤有效的成员ID
        valid_ids = [mid for mid in member_ids if mid and mid != 0]
        if not valid_ids:
            return {}
        
        conn = dm_conn.connect()
        if not conn:
            return {}
        
        try:
            cursor = conn.cursor()
            # 构建IN查询
            placeholders = ','.join(['?' for _ in valid_ids])
            sql = f"SELECT ID, NAME FROM ORG_MEMBER WHERE ID IN ({placeholders})"
            cursor.execute(sql, valid_ids)
            results = cursor.fetchall()
            cursor.close()
            
            # 构建ID到姓名的映射
            member_map = {}
            for row in results:
                # 使用字符串作为键，避免大整数精度丢失
                member_map[str(row[0])] = row[1]
            
            return member_map
            
        except Exception as e:
            print(f"批量获取成员姓名失败: {e}")
            return {}
        finally:
            conn.close()    
    @staticmethod
    def get_table_data_by_foreign_key(table_name, foreign_key_field, foreign_key_value, limit=100):
        """通过外键查询附表数据"""
        conn = dm_conn.connect()
        if not conn:
            return []
        
        try:
            cursor = conn.cursor()
            
            # 先检查表是否存在
            if not FormModel._check_table_exists(cursor, table_name):
                print(f"表 {table_name} 不存在，跳过查询")
                return []
            
            # 检查外键字段是否存在，如果不存在尝试其他可能的字段名
            if not FormModel._check_column_exists(cursor, table_name, foreign_key_field):
                print(f"字段 {foreign_key_field} 在表 {table_name} 中不存在，尝试查找其他外键字段...")
                actual_foreign_key = FormModel._find_actual_foreign_key(cursor, table_name)
                if actual_foreign_key:
                    foreign_key_field = actual_foreign_key
                    print(f"找到实际外键字段: {actual_foreign_key}")
                else:
                    print(f"未找到有效的外键字段，跳过查询")
                    return []
            
            sql = f"SELECT * FROM {table_name} WHERE {foreign_key_field} = ?"
            if limit:
                sql += f" LIMIT {limit}"
            
            cursor.execute(sql, (foreign_key_value,))
            columns = [desc[0] for desc in cursor.description]
            rows = cursor.fetchall()
            
            result = []
            for row in rows:
                record = {}
                for i, col in enumerate(columns):
                    record[col] = row[i]
                result.append(record)
            
            cursor.close()
            return result
            
        except Exception as e:
            error_msg = str(e)
            if "Invalid table or view name" in error_msg:
                print(f"表 {table_name} 不存在或无效: {error_msg}")
            elif "Invalid column name" in error_msg:
                print(f"字段 {foreign_key_field} 在表 {table_name} 中不存在: {error_msg}")
            else:
                print(f"通过外键获取表数据失败: {error_msg}")
            return []
        finally:
            conn.close()
    
    @staticmethod
    def _check_column_exists(cursor, table_name, column_name):
        """检查列是否存在"""
        try:
            # 尝试查询该列
            cursor.execute(f"SELECT {column_name} FROM {table_name} LIMIT 1")
            return True
        except Exception:
            return False
    
    @staticmethod
    def _find_actual_foreign_key(cursor, table_name):
        """在表中查找实际的外键字段"""
        # 常见的外键字段名
        possible_keys = [
            'main_id', 'mainid', 'parent_id', 'parentid', 
            'form_main_id', 'formmain_id', 'master_id', 'masterid'
        ]
        
        for key in possible_keys:
            if FormModel._check_column_exists(cursor, table_name, key):
                return key
        
        # 如果常见字段名都不存在，尝试获取表结构来查找可能的外键
        try:
            # 获取表的所有列名
            cursor.execute(f"SELECT * FROM {table_name} LIMIT 1")
            columns = [desc[0].lower() for desc in cursor.description]
            
            # 查找包含 'id' 且可能是外键的字段
            for col in columns:
                if 'id' in col and col not in ['id', 'ID'] and ('main' in col or 'parent' in col or 'master' in col):
                    # 找到可能的外键字段，返回原始大小写的字段名
                    cursor.execute(f"SELECT * FROM {table_name} LIMIT 1")
                    original_columns = [desc[0] for desc in cursor.description]
                    for orig_col in original_columns:
                        if orig_col.lower() == col:
                            print(f"发现可能的外键字段: {orig_col}")
                            return orig_col
        except Exception as e:
            print(f"查找外键字段失败: {e}")
        
        return None
    
    @staticmethod
    def _check_table_exists(cursor, table_name):
        """检查表是否存在"""
        try:
            cursor.execute(f"SELECT 1 FROM {table_name} LIMIT 1")
            return True
        except Exception:
            return False
