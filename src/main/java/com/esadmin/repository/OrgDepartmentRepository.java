package com.esadmin.repository;

import com.esadmin.entity.OrgDepartment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrgDepartmentRepository extends JpaRepository<OrgDepartment, Long> {

    /**
     * 查询所有部门类型的组织单元
     */
    @Query("SELECT d FROM OrgDepartment d WHERE d.type = 'Department' AND (d.isDeleted = 0 OR d.isDeleted IS NULL) ORDER BY d.sortId ASC, d.name ASC")
    List<OrgDepartment> findAllDepartments();

    /**
     * 根据名称搜索部门
     */
    @Query("SELECT d FROM OrgDepartment d WHERE d.type = 'Department' AND (d.isDeleted = 0 OR d.isDeleted IS NULL) AND d.name LIKE %:name% ORDER BY d.sortId ASC, d.name ASC")
    List<OrgDepartment> findDepartmentsByName(@Param("name") String name);

    /**
     * 查询启用的部门
     */
    @Query("SELECT d FROM OrgDepartment d WHERE d.type = 'Department' AND (d.isDeleted = 0 OR d.isDeleted IS NULL) AND d.isEnable = 1 ORDER BY d.sortId ASC, d.name ASC")
    List<OrgDepartment> findEnabledDepartments();

    /**
     * 根据ID列表查询部门
     */
    @Query("SELECT d FROM OrgDepartment d WHERE d.id IN :ids AND d.type = 'Department' AND (d.isDeleted = 0 OR d.isDeleted IS NULL)")
    List<OrgDepartment> findDepartmentsByIds(@Param("ids") List<Long> ids);

    /**
     * 查询所有组织单元（单位/部门）
     */
    @Query("SELECT d FROM OrgDepartment d WHERE (d.isDeleted = 0 OR d.isDeleted IS NULL) AND d.isEnable = 1 ORDER BY d.type DESC, d.sortId ASC, d.name ASC")
    List<OrgDepartment> findAllUnits();
}
