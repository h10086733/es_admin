# 权限检查完整指南

## 功能说明

系统现在会在页面加载时立即检查用户的 `isView` 权限：
- 如果 `isView=true` 或未返回（默认true）→ 允许访问
- 如果 `isView=false` → 弹窗提示 + 显示无权限页面

## 工作流程

### 1. 页面加载时

```
用户访问 → 
提取userId → 
调用 /api/search/permission/check/{userId} → 
检查 isView 字段 → 
判断是否允许访问
```

### 2. 有权限的用户 (isView=true)

```
1. 权限检查通过
2. 正常显示页面
3. 可以使用所有功能
```

**控制台输出**:
```javascript
权限检查结果: {
  userId: "123456",
  isAdmin: false,
  isView: true
}
```

### 3. 无权限的用户 (isView=false)

```
1. 权限检查失败
2. 弹出警告框："您没有权限访问此系统，请联系管理员开通权限后再使用"
3. 点击确定后，页面被替换为无权限提示页面
4. 无法进行任何操作
```

**无权限页面显示**:
```
┌─────────────────────────────┐
│                             │
│           🔒                │
│                             │
│      无权限访问              │
│                             │
│  您的账号 123456 暂无访问权限│
│                             │
│  如需开通权限，请联系系统管理员│
│                             │
└─────────────────────────────┘
```

**控制台输出**:
```javascript
权限检查结果: {
  userId: "-64557199688994460741",
  isAdmin: false,
  isView: false
}
```

## 接口说明

### 权限检查接口

**URL**: `GET /api/search/permission/check/{userId}`

**请求示例**:
```bash
curl http://localhost:8088/api/search/permission/check/-64557199688994460741
```

**响应格式**:

有权限:
```json
{
  "success": true,
  "data": {
    "isAdmin": false,
    "isView": true
  }
}
```

无权限:
```json
{
  "success": true,
  "data": {
    "isAdmin": false,
    "isView": false
  }
}
```

接口失败:
```json
{
  "success": false,
  "message": "错误信息"
}
```

### 管理员接口（旧接口，兼容）

**URL**: `GET /api/search/admin/check/{userId}`

只返回 `isAdmin`，不返回 `isView`。如果新接口失败，会fallback到这个接口。

## 测试步骤

### 测试1: 无权限用户

1. **准备**: 确保用户的 `isView=false`
   ```bash
   # 检查接口返回
   curl http://localhost:8088/api/search/permission/check/-64557199688994460741
   ```

2. **访问系统**:
   ```
   http://localhost:8088/?user_id=-64557199688994460741
   ```

3. **预期结果**:
   - 立即弹出警告框
   - 点击确定后显示无权限页面
   - 页面显示🔒图标和"无权限访问"
   - 无法进行任何操作

4. **查看控制台**:
   - 按 F12 打开开发者工具
   - Console标签应该显示：
     ```
     权限检查结果: {userId: "-64557199688994460741", isAdmin: false, isView: false}
     ```

### 测试2: 有权限用户

1. **准备**: 确保用户的 `isView=true`

2. **访问系统**:
   ```
   http://localhost:8088/?user_id=有权限的用户ID
   ```

3. **预期结果**:
   - 不弹出任何警告
   - 正常显示搜索页面
   - 可以正常使用所有功能

4. **查看控制台**:
   ```
   权限检查结果: {userId: "xxx", isAdmin: true/false, isView: true}
   ```

### 测试3: 接口异常处理

1. **模拟**: 关闭后端服务或修改接口URL

2. **预期结果**:
   - 控制台显示警告："权限检查出错"
   - 为了兼容性，默认允许访问（`hasViewPermission = true`）
   - 页面正常加载

## 部署清单

### 1. 后端修改

- ✅ `AdminCheckService.java` - 添加 `isView` 字段和 `checkUserPermission` 方法
- ✅ `SearchController.java` - 添加 `/api/search/permission/check/{userId}` 接口
- ✅ `SearchService.java` - 添加 `checkUserPermission` 公开方法

### 2. 前端修改

- ✅ `index.html` - 修改 `initializeUserContext()` 函数
  - 调用权限检查接口
  - 检查 `isView` 字段
  - 弹窗提示
  - 显示无权限页面

### 3. 部署步骤

```bash
# 1. 编译
mvn clean package -DskipTests

# 2. 停止旧服务
kill -9 $(lsof -ti:8088)

# 3. 启动新服务
nohup java -jar target/es_admin-*.jar > logs/app.log 2>&1 &

# 4. 查看日志
tail -f logs/app.log | grep -E "权限检查|isView"
```

