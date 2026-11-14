import { useCallback, useEffect, useRef, useState } from 'react';
import { knowledgeApi } from '../api/knowledge';
import { adminApi } from '../api/admin';
import Loading from '../components/Loading';
import Icon from '../components/Icon';
import './KnowledgePage.css';

const statusText = {
  PENDING: '等待处理',
  PROCESSING: '处理中',
  COMPLETED: '已完成',
  FAILED: '失败',
  DELETING: '删除中',
  DELETED: '已删除',
};
const pollingStatuses = new Set(['PENDING', 'PROCESSING', 'DELETING']);
const deletableStatuses = new Set(['COMPLETED', 'FAILED']);
const timeFormatter = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', dateStyle: 'medium', timeStyle: 'medium',
});

function displayTime(value) {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '-' : timeFormatter.format(date);
}

export default function KnowledgePage({ admin = false }) {
  const [documents, setDocuments] = useState([]);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [deletingId, setDeletingId] = useState(null);
  const [listError, setListError] = useState('');
  const [actionError, setActionError] = useState('');
  const [actionMessage, setActionMessage] = useState('');
  const input = useRef(null);
  const listController = useRef(null);
  const operationController = useRef(null);
  const requestSequence = useRef(0);
  const lifecycle = useRef(0);
  const active = useRef(false);
  const operationBusy = useRef(false);

  const loadDocuments = useCallback(async ({ initial = false, background = false } = {}) => {
    if (!active.current || (background && listController.current)) return;
    listController.current?.abort();
    const controller = new AbortController();
    const sequence = ++requestSequence.current;
    listController.current = controller;
    if (initial) setLoading(true);
    try {
      const config = { signal: controller.signal };
      const data = await (admin ? adminApi.documents(config) : knowledgeApi.list(config));
      if (!active.current || controller.signal.aborted || sequence !== requestSequence.current) return;
      if (!Array.isArray(data)) throw new Error('文档列表响应格式不正确，请刷新重试。');
      setDocuments(data);
      setListError('');
    } catch (exception) {
      if (active.current && !controller.signal.aborted && sequence === requestSequence.current) {
        setListError(exception.message || '文档列表加载失败，请刷新重试。');
      }
    } finally {
      if (listController.current === controller) listController.current = null;
      if (active.current && !controller.signal.aborted && sequence === requestSequence.current) setLoading(false);
    }
  }, [admin]);

  useEffect(() => {
    active.current = true;
    lifecycle.current += 1;
    operationBusy.current = false;
    setDocuments([]);
    setUploading(false);
    setDeletingId(null);
    setActionError('');
    setActionMessage('');
    setListError('');
    loadDocuments({ initial: true });
    return () => {
      active.current = false;
      lifecycle.current += 1;
      requestSequence.current += 1;
      listController.current?.abort();
      operationController.current?.abort();
      listController.current = null;
      operationController.current = null;
    };
  }, [loadDocuments]);

  const shouldPoll = admin && (deletingId !== null || documents.some(document => pollingStatuses.has(document.status)));
  useEffect(() => {
    if (!shouldPoll) return undefined;
    let cancelled = false;
    let timer;
    const poll = async () => {
      // 等本次查询结束再计时；手动刷新尚未结束时跳过这一轮。
      await loadDocuments({ background: true });
      if (!cancelled) timer = window.setTimeout(poll, 3000);
    };
    timer = window.setTimeout(poll, 3000);
    return () => { cancelled = true; window.clearTimeout(timer); };
  }, [shouldPoll, loadDocuments]);

  const upload = async event => {
    const picker = event.target;
    const file = picker.files?.[0];
    if (!file || !admin || operationBusy.current) return;
    const controller = new AbortController();
    const generation = lifecycle.current;
    const isCurrent = () => active.current && lifecycle.current === generation && !controller.signal.aborted;
    operationBusy.current = true;
    operationController.current = controller;
    setActionError('');
    setActionMessage('');
    setUploading(true);
    try {
      const result = await adminApi.upload(file, { signal: controller.signal });
      if (isCurrent()) {
        setActionMessage(result?.status === 'COMPLETED'
          ? '该内容已处理完成，可直接使用。'
          : '上传请求已接收，请查看列表中的实际处理状态。');
      }
    } catch (exception) {
      if (isCurrent()) setActionError(`上传请求失败：${exception.message || '请重试'}。请刷新确认实际状态。`);
    } finally {
      picker.value = '';
      if (isCurrent()) {
        await loadDocuments();
        if (isCurrent()) {
          setUploading(false);
          operationBusy.current = false;
          operationController.current = null;
        }
      }
    }
  };

  const remove = async document => {
    if (!admin || !deletableStatuses.has(document.status) || operationBusy.current) return;
    if (!window.confirm(`确定删除“${document.name}”吗？`)) return;
    const controller = new AbortController();
    const generation = lifecycle.current;
    const isCurrent = () => active.current && lifecycle.current === generation && !controller.signal.aborted;
    operationBusy.current = true;
    operationController.current = controller;
    setActionError('');
    setActionMessage('');
    setDeletingId(document.id);
    try {
      await adminApi.removeDocument(document.id, { signal: controller.signal });
      if (isCurrent()) setActionMessage('删除请求已完成。');
    } catch (exception) {
      if (isCurrent()) setActionError(`删除请求失败：${exception.message || '请重试'}。请查看文档的实际状态。`);
    } finally {
      // 超时或部分失败也重新查询，不能把仍在 DELETING 的文档直接移出列表。
      if (isCurrent()) {
        await loadDocuments();
        if (isCurrent()) {
          setDeletingId(null);
          operationBusy.current = false;
          operationController.current = null;
        }
      }
    }
  };

  return <div className="page-stack knowledge-page">
    <div className="page-heading"><div><span className="eyebrow">{admin ? 'KNOWLEDGE MANAGEMENT' : 'YOUR KNOWLEDGE LIBRARY'}</span><h1>{admin ? '知识库管理' : '知识库'}</h1></div><div className="page-actions"><button className="secondary-button" disabled={loading || uploading} onClick={() => loadDocuments({ initial: true })}><Icon name="refresh" size={16} />刷新</button>{admin && <><input ref={input} type="file" hidden accept=".pdf,.docx,.txt,.md" onChange={upload} /><button className="primary-button" disabled={uploading || deletingId !== null} onClick={() => input.current?.click()}><Icon name="plus" size={17} />{uploading ? '上传中...' : '上传文档'}</button></>}</div></div>
    {actionError && <div className="error-banner" role="alert">{actionError}</div>}{listError && <div className="error-banner" role="alert">列表刷新失败：{listError}</div>}{actionMessage && <div className="success-banner" role="status">{actionMessage}</div>}
    <section className="surface"><div className="surface-heading"><div className="section-title"><Icon name="file" size={19} /><h2>文档列表</h2><span className="count-label">{loading ? '加载中' : `${documents.length} 个文档`}</span></div>{!admin && <span className="section-aside">知识积累 · 随时查阅</span>}</div>
      {loading ? <Loading /> : documents.length ? <div className="table-wrap"><table><thead><tr><th>文档名称</th><th>处理状态</th><th>更新时间（上海）</th>{admin && <th className="action-column">操作</th>}</tr></thead><tbody>{documents.map(document => { const status = document.status; const canDelete = admin && deletableStatuses.has(status); return <tr key={document.id}><td className="document-cell"><div className="document-title"><span className={`file-symbol file-${document.name?.split('.').pop()?.toLowerCase()}`}><Icon name="file" size={21} /></span><div><strong>{document.name}</strong><small>文档 ID：{document.id}</small>{admin && document.failureReason && <small className="failure-reason">{document.failureReason}</small>}</div></div></td><td><span className={`status status-${status.toLowerCase()}`}>{statusText[status] || status}</span></td><td className="date-cell">{displayTime(document.updatedAt)}</td>{admin && <td className="action-column">{canDelete ? <button className="table-action danger-text" disabled={uploading || deletingId !== null} onClick={() => remove(document)}><Icon name="trash" size={14} />{deletingId === document.id ? '删除中...' : '删除'}</button> : status === 'DELETING' ? <span className="table-hint">删除中...</span> : null}</td>}</tr>; })}</tbody></table></div> : <div className="empty-state"><div className="empty-icon"><Icon name="book" size={27} /></div><h3>{listError ? '暂时无法获取文档' : '让知识在这里汇集'}</h3><p>{listError ? '请检查连接后点击刷新。' : admin ? '上传第一份文档，开启有据可循的知识问答。' : '管理员上传并完成处理后，文档会出现在这里。'}</p></div>}
      {admin && <div className="table-footer"><Icon name="refresh" size={14} />等待处理、处理中、删除中会自动刷新。失败文档可重新上传同一文件以重试解析。</div>}
    </section>
  </div>;
}
