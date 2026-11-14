package com.doctrail.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doctrail.entity.AgentRunEntity;
import org.apache.ibatis.annotations.Select;
import java.util.List;

public interface AgentRunMapper extends BaseMapper<AgentRunEntity> {
    /** Only existing audits are compensated; stage-5 historical messages are untouched. */
    //查询需要补偿重试审计收尾的 requestId 列表
    //审审计头记录还没收尾成功：计头记录还没收尾成功
    @Select("""
            SELECT r.request_id FROM agent_run r
            JOIN message m ON m.request_id = r.request_id AND m.role = 'user'
            WHERE r.status = 'RUNNING' AND m.request_state IN ('SUCCEEDED', 'FAILED')
            ORDER BY r.started_at, r.id LIMIT 100
            """)
    List<String> selectPendingTerminalRequestIds();
}
