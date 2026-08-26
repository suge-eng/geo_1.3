package com.geo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geo.entity.TaskReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TaskReportMapper extends BaseMapper<TaskReport> {

    List<TaskReport> selectByTaskNo(@Param("taskNo") String taskNo);
}
