import { useEffect, useRef, useState } from 'react';
import { chatApi } from '../api/chat';
import Loading from '../components/Loading';
import Icon from '../components/Icon';

const TEXTAREA_MIN_HEIGHT = 52;
const TEXTAREA_MAX_HEIGHT = 140;

const fmt = value => value
  ? new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date(value))
  : '';

const asItems = data => Array.isArray(data) ? data : data?.items || [];

const mergeConversations = (localItems, serverItems) => {
  const seen = new Set();
  return [...localItems, ...serverItems].filter(item => {
    if (!item?.id || seen.has(item.id)) return false;
    seen.add(item.id);
    return true;
  });
};

function SourceList({ sources }) {
  if (!sources?.length) return null;

  const byDocument = new Map();
  for (const source of sources) {
    if (!byDocument.has(source.docId)) byDocument.set(source.docId, source);
  }

  return (
    <div className="source-list">
      {[...byDocument.values()].map(source => (
        <span key={source.docId}>
          <Icon name="file" size={13} />来源：{source.docName || source.docId} · {source.score?.toFixed?.(2) || '-'}
        </span>
      ))}
    </div>
  );
}

export default function ChatPage() {
  const [conversations, setConversations] = useState([]);
  const [active, setActive] = useState(null);
  const [draft, setDraft] = useState(true);
  const [messages, setMessages] = useState([]);
  const [messagesLoadedFor, setMessagesLoadedFor] = useState(null);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [actionLoading, setActionLoading] = useState(false);
  const [feedbackLoading, setFeedbackLoading] = useState(null);
  const [editingTitle, setEditingTitle] = useState(false);
  const [titleDraft, setTitleDraft] = useState('');
  const [error, setError] = useState('');
  const activeIdRef = useRef(null);
  const messagesSeqRef = useRef(0);
  const listSeqRef = useRef(0);
  const sendInFlightRef = useRef(false);
  const messageListRef = useRef(null);
  const textareaRef = useRef(null);
  const busy = loading || actionLoading;

  const resetTextareaHeight = () => {
    const element = textareaRef.current;
    if (!element) return;
    element.style.height = `${TEXTAREA_MIN_HEIGHT}px`;
    element.style.overflowY = 'hidden';
  };

  const resizeTextarea = element => {
    if (!element) return;
    element.style.height = 'auto';
    const nextHeight = Math.max(TEXTAREA_MIN_HEIGHT, Math.min(element.scrollHeight, TEXTAREA_MAX_HEIGHT));
    element.style.height = `${nextHeight}px`;
    element.style.overflowY = element.scrollHeight > TEXTAREA_MAX_HEIGHT ? 'auto' : 'hidden';
  };

  const focusInput = () => {
    window.requestAnimationFrame(() => textareaRef.current?.focus());
  };

  const selectConversation = target => {
    if (!target?.id || target.id === activeIdRef.current || busy) return;
    messagesSeqRef.current += 1;
    setMessages([]);
    setMessagesLoadedFor(null);
    setActive(target);
    activeIdRef.current = target.id;
    setDraft(false);
    setEditingTitle(false);
    setTitleDraft('');
    setError('');
    resetTextareaHeight();
  };

  const startNewDraft = () => {
    if (busy) return;
    if (draft) {
      focusInput();
      return;
    }
    if (!active?.id || messagesLoadedFor !== active.id || messages.length === 0) {
      focusInput();
      return;
    }
    messagesSeqRef.current += 1;
    setActive(null);
    activeIdRef.current = null;
    setDraft(true);
    setMessages([]);
    setMessagesLoadedFor(null);
    setInput('');
    setEditingTitle(false);
    setTitleDraft('');
    setError('');
    resetTextareaHeight();
    focusInput();
  };

  useEffect(() => {
    const requestSeq = ++listSeqRef.current;
    chatApi.listConversations()
      .then(data => {
        if (requestSeq !== listSeqRef.current) return;
        setConversations(items => mergeConversations(items, asItems(data)));
      })
      .catch(caught => {
        if (requestSeq === listSeqRef.current) setError(caught.message);
      });
    return () => {
      listSeqRef.current += 1;
      messagesSeqRef.current += 1;
    };
  }, []);

  useEffect(() => {
    const targetId = active?.id;
    if (!targetId) return undefined;
    const requestSeq = ++messagesSeqRef.current;
    chatApi.listMessages(targetId)
      .then(data => {
        if (requestSeq !== messagesSeqRef.current || targetId !== activeIdRef.current) return;
        const history = asItems(data);
        setMessages(items => history.length === 0 && items.length > 0 ? items : history);
        setMessagesLoadedFor(targetId);
      })
      .catch(caught => {
        if (requestSeq === messagesSeqRef.current && targetId === activeIdRef.current) {
          setError(caught.message);
        }
      });
    return undefined;
  }, [active?.id]);

  useEffect(() => {
    const frame = window.requestAnimationFrame(() => {
      const element = messageListRef.current;
      if (element) element.scrollTop = element.scrollHeight;
    });
    return () => window.cancelAnimationFrame(frame);
  }, [messages, loading, active?.id, messagesLoadedFor]);

  const beginRename = () => {
    if (!active || busy) return;
    setTitleDraft(active.title || '');
    setEditingTitle(true);
    setError('');
  };

  const cancelRename = () => {
    setEditingTitle(false);
    setTitleDraft('');
  };

  const saveRename = async event => {
    event.preventDefault();
    const title = titleDraft.trim();
    const targetId = active?.id;
    if (!targetId || !title || busy) return;
    setActionLoading(true);
    setError('');
    try {
      const updated = await chatApi.updateConversation(targetId, { title });
      setConversations(items => items.map(item => item.id === targetId ? updated : item));
      if (activeIdRef.current === targetId) {
        setActive(updated);
        activeIdRef.current = updated.id;
        setEditingTitle(false);
        setTitleDraft('');
      }
    } catch (caught) {
      if (activeIdRef.current === targetId) setError(caught.message);
    } finally {
      setActionLoading(false);
    }
  };

  const removeConversation = async () => {
    const removedId = active?.id;
    if (!removedId || busy) return;
    if (!window.confirm(`确定删除会话“${active.title || '未命名对话'}”吗？`)) return;
    setActionLoading(true);
    setError('');
    try {
      await chatApi.deleteConversation(removedId);
      setConversations(items => items.filter(item => item.id !== removedId));
      if (activeIdRef.current === removedId) {
        messagesSeqRef.current += 1;
        setActive(null);
        activeIdRef.current = null;
        setDraft(true);
        setMessages([]);
        setMessagesLoadedFor(null);
        setEditingTitle(false);
        setTitleDraft('');
        resetTextareaHeight();
      }
    } catch (caught) {
      if (activeIdRef.current === removedId) setError(caught.message);
    } finally {
      setActionLoading(false);
    }
  };

  const submitFeedback = async (message, value) => {
    if (!message.id || feedbackLoading) return;
    const targetConversationId = activeIdRef.current;
    setFeedbackLoading(`${message.id}:${value}`);
    setError('');
    try {
      const updated = await chatApi.submitFeedback(message.id, { value });
      if (activeIdRef.current === targetConversationId) {
        setMessages(items => items.map(item => item.id === updated.id ? { ...item, ...updated } : item));
      }
    } catch (caught) {
      if (activeIdRef.current === targetConversationId) setError(caught.message);
    } finally {
      setFeedbackLoading(null);
    }
  };

  const recoverNewConversationFailure = async (failedId, failedQuestion, sendError) => {
    const historySeq = ++messagesSeqRef.current;
    let history;
    try {
      history = asItems(await chatApi.listMessages(failedId));
    } catch {
      if (historySeq === messagesSeqRef.current && activeIdRef.current === failedId) {
        setMessages(items => items.slice(0, -1));
        setError('发送失败，历史待确认');
      }
      return;
    }

    if (history.length > 0) {
      if (historySeq === messagesSeqRef.current && activeIdRef.current === failedId) {
        setMessages(history);
        setMessagesLoadedFor(failedId);
        setError(sendError.message);
      }
      return;
    }

    try {
      await chatApi.deleteConversation(failedId);
      setConversations(items => items.filter(item => item.id !== failedId));
      if (activeIdRef.current === failedId) {
        messagesSeqRef.current += 1;
        setActive(null);
        activeIdRef.current = null;
        setDraft(true);
        setMessages([]);
        setMessagesLoadedFor(null);
        setInput(failedQuestion);
        setEditingTitle(false);
        setTitleDraft('');
        setError(sendError.message);
        window.requestAnimationFrame(() => {
          resizeTextarea(textareaRef.current);
          textareaRef.current?.focus();
        });
      }
    } catch {
      if (activeIdRef.current === failedId) {
        setMessages([]);
        setMessagesLoadedFor(failedId);
        setError('空会话清理失败，请手动删除');
      }
    }
  };

  const send = async event => {
    event.preventDefault();
    const text = input.trim();
    if (!text || actionLoading || sendInFlightRef.current) return;
    sendInFlightRef.current = true;
    setLoading(true);
    setError('');

    let conversation = active;
    let createdForSend = false;
    let optimisticMessagesAdded = false;
    try {
      if (!conversation) {
        const normalized = text.replace(/\s+/gu, ' ').trim();
        const title = Array.from(normalized).slice(0, 24).join('');
        conversation = await chatApi.createConversation({ title });
        createdForSend = true;
        messagesSeqRef.current += 1;
        setConversations(items => [conversation, ...items.filter(item => item.id !== conversation.id)]);
        setActive(conversation);
        activeIdRef.current = conversation.id;
        setDraft(false);
        setMessagesLoadedFor(null);
      }

      const requestId = crypto.randomUUID();
      setInput('');
      resetTextareaHeight();
      const userMessage = { role: 'user', content: text, createdAt: new Date().toISOString() };
      setMessages(items => [...items, userMessage, { role: 'assistant', content: '', pending: true }]);
      optimisticMessagesAdded = true;

      const data = await chatApi.sendMessage({ question: text, conversationId: conversation.id }, requestId);
      const historySeq = ++messagesSeqRef.current;
      try {
        const history = asItems(await chatApi.listMessages(conversation.id));
        if (historySeq === messagesSeqRef.current && activeIdRef.current === conversation.id) {
          if (history.length > 0) {
            setMessages(history);
            setMessagesLoadedFor(conversation.id);
          } else {
            setMessages(items => items.map((message, index) => index === items.length - 1
              ? { ...message, content: data.answer, pending: false, sources: data.sources || [], runId: data.runId, traceId: data.traceId }
              : message));
          }
        }
      } catch (historyError) {
        if (historySeq === messagesSeqRef.current && activeIdRef.current === conversation.id) {
          setMessages(items => items.map((message, index) => index === items.length - 1
            ? { ...message, content: data.answer, pending: false, sources: data.sources || [], runId: data.runId, traceId: data.traceId }
            : message));
          setError(`消息已发送，但历史刷新失败：${historyError.message}`);
        }
      }
    } catch (caught) {
      if (!optimisticMessagesAdded) {
        setError(caught.message);
      } else if (createdForSend) {
        await recoverNewConversationFailure(conversation.id, text, caught);
      } else if (activeIdRef.current === conversation.id) {
        setMessages(items => items.slice(0, -1));
        setError(caught.message);
      }
    } finally {
      sendInFlightRef.current = false;
      setLoading(false);
    }
  };

  return (
    <div className="chat-page">
      <aside className="conversation-panel">
        <div className="panel-heading"><span className="nav-section-label">你的工作空间</span><Icon name="chat" size={16} /></div>
        <button className="new-conversation" onClick={startNewDraft} disabled={busy} aria-label="＋ 开启新对话"><Icon name="plus" size={18} />开启新对话<Icon name="edit" size={15} /></button>
        <div className="history-heading"><span>最近对话</span><span>{conversations.length}</span></div>
        <div className="conversation-list">
          {draft && <button key="__draft__" className="conversation-item active draft" onClick={startNewDraft} disabled={busy}><Icon name="chat" size={16} /><span>新对话</span><span className="draft-indicator" /></button>}
          {conversations.map(item => <button key={item.id} className={`conversation-item ${active?.id === item.id ? 'active' : ''}`} onClick={() => selectConversation(item)} disabled={busy}><Icon name="chat" size={16} /><span>{item.title || '未命名对话'}</span><small>{fmt(item.updatedAt || item.createdAt)}</small></button>)}
          {conversations.length === 0 && <div className="history-empty"><Icon name="clock" size={23} /><p>灵感，从这里留存。</p><small>发送第一条消息后，对话将保存在这里。</small></div>}
        </div>
        <div className="conversation-note"><Icon name="book" size={19} /><p>从文档出发<br /><strong>让答案有所依据。</strong></p></div>
      </aside>
      <section className={`chat-panel ${messages.length === 0 ? 'is-empty' : ''}`}>
        <div className="chat-heading">
          <div className="chat-heading-main">{editingTitle ? <form className="title-editor" onSubmit={saveRename}><input value={titleDraft} onChange={event => setTitleDraft(event.target.value)} maxLength={200} autoFocus aria-label="会话标题" /><button className="primary-button" type="submit" disabled={busy || !titleDraft.trim()}>保存</button><button className="ghost-button" type="button" onClick={cancelRename} disabled={actionLoading}>取消</button></form> : active && <h2>{active.title || '未命名对话'}</h2>}</div>
          <div className="chat-heading-actions">{active && !editingTitle && <><button className="ghost-button" onClick={beginRename} disabled={busy} aria-label="重命名"><Icon name="edit" size={15} /><span>重命名</span></button><button className="danger-button" onClick={removeConversation} disabled={busy} aria-label="删除"><Icon name="trash" size={15} /><span>删除</span></button></>}<span className="assistant-label"><Icon name="spark" size={14} />DocTrail</span></div>
        </div>
        <div className="message-list" ref={messageListRef} aria-live="polite" aria-busy={loading}>
          {messages.length ? messages.map((message, index) => <article key={`${message.id || index}`} className={`message ${message.role === 'user' ? 'from-user' : 'from-agent'}`}>
            <div className="avatar"><Icon name={message.role === 'user' ? 'user' : 'spark'} size={18} /></div>
            <div className="message-content"><span className="message-author">{message.role === 'user' ? '你' : 'DocTrail'}</span><div className="message-bubble">{message.content || (message.pending ? <Loading label="正在思考" /> : '')}</div>
              <SourceList sources={message.sources} />
              <div className="message-meta"><time>{fmt(message.createdAt)}</time>{message.role === 'assistant' && message.id && !message.pending && <div className="message-actions" aria-label="消息反馈"><button className={`feedback-button ${message.feedback === 'up' ? 'active' : ''}`} onClick={() => submitFeedback(message, 'up')} disabled={Boolean(feedbackLoading)} aria-label="赞同" aria-pressed={message.feedback === 'up'}><Icon name="thumbUp" size={15} /></button><button className={`feedback-button ${message.feedback === 'down' ? 'active' : ''}`} onClick={() => submitFeedback(message, 'down')} disabled={Boolean(feedbackLoading)} aria-label="不赞同" aria-pressed={message.feedback === 'down'}><Icon name="thumbDown" size={15} /></button>{feedbackLoading?.startsWith(`${message.id}:`) && <span className="feedback-saving">保存中...</span>}</div>}</div>
            </div>
          </article>) : <div className="chat-empty"><div className="welcome-symbol"><Icon name="spark" size={42} /></div><span className="eyebrow">A SPACE FOR YOUR CURIOSITY</span><h1>你好，今天想探索什么？</h1><p>让散落的知识，成为清晰的答案。</p></div>}
        </div>
        <div className="composer-dock">
          {error && <div className="error-banner inline-error" role="alert">{error}</div>}
          <form className="composer" onSubmit={send}><textarea ref={textareaRef} value={input} onChange={event => { setInput(event.target.value); resizeTextarea(event.currentTarget); }} placeholder="输入你的问题..." aria-label="输入你的问题" rows="2" disabled={loading} onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); send(event); } }} /><div className="composer-toolbar"><span><Icon name="book" size={15} />知识库问答</span><button className="primary-button send-button" aria-label={loading ? '生成中...' : '发送'} disabled={busy || !input.trim()}><Icon name="arrowUp" size={20} /><span>{loading ? '生成中...' : '发送'}</span></button></div></form>
          <p className="composer-footnote">Enter 发送 · Shift + Enter 换行</p>
        </div>
      </section>
    </div>
  );
}
