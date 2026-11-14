package com.doctrail.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doctrail.entity.MessageEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

/** MyBatis-Plus mapper for Java-owned conversation messages. */
public interface MessageMapper extends BaseMapper<MessageEntity> {

    @Select("""
            SELECT request_row.*
            FROM message request_row
            WHERE request_row.role = 'user'
              AND request_row.request_state = 'RUNNING'
              AND request_row.update_time < #{cutoff}
              AND NOT EXISTS (
                  SELECT 1
                  FROM message assistant_row
                  WHERE assistant_row.reply_to_message_id = request_row.id
                    AND assistant_row.role = 'assistant'
              )
            ORDER BY request_row.update_time ASC, request_row.id ASC
            LIMIT 100
            FOR UPDATE
            """)
    List<MessageEntity> selectTimedOutRunningForUpdate(@Param("cutoff") OffsetDateTime cutoff);
}
