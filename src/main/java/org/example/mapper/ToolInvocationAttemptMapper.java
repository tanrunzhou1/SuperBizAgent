package org.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.entity.ToolInvocationAttemptEntity;

/**
 * 工具调用尝试记录数据访问接口。
 */
@Mapper
public interface ToolInvocationAttemptMapper extends BaseMapper<ToolInvocationAttemptEntity> {
}
