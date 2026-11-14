package com.doctrail.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doctrail.entity.ConversationEntity;

/** MyBatis-Plus mapper used by the stage-3 persisted conversation service. */
public interface ConversationMapper extends BaseMapper<ConversationEntity> {
//    MyBatis-Plus 的 Mapper，返回`List<ConversationEntity>`
}
