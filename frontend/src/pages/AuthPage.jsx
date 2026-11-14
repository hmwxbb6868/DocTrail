import { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { authApi } from '../api/auth';
import { adminApi } from '../api/admin';
import Icon from '../components/Icon';

export default function AuthPage({ register = false, admin = false }) {
  const navigate = useNavigate(); const location = useLocation();
  const [form, setForm] = useState({ username: '', password: '', confirmPassword: '', captchaCode: '' });
  const [captchaId, setCaptchaId] = useState(''); const [captchaExpires, setCaptchaExpires] = useState(0);
  const [error, setError] = useState(''); const [loading, setLoading] = useState(false); const [captchaLoading, setCaptchaLoading] = useState(false);
  const loadCaptcha = async () => { if (!register || admin) return; setCaptchaLoading(true); try { const data = await authApi.captcha(); setCaptchaId(data?.captchaId || ''); setCaptchaExpires(data?.expiresInSeconds || 0); setForm(current => ({ ...current, captchaCode: '' })); } catch (e) { setError(e.message); } finally { setCaptchaLoading(false); } };
  useEffect(() => { loadCaptcha(); }, [register, admin]);
  const submit = async (event) => { event.preventDefault(); setError(''); if (register && form.password !== form.confirmPassword) return setError('两次输入的密码不一致'); setLoading(true); try { if (admin) await adminApi.login({ username: form.username, password: form.password }); else if (register) await authApi.register({ username: form.username, password: form.password, captchaId, captchaCode: form.captchaCode }); else await authApi.login({ username: form.username, password: form.password }); navigate(admin ? '/admin/dashboard' : location.state?.from || '/chat', { replace: true }); } catch (e) { setError(e.message); if (register) await loadCaptcha(); } finally { setLoading(false); } };
  return <div className={`auth-page ${admin ? 'auth-admin' : ''}`}>
    <aside className="auth-story">
      <Link className="brand" to="/login"><span className="brand-mark"><Icon name="spark" size={25} /></span><span>DocTrail<span className="brand-caption">YOUR KNOWLEDGE, CONNECTED</span></span></Link>
      <div className="auth-story-content"><span className="eyebrow">A LITTLE CLARITY, EVERY DAY</span><h2>让每一份知识，<br />都有新的可能。</h2><p>连接文档与灵感，<br />在一次次对话中，找到清晰的答案。</p>
        <div className="auth-illustration" aria-hidden="true"><div className="orbit orbit-one" /><div className="orbit orbit-two" /><div className="illustration-core"><Icon name="spark" size={46} /></div><span className="orbit-card orbit-document"><Icon name="file" size={24} /><span>知识沉淀</span></span><span className="orbit-card orbit-answer"><Icon name="chat" size={24} /><span>灵感连接</span></span><span className="orbit-dot" /></div>
      </div>
      <div className="auth-story-footer"><span>KNOWLEDGE INTO POSSIBILITY</span><span>01 — ∞</span></div>
    </aside>
    <main className="auth-main"><div className="auth-topnote"><Icon name={admin ? 'shield' : 'book'} size={16} />{admin ? '管理工作区' : '你的专属知识空间'}</div>
      <div className="auth-panel">
        <span className="auth-greeting"><Icon name={admin ? 'shield' : 'spark'} size={25} /></span>
        <span className="eyebrow">{admin ? 'ADMIN CONSOLE' : register ? 'GET STARTED' : 'WELCOME BACK'}</span>
        <h1>{admin ? '管理端登录' : register ? '创建账号' : '欢迎回来'}</h1>
        <p className="muted">{admin ? '从这里，掌握知识与 Agent 的每一步。' : register ? '开启属于你的知识探索之旅。' : '带着好奇出发，让知识为你解答。'}</p>
        <form onSubmit={submit}>
          <label>用户名<input required placeholder="请输入用户名" value={form.username} onChange={e => setForm({ ...form, username: e.target.value })} autoComplete="username" /></label>
          <label>密码<input required type="password" placeholder="请输入密码" minLength="6" value={form.password} onChange={e => setForm({ ...form, password: e.target.value })} autoComplete={register ? 'new-password' : 'current-password'} /></label>
          {register && <label>确认密码<input required type="password" placeholder="再次输入密码" minLength="6" value={form.confirmPassword} onChange={e => setForm({ ...form, confirmPassword: e.target.value })} autoComplete="new-password" /></label>}
          {register && <><label>验证码<input required placeholder="请输入验证码" inputMode="numeric" pattern="[0-9]{4,8}" value={form.captchaCode} onChange={e => setForm({ ...form, captchaCode: e.target.value })} autoComplete="one-time-code" /></label><div className="captcha-note">验证码已输出到 Java 控制台，有效期约 {captchaExpires} 秒。{captchaLoading && '正在刷新验证码...'}</div><button type="button" className="ghost-button" onClick={loadCaptcha} disabled={captchaLoading}><Icon name="refresh" size={15} />重新获取验证码</button></>}
          {error && <div className="error-banner" role="alert">{error}</div>}
          <button className="primary-button wide" disabled={loading || captchaLoading || (register && !captchaId)}><span>{loading ? '处理中...' : admin ? '进入控制台' : register ? '注册' : '登录'}</span><Icon name="arrow" size={18} /></button>
        </form>
        {!admin && <p className="auth-switch">{register ? '已有账号？' : '还没有账号？'} <Link to={register ? '/login' : '/register'}>{register ? '返回登录' : '立即注册'}</Link></p>}
        <div className="auth-secondary-link"><Link to={admin ? '/login' : '/admin/login'}><Icon name={admin ? 'arrow' : 'shield'} size={14} />{admin ? '返回用户登录' : '管理员入口'}</Link></div>
      </div>
      <footer className="auth-footer">DocTrail <span>·</span> 让知识触手可及</footer>
    </main>
  </div>;
}
