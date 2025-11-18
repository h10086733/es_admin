package com.esadmin.repository;

import com.esadmin.entity.ReviewPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewPolicyRepository extends JpaRepository<ReviewPolicy, Long> {
    
    /**
     * 根据数据源类型和ID查找审核策略
     */
    @Query("SELECT rp FROM ReviewPolicy rp WHERE rp.sourceType = :sourceType AND rp.sourceId = :sourceId")
    Optional<ReviewPolicy> findBySourceTypeAndSourceId(@Param("sourceType") String sourceType, 
                                                       @Param("sourceId") String sourceId);
    
    /**
     * 根据数据源类型查找所有审核策略
     */
    List<ReviewPolicy> findBySourceTypeOrderBySourceName(String sourceType);
    
    /**
     * 根据审核模式查找所有审核策略
     */
    List<ReviewPolicy> findByReviewModeOrderBySourceName(String reviewMode);
    
    /**
     * 删除指定数据源的审核策略
     */
    void deleteBySourceTypeAndSourceId(String sourceType, String sourceId);
}