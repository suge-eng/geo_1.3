package com.geo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geo.entity.AiAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiAccountMapper extends BaseMapper<AiAccount> {

    List<AiAccount> selectAvailableAccounts(@Param("platform") String platform);

    AiAccount selectBestAccount(@Param("platform") String platform);

    @Update("UPDATE ai_account SET daily_used = daily_used + 1, last_request_at = NOW() WHERE id = #{id}")
    int incrementDailyUsed(@Param("id") Long id);

    @Update("UPDATE ai_account SET daily_used = 0, daily_reset_at = #{resetTime} WHERE daily_reset_at < #{resetTime}")
    int resetDailyUsed(@Param("resetTime") LocalDateTime resetTime);

    @Update("UPDATE ai_account SET consecutive_failures = consecutive_failures + 1 WHERE id = #{id}")
    int incrementFailureCount(@Param("id") Long id);

    @Update("UPDATE ai_account SET consecutive_failures = 0 WHERE id = #{id}")
    int resetFailureCount(@Param("id") Long id);

    @Update("UPDATE ai_account SET status = #{status}, cooldown_until = #{cooldownUntil} WHERE id = #{id}")
    int updateStatusAndCooldown(@Param("id") Long id, @Param("status") String status, @Param("cooldownUntil") LocalDateTime cooldownUntil);
}
