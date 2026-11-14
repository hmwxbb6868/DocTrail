import { useEffect, useRef, useState } from 'react';
import { adminApi } from '../api/admin';
import Loading from '../components/Loading';
import Icon from '../components/Icon';

const statusLabels = { COMPLETED: '已完成', FAILED: '失败', RUNNING: '运行中', PENDING: '待执行', SUCCEEDED: '已成功' };
const taskLabels = { knowledge_qa: '知识问答', chitchat: '日常对话' };
const displayTime = value => value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) : '—';

export default function AgentRunsPage() {
  const [runs, setRuns] = useState([]);
  const [selected, setSelected] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const dialog = useRef(null);
  useEffect(() => { adminApi.runs().then(data => setRuns(Array.isArray(data) ? data : data?.items || [])).catch(e => setError(e.message)).finally(() => setLoading(false)); }, []);
  useEffect(() => { if (selected && dialog.current && !dialog.current.open) dialog.current.showModal(); }, [selected]);
  const detail = async run => { try { setSelected(await adminApi.run(run.runId)); } catch (e) { setError(e.message); } };
  return <div className="page-stack runs-page">
    <div className="page-heading"><div><span className="eyebrow">AGENT OBSERVABILITY</span><h1>Agent 运行记录</h1></div><span className="page-label"><Icon name="activity" size={15} />执行追踪</span></div>
    {error && <div className="error-banner" role="alert">{error}</div>}
    <section className="surface"><div className="surface-heading"><div className="section-title"><Icon name="activity" size={19} /><h2>执行记录</h2><span className="count-label">{loading ? '加载中' : `本页 ${runs.length} 条`}</span></div></div>
      {loading ? <Loading /> : runs.length ? <div className="table-wrap"><table><thead><tr><th>运行 ID</th><th>任务类型</th><th>状态</th><th>开始时间（上海）</th><th className="action-column">操作</th></tr></thead><tbody>{runs.map(run => <tr key={run.runId}><td className="run-id-cell"><span className="run-id" title={run.runId}>{run.runId}</span></td><td><span className="task-label"><Icon name={run.taskType === 'chitchat' ? 'chat' : 'book'} size={15} />{taskLabels[run.taskType] || run.taskType || '—'}</span></td><td><span className={`status status-${String(run.status || '').toLowerCase()}`}>{statusLabels[run.status] || run.status || '—'}</span></td><td className="date-cell">{displayTime(run.startedAt)}</td><td className="action-column"><button className="table-action" onClick={() => detail(run)}>查看详情<Icon name="chevron" size={14} /></button></td></tr>)}</tbody></table></div> : <div className="empty-state"><div className="empty-icon lavender"><Icon name="activity" size={28} /></div><h3>暂无运行记录</h3><p>完成一次问答后，可在这里回顾完整执行过程。</p></div>}
      <div className="table-footer"><Icon name="shield" size={14} />记录实际执行过程，帮助理解、排查与复盘。</div>
    </section>
    {selected && <dialog ref={dialog} className="detail-drawer" onCancel={() => setSelected(null)} onClick={event => { if (event.target === event.currentTarget) { const rect = event.currentTarget.getBoundingClientRect(); if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) setSelected(null); } }} aria-labelledby="run-detail-title"><div className="drawer-header"><div><span className="eyebrow">EXECUTION DETAILS</span><h2 id="run-detail-title">运行详情</h2></div><button className="icon-button" onClick={() => setSelected(null)} aria-label="关闭运行详情"><Icon name="close" /></button></div><div className="drawer-body"><div className="detail-summary"><span className="quick-icon lavender"><Icon name="activity" size={24} /></span><div><h3>{taskLabels[selected.taskType] || selected.taskType || 'Agent 任务'}</h3><span className={`status status-${String(selected.status || '').toLowerCase()}`}>{statusLabels[selected.status] || selected.status || '—'}</span></div></div><dl className="detail-fields"><div><dt>运行 ID</dt><dd>{selected.runId}</dd></div><div><dt>Trace ID</dt><dd>{selected.traceId || '—'}</dd></div><div><dt>请求 ID</dt><dd>{selected.requestId || '—'}</dd></div><div><dt>开始时间</dt><dd>{displayTime(selected.startedAt)}</dd></div><div><dt>结束时间</dt><dd>{displayTime(selected.finishedAt)}</dd></div></dl>{selected.errorMessage && <div className="error-banner">{selected.errorMessage}</div>}<div className="trace-counts"><span><Icon name="activity" size={16} />{selected.steps?.length || 0} 个执行步骤</span><span><Icon name="grid" size={16} />{selected.toolCalls?.length || 0} 次工具调用</span></div><details className="raw-details"><summary>完整执行数据<span>JSON</span></summary><pre>{JSON.stringify(selected, null, 2)}</pre></details></div></dialog>}
  </div>;
}
