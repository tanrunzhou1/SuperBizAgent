package org.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.example.entity.ChatSessionEntity;

/**
 * 聊天会话数据访问接口。
 */
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSessionEntity> {
    /**
     * 创建会话；会话已存在时忽略写入。
     *
     * @param sessionId 会话标识
     * @return 受影响行数
     */
    @Insert("INSERT OR IGNORE INTO chat_session (session_id) VALUES (#{sessionId})")
    int insertIgnore(String sessionId);

    /**
     * 查询并锁定会话记录。
     *
     * @param sessionId 会话标识
     * @return 会话记录；不存在时为 null
     */
    @Select("SELECT * FROM chat_session WHERE session_id = #{sessionId}")
    ChatSessionEntity selectBySessionId(String sessionId);
}
