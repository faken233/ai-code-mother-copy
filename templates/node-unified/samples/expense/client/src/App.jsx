import { useCallback, useEffect, useState } from 'react';
import { Navigate, Route, Routes, useNavigate } from 'react-router-dom';
import { api, clearToken, errorMessage, getToken, setToken } from './api.js';

function nowLocalInput() {
  const now = new Date();
  now.setMinutes(now.getMinutes() - now.getTimezoneOffset());
  return now.toISOString().slice(0, 16);
}

function Login({ onAuthed }) {
  const [mode, setMode] = useState('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();

  const submit = async (event) => {
    event.preventDefault();
    if (!email.trim() || !password) {
      setError('请填写邮箱和密码');
      return;
    }
    setBusy(true);
    setError('');
    const result = await api(`/auth/${mode === 'login' ? 'login' : 'register'}`, {
      method: 'POST',
      body: JSON.stringify({ email: email.trim(), password })
    });
    setBusy(false);
    if (!result.ok) {
      setError(errorMessage(result, mode === 'login' ? '登录失败' : '注册失败'));
      return;
    }
    setToken(result.body.token);
    onAuthed(result.body.user);
    navigate('/', { replace: true });
  };

  return (
    <div className="wrap narrow">
      <header>
        <h1>记账本</h1>
        <p className="sub">一体化样例 · 单端口 Node + React + MySQL</p>
      </header>

      <section className="card">
        <div className="tabs">
          <button type="button" className={mode === 'login' ? 'active' : ''} onClick={() => setMode('login')}>登录</button>
          <button type="button" className={mode === 'register' ? 'active' : ''} onClick={() => setMode('register')}>注册</button>
        </div>
        <form onSubmit={submit}>
          <div className="row">
            <input
              type="email"
              placeholder="邮箱，例如 a@demo.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
            <input
              type="password"
              placeholder="密码（至少 6 位）"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
            <button type="submit" disabled={busy}>{busy ? '处理中…' : mode === 'login' ? '登录' : '注册'}</button>
          </div>
          {error ? <p className="error">{error}</p> : null}
        </form>
        <p className="hint">首次使用请切到“注册”，用两个不同邮箱各注册一个账号，即可验证数据隔离。</p>
      </section>
    </div>
  );
}

function Home({ me, onLogout }) {
  const [items, setItems] = useState([]);
  const [total, setTotal] = useState(0);
  const [form, setForm] = useState({ title: '', amount: '', category: '餐饮', spent_at: nowLocalInput(), note: '' });
  const [hint, setHint] = useState('');
  const [probeId, setProbeId] = useState('');
  const [probe, setProbe] = useState(null);

  const load = useCallback(async () => {
    const result = await api('/expenses?sort=-spent_at&pageSize=50');
    if (result.status === 401) {
      onLogout();
      return;
    }
    setItems((result.body && result.body.items) || []);
    setTotal((result.body && result.body.total) || 0);
  }, [onLogout]);

  useEffect(() => {
    load();
  }, [load]);

  const add = async (event) => {
    event.preventDefault();
    setHint('');
    const amount = Number(form.amount);
    if (!form.title.trim()) return setHint('标题必填');
    if (!Number.isFinite(amount) || amount < 0) return setHint('金额必须是非负数字');
    if (!form.spent_at) return setHint('请选择时间');

    // 故意多传 user_id：服务端会丢弃它
    const result = await api('/expenses', {
      method: 'POST',
      body: JSON.stringify({ ...form, amount, user_id: 999999 })
    });
    if (!result.ok) return setHint(errorMessage(result, '保存失败'));
    setForm({ title: '', amount: '', category: form.category, spent_at: form.spent_at, note: '' });
    load();
  };

  const remove = async (id) => {
    await api(`/expenses/${id}`, { method: 'DELETE' });
    load();
  };

  const runProbe = async () => {
    if (!probeId) return setProbe({ status: '—', body: { error: { message: '请填写一个不属于当前账号的记录 id' } } });
    const result = await api(`/expenses/${probeId}`, {
      method: 'PUT',
      body: JSON.stringify({ title: '[越权测试] 被篡改了' })
    });
    setProbe(result);
  };

  return (
    <div className="wrap">
      <header>
        <h1>记账本</h1>
        <p className="sub">
          当前用户 id：<strong>{me.id}</strong>
          <span className="sep">·</span>
          本页与 /api 同源、同端口
          <button type="button" className="ghost small" onClick={onLogout}>退出登录</button>
        </p>
      </header>

      <section className="card">
        <h2>记一笔</h2>
        <form onSubmit={add}>
          <div className="row">
            <input placeholder="标题，如：午饭" value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />
            <input type="number" step="0.01" min="0" placeholder="金额" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} />
            <select value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value })}>
              <option>餐饮</option>
              <option>交通</option>
              <option>购物</option>
              <option>其他</option>
            </select>
          </div>
          <div className="row">
            <input type="datetime-local" value={form.spent_at} onChange={(e) => setForm({ ...form, spent_at: e.target.value })} />
            <input placeholder="备注（可选）" value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />
            <button type="submit">保存</button>
          </div>
          {hint ? <p className="error">{hint}</p> : null}
        </form>
      </section>

      <section className="card">
        <h2>我的支出 <span className="muted">共 {total} 条</span></h2>
        {items.length === 0 ? (
          <p className="empty">当前账号没有任何记录 —— 这正是“数据隔离”生效的表现。</p>
        ) : (
          <table>
            <thead>
              <tr><th>标题</th><th>金额</th><th>分类</th><th>时间</th><th /></tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.id}>
                  <td>{item.title}</td>
                  <td>¥{Number(item.amount).toFixed(2)}</td>
                  <td>{item.category}</td>
                  <td>{String(item.spent_at || '').replace('T', ' ')}</td>
                  <td className="right">
                    <button type="button" className="danger small" onClick={() => remove(item.id)}>删除</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="card check">
        <h2>越权检查（验收第 3 步）</h2>
        <div className="row">
          <input type="number" min="1" placeholder="别人的记录 id" value={probeId} onChange={(e) => setProbeId(e.target.value)} />
          <button type="button" className="ghost" onClick={runProbe}>尝试修改它</button>
        </div>
        <p className="hint">用 B 账号登录，填 A 账号某条记录的 id。预期 404 —— 不是 403，也不是 200。</p>
        {probe ? (
          <div className="result">
            PUT /api/expenses/{probeId} → HTTP <strong>{probe.status}</strong>{' '}
            {probe.status === 404 ? (
              <span className="ok">✅ 已拦下（记录不存在或不属于你）</span>
            ) : probe.status === 200 ? (
              <span className="bad">❌ 越权成功，归属校验失效</span>
            ) : (
              <span className="bad">⚠️ 非预期状态</span>
            )}
            <div className="mono muted">{JSON.stringify(probe.body)}</div>
          </div>
        ) : null}
      </section>

      <section className="card">
        <h2>这次验收请看三件事</h2>
        <ol className="hint list">
          <li>用 A 注册并添加两条记录 → 列表出现数据。</li>
          <li>退出，用 B 注册 → 列表为空（同一应用、同一端口、不同归属）。</li>
          <li>用 B 填 A 的记录 id 点“尝试修改” → 返回 404，且 A 的数据未变。</li>
        </ol>
      </section>
    </div>
  );
}

export default function App() {
  const [me, setMe] = useState(null);
  const [booted, setBooted] = useState(false);

  useEffect(() => {
    if (!getToken()) {
      setBooted(true);
      return;
    }
    api('/auth/me')
      .then((result) => {
        if (result.ok) setMe(result.body);
        else clearToken();
      })
      .finally(() => setBooted(true));
  }, []);

  const logout = () => {
    clearToken();
    setMe(null);
  };

  if (!booted) return <div className="boot">加载中…</div>;

  return (
    <Routes>
      <Route path="/login" element={me ? <Navigate to="/" replace /> : <Login onAuthed={setMe} />} />
      <Route path="/" element={me ? <Home me={me} onLogout={logout} /> : <Navigate to="/login" replace />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
