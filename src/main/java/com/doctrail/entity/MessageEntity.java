package com.doctrail.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * Stage-1 persistence extension point for the Flyway message table.
 * requestId and replyToMessageId are the future idempotency keys from the contract.
 */
@TableName("message")
public class MessageEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id; //消息唯一编号
    @TableField("conversation_id")
    private Long conversationId;//所属会话 ID。
    private String role;//消息角色，可以是用户、助手或系统。
    private String content;//消息内容。
    @TableField("request_id")
    private String requestId;//一次 AI 请求的唯一标识
    @TableField("request_state")
    private String requestState;//V4 请求状态：RUNNING、SUCCEEDED 或 FAILED
    @TableField("request_error_http")
    private Integer requestErrorHttp; //请求失败时记录的 HTTP 状态码
    @TableField("request_error_code")
    private Integer requestErrorCode;
    @TableField("request_error_message")
    private String requestErrorMessage;
    @TableField("answer_snapshot")
    private String answerSnapshot;//AI 请求成功后，将对外响应对象`ChatAskResponse`序列化存入此字段
    @TableField("answer_snapshot_version")
    private Integer answerSnapshotVersion;//AI 响应的版本号
    @TableField("reply_to_message_id")
    private Long replyToMessageId;//用来实现「消息回复」功能：本条消息是回复哪一条历史消息。
    @TableField("create_time")
    private OffsetDateTime createTime;
    //新增user消息，会有answerSnapshot字段（AI回复的信息），assistant则没有。

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getRequestState() { return requestState; }
    public void setRequestState(String requestState) { this.requestState = requestState; }
    public Integer getRequestErrorHttp() { return requestErrorHttp; }
    public void setRequestErrorHttp(Integer requestErrorHttp) { this.requestErrorHttp = requestErrorHttp; }
    public Integer getRequestErrorCode() { return requestErrorCode; }
    public void setRequestErrorCode(Integer requestErrorCode) { this.requestErrorCode = requestErrorCode; }
    public String getRequestErrorMessage() { return requestErrorMessage; }
    public void setRequestErrorMessage(String requestErrorMessage) { this.requestErrorMessage = requestErrorMessage; }
    public String getAnswerSnapshot() { return answerSnapshot; }
    public void setAnswerSnapshot(String answerSnapshot) { this.answerSnapshot = answerSnapshot; }
    public Integer getAnswerSnapshotVersion() { return answerSnapshotVersion; }
    public void setAnswerSnapshotVersion(Integer answerSnapshotVersion) { this.answerSnapshotVersion = answerSnapshotVersion; }
    public Long getReplyToMessageId() { return replyToMessageId; }
    public void setReplyToMessageId(Long replyToMessageId) { this.replyToMessageId = replyToMessageId; }
    public OffsetDateTime getCreateTime() { return createTime; }
    public void setCreateTime(OffsetDateTime createTime) { this.createTime = createTime; }
}
