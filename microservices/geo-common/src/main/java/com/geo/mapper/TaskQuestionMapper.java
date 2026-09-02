package com.geo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geo.entity.TaskQuestion;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 【任务-问题表Mapper】
 *
 * 设计思路：
 * 1. 一个任务包含多条问题，这些问题是让RPA机器人去问AI平台的实际内容。
 * 2. 问题有sortOrder字段，查询时按排序号顺序查出来，保证执行顺序和用户录入一致。
 * 3. selectByTaskNo + deleteByTaskNo 是组合拳，任务修改时"先删旧的再重新插入"。
 *
 * 补充：Excel批量导入的问题也是存在这张表，和手动输入的问题不区分存储。
 */
@Mapper
public interface TaskQuestionMapper extends BaseMapper<TaskQuestion> {

    /** 根据任务号查询所有问题（按sortOrder排序） */
    List<TaskQuestion> selectByTaskNo(@Param("taskNo") String taskNo);

    /** 删除某任务的全部问题（修改任务时调用） */
    @Delete("DELETE FROM task_question WHERE task_no = #{taskNo}")
    void deleteByTaskNo(@Param("taskNo") String taskNo);
}