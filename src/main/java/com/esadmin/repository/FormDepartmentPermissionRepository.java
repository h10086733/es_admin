package com.esadmin.repository;

import com.esadmin.entity.FormDepartmentPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface FormDepartmentPermissionRepository extends JpaRepository<FormDepartmentPermission, Long> {

    /**
     * 根据数据源查询权限配置
     */
    @Query("SELECT p FROM FormDepartmentPermission p WHERE p.sourceType = :sourceType AND p.sourceId = :sourceId AND p.isActive = 1")
    List<FormDepartmentPermission> findBySource(@Param("sourceType") String sourceType, @Param("sourceId") String sourceId);

    /**
     * 根据部门ID查询权限配置
     */
    @Query("SELECT p FROM FormDepartmentPermission p WHERE p.departmentId = :departmentId AND p.isActive = 1")
    List<FormDepartmentPermission> findByDepartmentId(@Param("departmentId") String departmentId);

    /**
     * 根据数据源和部门ID查询权限配置
     */
    @Query("SELECT p FROM FormDepartmentPermission p WHERE p.sourceType = :sourceType AND p.sourceId = :sourceId AND p.departmentId = :departmentId")
    FormDepartmentPermission findBySourceAndDepartmentId(@Param("sourceType") String sourceType, @Param("sourceId") String sourceId, @Param("departmentId") String departmentId);

    /**
     * 检查部门是否有数据源访问权限
     */
    @Query("SELECT COUNT(p) > 0 FROM FormDepartmentPermission p WHERE p.sourceType = :sourceType AND p.sourceId = :sourceId AND p.departmentId = :departmentId AND p.isActive = 1")
    boolean hasPermission(@Param("sourceType") String sourceType, @Param("sourceId") String sourceId, @Param("departmentId") String departmentId);

    /**
     * 获取数据源的所有权限配置（包括部门名称）
     */
    @Query("SELECT p FROM FormDepartmentPermission p WHERE p.sourceType = :sourceType AND p.sourceId = :sourceId ORDER BY p.departmentName")
    List<FormDepartmentPermission> findSourcePermissionsWithDeptName(@Param("sourceType") String sourceType, @Param("sourceId") String sourceId);

    /**
     * 批量删除数据源的所有权限配置
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("DELETE FROM FormDepartmentPermission p WHERE p.sourceType = :sourceType AND p.sourceId = :sourceId")
    void deleteBySource(@Param("sourceType") String sourceType, @Param("sourceId") String sourceId);

    /**
     * 查询所有数据源的权限配置
     */
    @Query("SELECT p FROM FormDepartmentPermission p WHERE p.isActive = 1 ORDER BY p.sourceType, p.sourceName, p.departmentName")
    List<FormDepartmentPermission> findAllActivePermissions();

    /**
     * 检查是否存在该数据源的任何权限配置（用于判断是否默认全部可访问）
     */
    @Query("SELECT COUNT(p) > 0 FROM FormDepartmentPermission p WHERE p.sourceType = :sourceType AND p.sourceId = :sourceId AND p.isActive = 1")
    boolean hasAnyPermissionForSource(@Param("sourceType") String sourceType, @Param("sourceId") String sourceId);
    
    /**
     * 批量查询多个数据源的权限配置
     */
    @Query("SELECT p FROM FormDepartmentPermission p WHERE p.sourceType = :sourceType AND p.sourceId IN :sourceIds AND p.isActive = 1")
    List<FormDepartmentPermission> findBySourceTypeAndIds(@Param("sourceType") String sourceType, @Param("sourceIds") List<String> sourceIds);
    
    /**
     * 批量检查权限
     */
    @Query("SELECT CONCAT(p.sourceType, ':', p.sourceId, ':', p.departmentId) FROM FormDepartmentPermission p " +
           "WHERE p.sourceType IN :sourceTypes AND p.sourceId IN :sourceIds AND p.departmentId IN :departmentIds AND p.isActive = 1")
    List<String> findPermissionKeys(@Param("sourceTypes") List<String> sourceTypes, 
                                   @Param("sourceIds") List<String> sourceIds, 
                                   @Param("departmentIds") List<String> departmentIds);
    
    /**
     * 优化的活跃权限查询 - 只查询必要字段
     */
    @Query("SELECT NEW com.esadmin.entity.FormDepartmentPermission(p.sourceType, p.sourceId, p.sourceName, p.departmentId, p.departmentName, p.permissionType, p.updateTime) " +
           "FROM FormDepartmentPermission p WHERE p.isActive = 1 ORDER BY p.sourceType, p.sourceName, p.departmentName")
    List<FormDepartmentPermission> findAllActivePermissionsOptimized();

    /**
     * 查询指定部门可访问的数据源Key集合
     */
    @Query("SELECT CONCAT(p.sourceType, ':', p.sourceId) FROM FormDepartmentPermission p " +
           "WHERE p.isActive = 1 AND p.departmentId = :departmentId")
    List<String> findActiveSourceKeysByDepartment(@Param("departmentId") String departmentId);

    /**
     * 查询所有已配置权限的数据源Key集合
     */
    @Query("SELECT DISTINCT CONCAT(p.sourceType, ':', p.sourceId) FROM FormDepartmentPermission p WHERE p.isActive = 1")
    List<String> findAllRestrictedSourceKeys();
}
