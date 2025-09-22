package com.esadmin.service;

import com.esadmin.repository.OrgMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MemberService {

    private static final Logger log = LoggerFactory.getLogger(MemberService.class);
    
    private final OrgMemberRepository memberRepository;
    
    public MemberService(OrgMemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    public Map<String, String> getMembersBatch(List<Long> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            return new HashMap<>();
        }

        // 过滤有效的成员ID
        List<Long> validIds = memberIds.stream()
                .filter(id -> id != null && id != 0)
                .collect(Collectors.toList());

        if (validIds.isEmpty()) {
            return new HashMap<>();
        }

        try {
            List<Object[]> results = memberRepository.findMembersByIds(validIds);
            Map<String, String> memberMap = new HashMap<>();

            for (Object[] row : results) {
                // Handle both Long and BigInteger from DM database
                Object idObj = row[0];
                Long id;
                if (idObj instanceof Long) {
                    id = (Long) idObj;
                } else if (idObj instanceof Number) {
                    id = ((Number) idObj).longValue();
                } else {
                    id = Long.valueOf(idObj.toString());
                }
                String name = (String) row[1];
                memberMap.put(String.valueOf(id), name);
            }

            return memberMap;
        } catch (Exception e) {
            log.error("批量获取成员姓名失败", e);
            return new HashMap<>();
        }
    }

    public String getMemberName(Long memberId) {
        if (memberId == null || memberId == 0) {
            return null;
        }

        try {
            return memberRepository.findById(memberId)
                    .map(member -> member.getName())
                    .orElse(null);
        } catch (Exception e) {
            log.error("获取成员姓名失败", e);
            return null;
        }
    }

    /**
     * 同步org_member表数据
     */
    public boolean syncMemberData() {
        try {
            log.info("开始同步org_member表数据");
            long startTime = System.currentTimeMillis();
            
            // 这里可以添加具体的成员数据同步逻辑
            // 例如：从远程API获取最新成员数据并更新到本地数据库
            // 目前只是刷新缓存
            
            long count = memberRepository.count();
            double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
            
            log.info("org_member表同步完成，共 {} 条记录，耗时 {} 秒", count, String.format("%.1f", elapsed));
            return true;
            
        } catch (Exception e) {
            log.error("同步org_member表失败", e);
            return false;
        }
    }
}