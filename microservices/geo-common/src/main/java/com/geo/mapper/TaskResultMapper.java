package com.geo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geo.entity.TaskResult;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface TaskResultMapper extends BaseMapper<TaskResult> {

    List<TaskResult> selectByTaskNo(@Param("taskNo") String taskNo);

    List<TaskResult> selectByTaskId(@Param("taskId") Long taskId);

    @Update("UPDATE task_result SET status = #{status} WHERE task_id = #{taskId}")
    int updateStatusByTaskId(@Param("taskId") Long taskId, @Param("status") String status);

    List<TaskResult> selectByStatus(@Param("status") String status);

    @Delete("DELETE FROM task_result WHERE task_no = #{taskNo}")
    void deleteByTaskNo(@Param("taskNo") String taskNo);

    /**
     * 认领任务池中最旧的一个待执行单元（1 问题 x 1 AI = 1 条 task_result）。
     * 通过 JOIN task 排除「未提交/已暂停/已取消」的任务，FIFO 保证公平；
     * FOR UPDATE SKIP LOCKED 保证并发认领不重复、多 worker 安全并行。
     */
    @Select("SELECT tr.* FROM task_result tr " +
            "JOIN task t ON t.id = tr.task_id " +
            "WHERE tr.ai_platform = #{platform} AND tr.status = 'PENDING' " +
            "AND t.status NOT IN ('PENDING', 'PAUSED', 'CANCELLED') " +
            "ORDER BY tr.id ASC LIMIT 1 FOR UPDATE SKIP LOCKED")
    TaskResult claimNextPending(@Param("platform") String platform);

    /** 认领成功后标记为 RUNNING，并写入 worker 与租约截止时间、开始执行时刻。 */
    @Update("UPDATE task_result SET status='RUNNING', assignee=#{assignee}, lease_expires_at=#{leaseExpiresAt}, " +
            "started_at=#{startedAt} " +
            "WHERE id=#{id} AND status='PENDING'")
    int markRunning(@Param("id") Long id, @Param("assignee") String assignee,
                    @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
                    @Param("startedAt") LocalDateTime startedAt);

    /** worker 心跳续约（必须是自己的 RUNNING 单元才续）。 */
    @Update("UPDATE task_result SET lease_expires_at=#{leaseExpiresAt} " +
            "WHERE id=#{id} AND assignee=#{assignee} AND status='RUNNING'")
    int extendLease(@Param("id") Long id, @Param("assignee") String assignee,
                    @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    /** worker 异常中止：归还任务池（回 PENDING 让别的 worker 重试）。 */
    @Update("UPDATE task_result SET status='PENDING', assignee=NULL, lease_expires_at=NULL " +
            "WHERE id=#{id} AND assignee=#{assignee} AND status='RUNNING'")
    int releaseToPending(@Param("id") Long id, @Param("assignee") String assignee);

    /** 回调到达终态后清理租约（不区分 assignee）。 */
    @Update("UPDATE task_result SET assignee=NULL, lease_expires_at=NULL WHERE id=#{id}")
    int clearLeaseByUnitId(@Param("id") Long id);

    /** 清扫：回收租约过期仍处于 RUNNING 的单元，使其回到任务池。 */
    @Update("UPDATE task_result SET status='PENDING', assignee=NULL, lease_expires_at=NULL " +
            "WHERE status='RUNNING' AND lease_expires_at < #{cutoff}")
    int reclaimExpiredLease(@Param("cutoff") LocalDateTime cutoff);

    @Select("SELECT status FROM task WHERE task_no=#{taskNo}")
    String selectTaskStatusByNo(@Param("taskNo") String taskNo);

    /** 巡检：找出「RUNNING 超过阈值仍无进展」且尚未通知过的卡住单元（脚本活着但大概率被验证码等卡住）。 */
    @Select("SELECT tr.* FROM task_result tr " +
            "WHERE tr.status='RUNNING' AND tr.started_at < #{cutoff} AND tr.stuck_notified=0 " +
            "ORDER BY tr.started_at ASC LIMIT 50")
    List<TaskResult> selectStuckRunning(@Param("cutoff") LocalDateTime cutoff);

    /** 通知成功后标记，避免重复发邮件。 */
    @Update("UPDATE task_result SET stuck_notified=1 WHERE id=#{id}")
    int markStuckNotified(@Param("id") Long id);

    /* ------- 任务池看板聚合查询 ------- */

    @Select("SELECT status AS name, COUNT(*) AS value FROM task_result GROUP BY status")
    List<Map<String, Object>> countByStatus();

    @Select("SELECT ai_platform AS name, COUNT(*) AS value FROM task_result GROUP BY ai_platform")
    List<Map<String, Object>> countByPlatform();

    @Select("SELECT COALESCE(assignee, '未认领') AS name, COUNT(*) AS value FROM task_result " +
            "WHERE status='RUNNING' GROUP BY assignee")
    List<Map<String, Object>> countByAssignee();

    @Select("SELECT id, task_no, ai_platform, assignee, status, started_at, lease_expires_at, " +
            "question_text FROM task_result WHERE status='RUNNING' ORDER BY started_at ASC LIMIT 200")
    List<TaskResult> selectRunningUnits();
}
