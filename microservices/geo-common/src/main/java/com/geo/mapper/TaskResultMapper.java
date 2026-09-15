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

/**
 * 【任务结果表Mapper - 分布式调度核心】
 *
 * =====================================================
 * 设计思路（这是整个系统最核心的部分之一）：
 * =====================================================
 *
 * 1. 什么是 TaskResult？
 *    一个 Task 有 Q 个问题、A 个AI平台。提交任务后，我们把它拆成 Q×A 个小任务。
 *    每个小任务就是 1条 TaskResult 记录 = "问某个AI平台某个问题"。
 *    例：任务有5个问题 × 3个AI平台 = 生成15条 task_result。
 *
 * 2. 什么是"租约机制"（Lease）？
 *    因为有多台 RPA Worker（机器人）同时抢任务，需要保证：
 *    - 一个小任务不会被两个Worker重复执行
 *    - 如果一个Worker执行中崩溃了，别人可以接替
 *
 *    解决思路：类似"图书馆借书"
 *    a) claimNextPending：把任务"借走"（从PENDING→RUNNING），并登记借用人assignee
 *    b) 借走时有个"还书期限"（lease_expires_at，租约到期时间）
 *    c) Worker活着就定期"续借"（extendLease心跳）
 *    d) 如果Worker挂了 → 不会续借 → 租约到期 → 定时任务reclaimExpiredLease把它收回去
 *
 * 3. 并发安全核心：FOR UPDATE SKIP LOCKED
 *    这是MySQL 8+的高级特性。多个Worker同时抢任务时：
 *    - 第一个人SELECT会拿到行级锁，别人拿不到就跳过（SKIP LOCKED），不会等待
 *    - 每个人拿到的是不同的行，保证不重复不冲突
 *    这是实现"多Worker水平扩展"最关键的一行SQL！
 *
 * 4. 状态巡检：
 *    selectStuckRunning用来发现"僵尸任务"——状态是RUNNING、心跳也在续，但实际上RPA被验证码卡住了。
 *    这种情况通过started_at判断：如果开始执行超过阈值时间还没完成，就发告警邮件给运维。
 */
@Mapper
public interface TaskResultMapper extends BaseMapper<TaskResult> {

    /** 根据任务号查询所有子任务（包含各种状态） */
    List<TaskResult> selectByTaskNo(@Param("taskNo") String taskNo);

    /** 根据任务ID查询所有子任务 */
    List<TaskResult> selectByTaskId(@Param("taskId") Long taskId);

    /** 批量把某个任务的所有子任务改成某状态（如任务暂停/取消时用） */
    @Update("UPDATE task_result SET status = #{status} WHERE task_id = #{taskId}")
    int updateStatusByTaskId(@Param("taskId") Long taskId, @Param("status") String status);

    /** 按状态查询子任务（如找出所有PENDING的） */
    List<TaskResult> selectByStatus(@Param("status") String status);

    /** 删除某任务的全部子任务（任务修改时用，删除后重新生成） */
    @Delete("DELETE FROM task_result WHERE task_no = #{taskNo}")
    void deleteByTaskNo(@Param("taskNo") String taskNo);

    /**
     * 【任务认领】从任务池中抢一个待执行的小任务
     *
     * 核心机制：
     * 1. JOIN task表：确保父任务状态是有效的（排除未提交/暂停/取消的任务）
     * 2. 按id升序：FIFO先进先出，早创建的任务先被执行
     * 3. FOR UPDATE SKIP LOCKED：最关键！多Worker并发时不重复不等待
     */
    @Select("SELECT tr.* FROM task_result tr " +
            "JOIN task t ON t.id = tr.task_id " +
            "WHERE tr.ai_platform = #{platform} AND tr.status = 'PENDING' " +
            "AND t.status NOT IN ('PENDING', 'PAUSED', 'CANCELLED') " +
            "ORDER BY tr.id ASC LIMIT 1 FOR UPDATE SKIP LOCKED")
    TaskResult claimNextPending(@Param("platform") String platform);

    /**
     * 【认领成功后标记执行中】
     * 条件 WHERE status='PENDING' 是双重保险，防止极端并发下重复标记。
     * 返回值=0说明已经被别人抢走了，调用方需要重新抢。
     */
    @Update("UPDATE task_result SET status='RUNNING', assignee=#{assignee}, lease_expires_at=#{leaseExpiresAt}, " +
            "started_at=#{startedAt} " +
            "WHERE id=#{id} AND status='PENDING'")
    int markRunning(@Param("id") Long id, @Param("assignee") String assignee,
                    @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
                    @Param("startedAt") LocalDateTime startedAt);

