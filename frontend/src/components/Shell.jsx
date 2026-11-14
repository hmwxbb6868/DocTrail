import { useState } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { authApi } from '../api/auth';
import Icon from './Icon';

export function UserShell() {
  const navigate = useNavigate();
  const logout = async () => { await authApi.logout(); sessionStorage.removeItem('doctrail-user-session'); navigate('/login'); };
  return <div className="app-shell">
    <header className="topbar">
      <NavLink className="brand" to="/chat"><span className="brand-mark"><Icon name="spark" size={23} /></span><span>DocTrail<span className="brand-caption">知识工作台</span></span></NavLink>
      <nav aria-label="用户导航"><NavLink to="/chat"><Icon name="chat" size={17} />对话</NavLink><NavLink to="/knowledge"><Icon name="book" size={17} />知识库</NavLink></nav>
      <div className="topbar-account"><span className="account-avatar"><Icon name="user" size={16} /></span><span className="account-label">个人工作区</span><button className="ghost-button logout-button" onClick={logout} aria-label="退出"><Icon name="logout" size={16} /><span>退出</span></button></div>
    </header>
    <main className="shell-content"><Outlet /></main>
  </div>;
}

export function AdminShell() {
  const navigate = useNavigate();
  const [menuOpen, setMenuOpen] = useState(false);
  const logout = async () => { await authApi.logout(); sessionStorage.removeItem('doctrail-admin-session'); navigate('/admin/login'); };
  return <div className={`admin-shell ${menuOpen ? 'menu-open' : ''}`}>
    <header className="mobile-admin-header"><span className="brand"><span className="brand-mark"><Icon name="spark" /></span>DocTrail</span><button className="icon-button" onClick={() => setMenuOpen(!menuOpen)} aria-label={menuOpen ? '关闭导航' : '打开导航'} aria-expanded={menuOpen}><Icon name={menuOpen ? 'close' : 'menu'} /></button></header>
    <aside className="sidebar">
      <NavLink className="brand sidebar-brand" to="/admin/dashboard"><span className="brand-mark"><Icon name="spark" size={24} /></span><span>DocTrail<span className="brand-caption">管理控制台</span></span></NavLink>
      <span className="nav-section-label">工作空间</span>
      <nav className="side-nav" aria-label="管理导航" onClick={() => setMenuOpen(false)}>
        <NavLink to="/admin/dashboard"><Icon name="grid" />仪表盘</NavLink>
        <NavLink to="/admin/knowledge"><Icon name="book" />知识库管理</NavLink>
        <NavLink to="/admin/agent-runs"><Icon name="activity" />Agent 运行记录</NavLink>
      </nav>
      <div className="sidebar-account"><span className="account-avatar"><Icon name="shield" size={18} /></span><div><strong>管理员</strong><small>管理工作区</small></div><button className="icon-button" onClick={logout} aria-label="退出管理端" title="退出管理端"><Icon name="logout" size={17} /></button></div>
    </aside>
    <main className="admin-content"><div className="admin-topline"><span>DocTrail <span className="breadcrumb-slash">/</span> 管理工作区</span><span className="workspace-label"><span />Workspace</span></div><Outlet /></main>
  </div>;
}
