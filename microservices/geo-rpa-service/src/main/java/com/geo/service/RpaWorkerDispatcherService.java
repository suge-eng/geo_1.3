package com.geo.service;

import com.geo.entity.TaskResult;
import com.geo.mapper.TaskResultMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 任务池分发器（dispatch / scheduler 中枢）。
 *
 * 所有用户提交的任务会被拆成独立工作单元（1 问题 x 1 AI = 1 条 task_result）存入数据库任务池；
 * 各平台的 worker 脚本通过 HTTP 认领，跑完一个单元回调后再认领下一个，全程无全局锁。
 *
 * 分配策略：
 *   - 认领：SELECT ... FOR UPDATE SKIP LOCKED 原子认领，先到先得（FIFO），天然公平、并发安全。
 *   - 租约：认领时写入 lease_expires_at，worker 心跳续约；租约过期未续的单元被清扫器回收回池，
 *           这样脚本宕机 / 电脑关机不会永久占住单元，多机可安全扩容。
 *   - 暂停/终止：assign 时通过 JOIN task 排除已暂停/取消/未提交的任务，使其单元不再被派发。
 */
@Service
public class RpaWorkerDispatcherService {

    private static final Logger log = LoggerFactory.getLogger(RpaWorkerDispatcherService.class);

    private final TaskResultMapper taskResultMapper;

    /** 单个单元认领后的租约时长（秒）。worker 每隔一段时间心跳续约。 */
    @Value("${geo.rpa.worker.lease-seconds:1200}")
    private long leaseSeconds;

    public RpaWorkerDispatcherService(TaskResultMapper taskResultMapper) {
        this.taskResultMapper = taskResultMapper;
    }

    /**
     * 认领一个待执行单元。
     *
     * @param platform 平台 code（如 deepseek / doubao / kimi / wenxin / qianwen / tencent）
     * @param workerId worker 标识（机器名）
     * @return 认领到的单元；当前无可执行单元时返回 null
     */
    @Transactional
    public TaskResult claimUnit(String platform, String workerId) {
        LocalDateTime now = LocalDateTime.now();
        // 关键一步：claimNextPending 底层使用 SELECT ... FOR UPDATE SKIP LOCKED，
        // 并发下多个 worker 同时认领也只会各自拿到「没有被别人锁住」的不同行，
        // 因此无需任何全局锁即可多机安全分配，先到先得。
        TaskResult unit = taskResultMapper.claimNextPending(platform);
        if (unit == null) {
            return null;
        }
        // 认领成功立即把单元标记为 RUNNING 并写入租约到期时间（now + leaseSeconds），
        // started_at 用于后续卡住检测（见 StuckTaskNotifier）。
        taskResultMapper.markRunning(unit.getId(), workerId, now.plusSeconds(leaseSeconds), now);
        unit.setStatus("RUNNING");
        unit.setAssignee(workerId);
        unit.setLeaseExpiresAt(now.plusSeconds(leaseSeconds));
        unit.setStartedAt(now);
        log.info("worker[{}] 认领单元: unitId={}, taskNo={}, platform={}, question={}",
                workerId, unit.getId(), unit.getTaskNo(), platform,
                unit.getQuestionText() != null ? unit.getQuestionText().substring(0, Math.min(30, unit.getQuestionText().length())) : "");
        return unit;
    }

    /**
     * worker 心跳续约。只有该单元的当前持有者才能续约。
     *
     * @return 是否续约成功（失败表示单元已不属于该 worker，应中止）
     */
    @Transactional
    public boolean heartbeat(Long unitId, String workerId) {
        if (unitId == null) {
            return false;
        }
        int updated = taskResultMapper.extendLease(unitId, workerId, LocalDateTime.now().plusSeconds(leaseSeconds));
        // extendLease 的 WHERE 同时限定 id 与 worker_id，只有当前持有者能续约成功，
        // 防止 worker 之间误续对方租约、或单元已被回收后仍被续命。
        return updated > 0;
    }

    /**
     * worker 异常中止：把单元归还任务池，让其它 worker 可以重试。
     */
    @Transactional
    public void abort(Long unitId, String workerId) {
        if (unitId == null) {
            return;
        }
        int updated = taskResultMapper.releaseToPending(unitId, workerId);
        // releaseToPending 同样校验 worker_id：仅持有者能归还，避免误伤他人正在执行的单元。
        if (updated > 0) {
            log.info("worker[{}] 中止并归还单元到任务池: unitId={}", workerId, unitId);
        }
    }

    /**
     * 清理某个单元的租约（回调到达终态后调用）。
     */
    @Transactional
    public void finish(Long unitId) {
        if (unitId == null) {
            return;
        }
        taskResultMapper.clearLeaseByUnitId(unitId);
    }

    /**
     * 清扫器：回收租约过期的 RUNNING 单元，使其回到任务池。
     * 覆盖脚本崩溃、电脑关机、网络断开等异常场景，保证任务不卡死。
     */
    @Scheduled(fixedDelayString = "${geo.rpa.worker.reclaim-ms:30000}",
            initialDelayString = "${geo.rpa.worker.reclaim-initial-ms:10000}")
    @Transactional
    public void reclaimExpiredUnits() {
        // cutoff 额外再放宽 60 秒：给「心跳恰好延迟几秒」的 worker 留缓冲，
        // 只有明显超过一个租约周期仍无心跳的单元才会被判定为已离线而回收，避免误杀。
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(leaseSeconds + 60);
        int reclaimed = taskResultMapper.reclaimExpiredLease(cutoff);
        if (reclaimed > 0) {
            log.warn("清扫器回收了 {} 个租约过期的单元（脚本可能已离线），已归还任务池", reclaimed);
        }
    }
}