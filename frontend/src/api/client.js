import axios from 'axios';

let accessToken = null;
let refreshPromise = null;
const authListeners = new Set();

function tokenRole(token) {
  if (!token) return null;
  try {
    const payload = token.split('.')[1];
    if (!payload) return null;
    const normalized = payload.replace(/-/g, '+').replace(/_/g, '/');
    const claims = JSON.parse(atob(normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=')));
    return claims.role === 'USER' || claims.role === 'ADMIN' ? claims.role : null;
  } catch {
    return null;
  }
}

export function setAccessToken(value) {
  accessToken = value || null;
  const role = tokenRole(accessToken);
  if (role === 'ADMIN') {
    sessionStorage.setItem('doctrail-admin-session', 'active');
    sessionStorage.removeItem('doctrail-user-session');
  } else if (role === 'USER') {
    sessionStorage.setItem('doctrail-user-session', 'active');
    sessionStorage.removeItem('doctrail-admin-session');
  } else {
    sessionStorage.removeItem('doctrail-user-session');
    sessionStorage.removeItem('doctrail-admin-session');
  }
  authListeners.forEach((listener) => listener());
  return role;
}

export const authState = {
  get token() { return accessToken; },
  set token(value) { setAccessToken(value); },
  get role() { return tokenRole(accessToken); },
  subscribe(listener) { authListeners.add(listener); return () => authListeners.delete(listener); },
};

export const api = axios.create({ baseURL: '/api', withCredentials: true, timeout: 15000 });

api.interceptors.request.use((config) => {
  if (accessToken) config.headers.Authorization = `Bearer ${accessToken}`;
  return config;
});

api.interceptors.response.use(
  (response) => {
    const result = response.data;
    if (result && typeof result.code !== 'undefined' && result.code !== 1) {
      const error = new Error(result.msg || '请求失败');
      error.code = result.code;
      error.result = result;
      return Promise.reject(error);
    }
    return result && Object.prototype.hasOwnProperty.call(result, 'data') ? result.data : result;
  },
  async (error) => {
    const original = error.config;
    if (error.response?.status === 401 && original && !original._retry && !original.url?.endsWith('/auth/refresh')) {
      original._retry = true;
      try {
        if (!refreshPromise) refreshPromise = api.post('/auth/refresh').finally(() => { refreshPromise = null; });
        const data = await refreshPromise;
        setAccessToken(data?.accessToken);
        if (accessToken) return api(original);
      } catch { setAccessToken(null); }
    }
    const normalized = new Error(error.response?.data?.msg || error.message || '网络错误');
    normalized.status = error.response?.status;
    normalized.cause = error;
    return Promise.reject(normalized);
  },
);

export async function restoreSession() {
  try {
    const data = await api.post('/auth/refresh');
    setAccessToken(data?.accessToken);
    return data;
  } catch { setAccessToken(null); return null; }
}

export function clearSession() { setAccessToken(null); }
