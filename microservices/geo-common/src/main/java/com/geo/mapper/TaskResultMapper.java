package com.geo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geo.entity.TaskResult;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface TaskResultMapper extends BaseMapper<TaskResult> {

    List<TaskResult> selectByTaskNo(@Param("taskNo") String taskNo);

    List<TaskResult> selectByTaskId(@Param("taskId") Long taskId);

    @Update("UPDATE task_result SET status = #{status} WHERE task_id = #{taskId}")
    int updateStatusByTaskId(@Param("taskId") Long taskId, @Param("status") String status);

    List<TaskResult> selectByStatus(@Param("status") String status);

    @Delete("DELETE FROM task_result WHERE task_no = #{taskNo}")
    void deleteByTaskNo(@Param("taskNo") String taskNo);
}
