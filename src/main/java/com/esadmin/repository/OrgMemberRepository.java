package com.esadmin.repository;

import com.esadmin.entity.OrgMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public interface OrgMemberRepository extends JpaRepository<OrgMember, Long> {
    
    @Query(value = "SELECT ID, NAME FROM ORG_MEMBER WHERE ID IN :ids", nativeQuery = true)
    List<Object[]> findMembersByIds(@Param("ids") List<Long> ids);
}