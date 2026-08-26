package com.geo.consumer;

import com.geo.config.RabbitMQConfig;
import com.geo.service.RpaDispatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class RpaTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(RpaTaskConsumer.class);

    public RpaTaskConsumer() {
    }

    // @RabbitListener(queues = RabbitMQConfig.RPA_TASK_QUEUE)
    // public void handleRpaTask(RpaDispatchService.RpaBatchTaskMessage message) {
    //     log.info("收到 RPA 批量任务: taskId={}, agentCount={}, questionCount={}",
    //             message.getTaskId(),
    //             message.getAgentList() != null ? message.getAgentList().size() : 0,
    //             message.getQuestionList() != null ? message.getQuestionList().size() : 0);
    // }

    @RabbitListener(queues = RabbitMQConfig.DLX_QUEUE)
    public void handleDlxTask(RpaDispatchService.RpaBatchTaskMessage message) {
        log.warn("收到死信队列消息: taskId={}", message.getTaskId());
    }
}
