package com.esadmin.repository;

import com.esadmin.entity.FormDefinition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FormDefinitionRepository extends JpaRepository<FormDefinition, Long> {
    
    @Query("SELECT f FROM FormDefinition f WHERE f.deleteFlag = 0 and form_type=6 and appbind_info LIKE '{\"formCode\":\"SGZB%' ")
    List<FormDefinition> findByDeleteFlag(Integer deleteFlag);
    
    @Query("SELECT f FROM FormDefinition f WHERE f.deleteFlag = 0  and form_type=6 and appbind_info LIKE '{\"formCode\":\"SGZB%' AND f.name LIKE %:search%")
    Page<FormDefinition> findByDeleteFlagAndNameContaining(@Param("search") String search, Pageable pageable);
    
    @Query("SELECT f FROM FormDefinition f WHERE f.deleteFlag = 0 and form_type=6 and appbind_info LIKE '{\"formCode\":\"SGZB%' ")
    Page<FormDefinition> findByDeleteFlag(Integer deleteFlag, Pageable pageable);
}