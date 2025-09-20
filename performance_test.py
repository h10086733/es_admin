#!/usr/bin/env python3
"""
性能测试脚本 - 展示20万数据同步性能优化效果
"""

import time
import sys
import os

# 添加项目路径
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from app.services.sync_service import SyncService
from app.models.form_model import FormModel

def test_sync_performance():
    """测试同步性能"""
    print("=== ES数据管理系统性能测试 ===\n")
    
    # 初始化服务
    sync_service = SyncService()
    
    # 获取表单列表
    print("1. 获取表单列表...")
    forms = FormModel.get_all_forms()
    print(f"   找到 {len(forms)} 个表单\n")
    
    if not forms:
        print("❌ 没有找到任何表单，请先确保数据库中有表单配置")
        return
    
    # 选择第一个表单进行测试
    test_form = forms[0]
    form_id = test_form['id']
    form_name = test_form['name']
    
    print(f"2. 测试表单: {form_name} (ID: {form_id})")
    
    # 获取表名和预估数据量
    table_name = FormModel.get_form_table_name(form_id)
    if not table_name:
        print("❌ 无法获取表名")
        return
    
    print(f"   数据表: {table_name}")
    
    # 预估数据量（获取第一批数据）
    sample_data = FormModel.get_table_data(table_name, limit=1000)
    if not sample_data:
        print("❌ 表中没有数据")
        return
    
    print(f"   样本数据: {len(sample_data)} 条")
    
    # 执行同步性能测试
    print("\n3. 开始性能测试...")
    print("   使用优化后的批量同步算法:")
    print("   - 数据库分批读取: 5000条/批")
    print("   - ES批量写入: 1000条/批")
    print("   - 超时设置: 60秒")
    
    start_time = time.time()
    
    try:
        result = sync_service.sync_form_data(form_id, full_sync=True)
        
        if result['success']:
            elapsed_time = result.get('elapsed_time', time.time() - start_time)
            count = result.get('count', 0)
            rate = result.get('rate', 0)
            
            print(f"\n✅ 同步成功!")
            print(f"   同步记录数: {count:,} 条")
            print(f"   总耗时: {elapsed_time:.2f} 秒")
            print(f"   平均速度: {rate:.1f} 条/秒")
            
            # 性能预估
            print(f"\n4. 20万数据性能预估:")
            if rate > 0:
                estimated_time_200k = 200000 / rate
                estimated_minutes = estimated_time_200k / 60
                estimated_hours = estimated_minutes / 60
                
                print(f"   预估耗时: {estimated_time_200k:.0f} 秒")
                if estimated_hours >= 1:
                    print(f"            约 {estimated_hours:.1f} 小时")
                else:
                    print(f"            约 {estimated_minutes:.1f} 分钟")
                
                # 与原有性能对比
                old_rate = 25  # 41ms/条 ≈ 25条/秒
                old_time_200k = 200000 / old_rate
                improvement = old_time_200k / estimated_time_200k
                
                print(f"\n5. 性能对比:")
                print(f"   优化前: 约 {old_time_200k/3600:.1f} 小时 ({old_rate} 条/秒)")
                print(f"   优化后: 约 {estimated_time_200k/3600:.1f} 小时 ({rate:.1f} 条/秒)")
                print(f"   性能提升: {improvement:.1f}x")
            
        else:
            print(f"❌ 同步失败: {result.get('message', '未知错误')}")
            
    except Exception as e:
        print(f"❌ 测试异常: {e}")

def test_search_performance():
    """测试搜索性能"""
    print("\n=== 搜索性能测试 ===")
    
    sync_service = SyncService()
    
    test_queries = ["设备", "台式", "电脑", "管理"]
    
    for query in test_queries:
        print(f"\n测试搜索: '{query}'")
        start_time = time.time()
        
        try:
            result = sync_service.search_data(query, size=20)
            elapsed_time = time.time() - start_time
            
            if 'error' not in result:
                total = result.get('total', 0)
                hits = len(result.get('hits', []))
                es_took = result.get('took', 0)
                
                print(f"   ✅ 搜索成功")
                print(f"   结果数量: {total:,} 条 (返回 {hits} 条)")
                print(f"   客户端耗时: {elapsed_time*1000:.1f} ms")
                print(f"   ES查询耗时: {es_took} ms")
            else:
                print(f"   ❌ 搜索失败: {result['error']}")
                
        except Exception as e:
            print(f"   ❌ 搜索异常: {e}")

if __name__ == "__main__":
    try:
        test_sync_performance()
        test_search_performance()
        
        print("\n=== 测试完成 ===")
        print("💡 提示: 实际性能可能因数据复杂度、网络延迟、硬件配置等因素有所差异")
        
    except KeyboardInterrupt:
        print("\n⏹️ 测试被用户中断")
    except Exception as e:
        print(f"\n❌ 测试程序异常: {e}")