### 4. 清除缓存

**Chrome/Edge**:
1. Ctrl + Shift + Delete
2. 选择"缓存的图片和文件"
3. 点击"清除数据"
4. Ctrl + F5 强制刷新

**Firefox**:
1. Ctrl + Shift + Delete
2. 选择"缓存"
3. 点击"立即清除"
4. Ctrl + F5 强制刷新

## 验证方法

### 方法1: 浏览器测试

1. 打开浏览器访问系统
2. 在URL中添加无权限用户的ID
3. 观察是否弹出警告并显示无权限页面

### 方法2: 控制台检查

1. F12 打开开发者工具
2. 切换到 Console 标签
3. 查看 "权限检查结果" 日志
4. 切换到 Network 标签
5. 查看 `/api/search/permission/check/` 请求和响应

### 方法3: 后端日志

```bash
# 实时查看权限检查日志
tail -f logs/application.log | grep -E "用户权限检查结果|isView"
```

预期输出（无权限用户）:
```
用户权限检查结果: userId=-64557199688994460741, isAdmin=false, isView=false (isViewObj=false)
```

## 故障排查

### 问题1: 没有弹出警告框

**检查项**:
1. 打开浏览器控制台，查看是否有JavaScript错误
2. 检查 `/api/search/permission/check/` 接口是否返回 `isView: false`
3. 确认浏览器缓存已清除

**调试**:
```javascript
// 在控制台手动测试
fetch('/api/search/permission/check/-64557199688994460741')
  .then(r => r.json())
  .then(data => console.log(data));
```

### 问题2: 弹出警告但页面没有变化

**原因**: JavaScript可能被其他代码阻止

**解决**:
1. 查看控制台是否有错误
2. 检查 `initializeUserContext` 函数是否被正确调用
3. 在代码中添加更多 `console.log` 调试

### 问题3: 有权限的用户也被拒绝

**检查项**:
1. 确认接口返回 `isView: true` 或 `isView` 为 null
2. 检查代码逻辑：`hasViewPermission = result.data.isView !== false`
3. 查看控制台的"权限检查结果"日志

### 问题4: 接口一直失败

**检查项**:
1. 确认后端接口已部署
2. 检查接口URL是否正确
3. 查看后端日志是否有错误
4. 测试接口是否可访问：
   ```bash
   curl http://localhost:8088/api/search/permission/check/test_user_id
   ```

## 权限判断逻辑

### 前端逻辑

```javascript
// 默认策略：宽松（兼容性考虑）
hasViewPermission = result.data.isView !== false

// 说明：
// - isView = true  → hasViewPermission = true  ✅ 允许
// - isView = false → hasViewPermission = false ❌ 拒绝
// - isView = null  → hasViewPermission = true  ✅ 允许（兼容老接口）
// - isView = undefined → hasViewPermission = true ✅ 允许（兼容）
```

### 后端逻辑

```java
// AdminCheckService.java:112
boolean isView = isViewObj != null ? isViewObj : true;

// 说明：
// - 接口返回 isView=true  → isView = true
// - 接口返回 isView=false → isView = false
// - 接口返回 isView=null  → isView = true（默认有权限）
```

### 如果需要更严格的控制

修改为：null 也算无权限

```java
// 后端改为
boolean isView = Boolean.TRUE.equals(isViewObj);
```

```javascript
// 前端改为
hasViewPermission = result.data.isView === true
```

## 安全建议

### 1. 不要依赖纯前端检查

虽然前端会阻止无权限用户，但技术上可以绕过。**真正的权限控制在后端**。

前端检查的目的：
- ✅ 提升用户体验（立即反馈）
- ✅ 减少无效请求
- ❌ 不是安全保障

### 2. 后端必须验证

所有敏感接口都要在后端验证权限：
- `/api/search/search` - 已有权限检查 ✅
- `/api/search/forms` - 已有权限检查 ✅
- 其他接口 - 根据需要添加

### 3. 日志记录

记录所有权限拒绝的操作：
```
用户 xxx 没有查看权限，isView=false
用户 xxx 权限被拒绝，原因: 您没有权限访问，请联系管理员
```

## 总结

现在系统有三层权限防护：

1. **前端检查**（本次新增）:
   - 页面加载时立即检查
   - 弹窗 + 无权限页面
   - 提升用户体验

2. **后端接口检查**:
   - 每个请求都检查权限
   - 返回错误响应
   - 真正的安全保障

3. **前端错误处理**:
   - 显示友好的错误提示
   - 引导用户联系管理员
   - 避免空白页面

三层结合，既保证了安全性，又提供了良好的用户体验。
