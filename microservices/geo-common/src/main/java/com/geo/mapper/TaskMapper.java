package com.geo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geo.entity.Task;
import org.apache.ibatis.annotations.Mapper;

/**
 * 【任务主表Mapper】
 *
 * 设计思路：
 * 1. MyBatis-Plus的BaseMapper自带了常用CRUD（增删改查）方法，不需要手写SQL。
 * 2. 继承BaseMapper<Task>后自动获得：insert、deleteById、updateById、selectById、selectPage等方法。
 * 3. Task表是整个系统的核心主表，其他表（task_ai、task_question、task_result）都通过task_no关联它。
 *
 * 注：目前没有自定义查询，说明Task的简单查询通过MyBatis-Plus的QueryWrapper即可完成。
 * 如果后续需要复杂多表查询（比如关联统计结果数），可以在这里加自定义方法。
 */
@Mapper
public interface TaskMapper extends BaseMapper<Task> {
}