    /**
     * 【心跳续约】
     * Worker每隔一段时间（如30秒）调用一次，说"我还活着呢，再给我续点时间"。
     * 必须校验assignee，防止A续走了B的任务。
     */
    @Update("UPDATE task_result SET lease_expires_at=#{leaseExpiresAt} " +
            "WHERE id=#{id} AND assignee=#{assignee} AND status='RUNNING'")
    int extendLease(@Param("id") Long id, @Param("assignee") String assignee,
                    @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    /**
     * 【主动归还】Worker正常关闭/出错时，把任务主动放回任务池让别人执行
     */
    @Update("UPDATE task_result SET status='PENDING', assignee=NULL, lease_expires_at=NULL " +
            "WHERE id=#{id} AND assignee=#{assignee} AND status='RUNNING'")
    int releaseToPending(@Param("id") Long id, @Param("assignee") String assignee);

    /** 【清理租约】任务完成/失败回调成功后，把assignee和租约清掉（审计历史可以保留） */
    @Update("UPDATE task_result SET assignee=NULL, lease_expires_at=NULL WHERE id=#{id}")
    int clearLeaseByUnitId(@Param("id") Long id);

    /**
     * 【认领时绑定账号】worker 认领单元时，如果已经从账号池借到了账号，
     * 就把 account_id 写入 task_result，回调时好知道用哪个账号更新。
     * 条件带 status='RUNNING' 双重保险：只有刚认领到的 RUNNING 单元才能绑定。
     */
    @Update("UPDATE task_result SET account_id = #{accountId} WHERE id = #{id} AND status = 'RUNNING'")
    int bindAccountToUnit(@Param("id") Long id, @Param("accountId") Long accountId);

    /**
     * 【死任务回收】定时任务定期调用：
     * 租约过期（Worker没续心跳）+ 还在RUNNING → 说明Worker大概率崩溃了
     * → 把任务放回PENDING状态，让其他健康的Worker接手。
     */
    @Update("UPDATE task_result SET status='PENDING', assignee=NULL, lease_expires_at=NULL " +
            "WHERE status='RUNNING' AND lease_expires_at < #{cutoff}")
    int reclaimExpiredLease(@Param("cutoff") LocalDateTime cutoff);

    /** 根据任务号查父任务状态（RPA收到回调时，先判断一下父任务是否已被取消） */
    @Select("SELECT status FROM task WHERE task_no=#{taskNo}")
    String selectTaskStatusByNo(@Param("taskNo") String taskNo);

    /**
     * 【僵尸任务巡检】找出"执行时间远超正常阈值"的RUNNING任务。
     * stuck_notified=0 保证只告警一次，避免邮件轰炸。
     */
    @Select("SELECT tr.* FROM task_result tr " +
            "WHERE tr.status='RUNNING' AND tr.started_at < #{cutoff} AND tr.stuck_notified=0 " +
            "ORDER BY tr.started_at ASC LIMIT 50")
    List<TaskResult> selectStuckRunning(@Param("cutoff") LocalDateTime cutoff);

    /** 告警邮件发送成功后标记，防止下次再查出来重复发 */
    @Update("UPDATE task_result SET stuck_notified=1 WHERE id=#{id}")
    int markStuckNotified(@Param("id") Long id);

    // ==================== 任务池看板聚合查询（运维监控大屏用）====================

    /** 按状态统计总数：PENDING多少、RUNNING多少、SUCCESS多少... → 画饼图 */
    @Select("SELECT status AS name, COUNT(*) AS value FROM task_result GROUP BY status")
    List<Map<String, Object>> countByStatus();

    /** 按AI平台统计：豆包多少条、Kimi多少条... → 画饼图 */
    @Select("SELECT ai_platform AS name, COUNT(*) AS value FROM task_result GROUP BY ai_platform")
    List<Map<String, Object>> countByPlatform();

    /** 按Worker机器统计（正在跑的）：哪个Worker承担了多少任务 → 看负载均衡情况 */
    @Select("SELECT COALESCE(assignee, '未认领') AS name, COUNT(*) AS value FROM task_result " +
            "WHERE status='RUNNING' GROUP BY assignee")
    List<Map<String, Object>> countByAssignee();

    /** 查看当前正在执行中的前200条任务明细：运维排障用（谁在跑什么，跑了多久） */
    @Select("SELECT id, task_no, ai_platform, assignee, status, started_at, lease_expires_at, " +
            "question_text FROM task_result WHERE status='RUNNING' ORDER BY started_at ASC LIMIT 200")
    List<TaskResult> selectRunningUnits();
}