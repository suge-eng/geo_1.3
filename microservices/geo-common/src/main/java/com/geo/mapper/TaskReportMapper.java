package com.geo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geo.entity.TaskReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 【任务分析报告历史表Mapper】
 *
 * 设计思路：
 * 1. Task表只存"当前最新的报告快照"（report字段），但周期任务（日报/周报）会产生多份历史报告。
 * 2. 历史报告单独存在 task_report 表，一条记录 = 一次完整的分析输出。
 * 3. selectByTaskNo查询某任务的所有历史报告，按时间倒序后，用户可以点"对比上周报告"。
 *
 * 好处：历史数据不污染主表，主表只负责当前状态；同时可以支持"报告历史回溯"和"趋势分析"。
 */
@Mapper
public interface TaskReportMapper extends BaseMapper<TaskReport> {

    /** 查询某任务的全部历史报告（按reportDate倒序在内存中排序） */
    List<TaskReport> selectByTaskNo(@Param("taskNo") String taskNo);
}