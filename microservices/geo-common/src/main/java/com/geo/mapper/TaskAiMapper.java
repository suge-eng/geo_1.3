package com.geo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geo.entity.TaskAi;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 【任务-AI平台关联表Mapper】
 *
 * 设计思路：
 * 1. 一个任务可以选择多个AI平台执行（如同时选豆包+Kimi+DeepSeek），所以用中间表 task_ai 存多对多关系。
 * 2. selectByTaskNo：查询某个任务选了哪些AI平台。
 * 3. deleteByTaskNo：用户修改任务时，先删旧关联再重新插入（比"判断哪些该删哪些该加"简单很多）。
 *
 * 设计权衡：用"先全删再重插"代替增量更新，优点是代码简单不易错，缺点是多写了几条SQL。
 * 因为一个任务选的AI平台不会太多（通常≤10个），这点性能损耗完全可接受。
 */
@Mapper
public interface TaskAiMapper extends BaseMapper<TaskAi> {

    /** 根据任务编号查询它选择的所有AI平台配置 */
    List<TaskAi> selectByTaskNo(@Param("taskNo") String taskNo);

    /** 删除某个任务的所有AI平台关联（修改任务时调用） */
    @Delete("DELETE FROM task_ai WHERE task_no = #{taskNo}")
    void deleteByTaskNo(@Param("taskNo") String taskNo);
}