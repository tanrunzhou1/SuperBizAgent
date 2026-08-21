package org.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.entity.ToolInvocationEntity;

/**
 * 工具调用审计主记录数据访问接口。
 */
@Mapper
public interface ToolInvocationMapper extends BaseMapper<ToolInvocationEntity> {
}
