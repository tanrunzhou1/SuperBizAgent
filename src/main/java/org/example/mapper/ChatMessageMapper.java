package org.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.entity.ChatMessageEntity;

/**
 * 聊天消息数据访问接口。
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {
    /**
     * 查询会话当前最大消息序号。
     *
     * @param sessionId 会话标识
     * @return 最大序号；没有消息时为 0
     */
    @Select("SELECT COALESCE(MAX(sequence_no), 0) FROM chat_message WHERE session_id = #{sessionId}")
    Integer selectMaxSequenceNo(@Param("sessionId") String sessionId);
}
