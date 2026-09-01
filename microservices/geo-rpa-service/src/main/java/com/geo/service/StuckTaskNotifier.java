package com.geo.service;

import com.geo.entity.TaskResult;
import com.geo.mapper.TaskResultMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 任务「卡住」巡检 + 邮件通知。
 *
 * 意义：worker 心跳正常只能说明脚本进程还活着，无法发现「被验证码/弹窗卡住」这类
 * 页面阻塞。本巡检以单个单元 RUNNING 的持续时长为准——超过阈值仍未回调完成，即判定
 * 卡住，并发送邮件通知用户去人工处理（如过验证码），标记后不再重复通知。
 *
 * 注意：这里只通知、不回收。回收租约过期（脚本已死）是 RpaWorkerDispatcherService
 * 的清扫器职责；卡住场景脚本还活着，等用户处理完即可继续，避免误回收。
 */
@Service
public class StuckTaskNotifier {

    private static final Logger log = LoggerFactory.getLogger(StuckTaskNotifier.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TaskResultMapper taskResultMapper;
    private final JavaMailSender mailSender;

    @Value("${geo.rpa.notify.enabled:false}")
    private boolean enabled;

    @Value("${geo.rpa.notify.stuck-minutes:5}")
    private long stuckMinutes;

    @Value("${geo.rpa.notify.to:}")
    private List<String> to;

    public StuckTaskNotifier(TaskResultMapper taskResultMapper, JavaMailSender mailSender) {
        this.taskResultMapper = taskResultMapper;
        this.mailSender = mailSender;
    }

    @Scheduled(fixedDelayString = "${geo.rpa.notify.scan-ms:60000}",
            initialDelayString = "20000")
    @Transactional
    public void checkStuckUnits() {
        if (!enabled) {
            return;
        }
        if (to == null || to.stream().allMatch(String::isBlank)) {
            log.warn("卡住通知已开启但未配置收件人 geo.rpa.notify.to，跳过通知");
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(stuckMinutes);
        List<TaskResult> stuckUnits;
        try {
            stuckUnits = taskResultMapper.selectStuckRunning(cutoff);
        } catch (Exception e) {
            log.warn("查询卡住单元失败: {}", e.getMessage());
            return;
        }

        if (stuckUnits == null || stuckUnits.isEmpty()) {
            return;
        }

        try {
            mailSender.send(buildMessage(stuckUnits));
            log.warn("发现 {} 个卡住单元，已发送邮件通知，收件人={}", stuckUnits.size(), to);
            for (TaskResult unit : stuckUnits) {
                taskResultMapper.markStuckNotified(unit.getId());
            }
        } catch (Exception e) {
            log.error("发送卡住通知邮件失败，将下轮重试: {}", e.getMessage());
        }
    }

    private SimpleMailMessage buildMessage(List<TaskResult> units) {
        StringBuilder sb = new StringBuilder();
        sb.append("您好，以下 ").append(units.size()).append(" 个任务单元疑似在 AI 平台上卡住（可能弹了验证码），")
          .append("请打开对应电脑人工处理：\n\n");

        for (TaskResult u : units) {
            sb.append("• 单元ID: ").append(u.getId()).append("\n");
            sb.append("  任务号: ").append(u.getTaskNo()).append("\n");
            sb.append("  AI平台: ").append(u.getAiPlatform()).append("\n");
            sb.append("  执行机器: ").append(u.getAssignee()).append("\n");
            sb.append("  开始时间: ").append(u.getStartedAt() != null ? u.getStartedAt().format(FMT) : "-").append("\n");
            sb.append("  问题: ").append(truncate(u.getQuestionText(), 60)).append("\n\n");
        }
        sb.append("处理完验证码后脚本会自动继续；如需彻底终止请到任务池看板操作。");

        SimpleMailMessage message = new SimpleMailMessage();
        message.setSubject("【任务调度】有 " + units.size() + " 个 AI 任务单元疑似卡住，请及时处理");
        message.setText(sb.toString());
        message.setTo(to.toArray(new String[0]));
        return message;
    }

    private String truncate(String s, int n) {
        if (s == null) {
            return "-";
        }
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}