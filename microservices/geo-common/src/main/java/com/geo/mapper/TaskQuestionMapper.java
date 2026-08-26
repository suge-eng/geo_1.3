package com.geo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geo.entity.TaskQuestion;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TaskQuestionMapper extends BaseMapper<TaskQuestion> {

    List<TaskQuestion> selectByTaskNo(@Param("taskNo") String taskNo);

    @Delete("DELETE FROM task_question WHERE task_no = #{taskNo}")
    void deleteByTaskNo(@Param("taskNo") String taskNo);
}
