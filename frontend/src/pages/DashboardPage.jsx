import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { adminApi } from '../api/admin';
import Loading from '../components/Loading';
import Icon from '../components/Icon';

export default function DashboardPage() {
  const [stats, setStats] = useState(null);
  const [error, setError] = useState('');
  useEffect(() => { adminApi.stats().then(setStats).catch(e => setError(e.message)); }, []);
  const cards = [
    { label: '知识文档', value: stats?.documentCount ?? '-', hint: '当前可用于问答', icon: 'book', tone: 'blue' },
    { label: '问答次数', value: stats?.questionCount ?? '-', hint: '已结束的问答请求', icon: 'chat', tone: 'peach' },
    { label: 'Agent 运行', value: stats?.runCount ?? '-', hint: '可追踪的执行记录', icon: 'activity', tone: 'purple' },
    { label: '成功率', value: stats?.finishedRunCount === 0 || stats?.successRate == null ? '—' : `${stats.successRate}%`, hint: '已结束运行的累计成功率', icon: 'check', tone: 'green' },
  ];
  return <div className="page-stack dashboard-page">
    <div className="page-heading"><div><span className="eyebrow">WORKSPACE OVERVIEW</span><h1>仪表盘</h1></div><span className="page-label"><Icon name="grid" size={15} />工作区概览</span></div>
    {error && <div className="error-banner" role="alert">{error}</div>}
    {!stats && !error ? <Loading /> : <>
      <div className="metric-grid">{cards.map(card => <section className={`metric-card tone-${card.tone}`} key={card.label}><div className="metric-top"><span>{card.label}</span><span className="metric-icon"><Icon name={card.icon} size={19} /></span></div><strong>{card.value}</strong><small>{card.hint}</small></section>)}</div>
      <section className="workspace-feature"><div><span className="eyebrow">BUILT FOR CLARITY</span><h2>从知识到答案，<br />每一步都清晰。</h2><Link to="/admin/knowledge" className="feature-link">管理知识库<Icon name="arrow" size={17} /></Link></div><div className="feature-diagram" aria-label="文档、知识库、回答的知识流转"><div className="diagram-node"><Icon name="file" size={25} /><span>文档</span></div><span className="diagram-line" /><div className="diagram-node diagram-center"><Icon name="spark" size={32} /><span>知识连接</span></div><span className="diagram-line" /><div className="diagram-node"><Icon name="chat" size={25} /><span>答案</span></div></div></section>
      <div className="section-heading"><h2>工作区入口</h2></div>
      <div className="quick-links"><Link to="/admin/knowledge" className="quick-link"><span className="quick-icon"><Icon name="book" size={23} /></span><div><h3>管理知识来源</h3><p>上传文档、查看处理状态，维护可靠的知识底座。</p></div><Icon name="arrow" size={19} /></Link><Link to="/admin/agent-runs" className="quick-link"><span className="quick-icon lavender"><Icon name="activity" size={23} /></span><div><h3>回看 Agent 执行</h3><p>查看运行详情、执行步骤与工具调用记录。</p></div><Icon name="arrow" size={19} /></Link></div>
      <div className="dashboard-footer"><Icon name="shield" size={15} />每一份知识有出处，每一次执行有记录。</div>
    </>}
  </div>;
}
