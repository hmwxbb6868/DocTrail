import { api, clearSession, authState } from './client';

export const authApi = {
  login: async (payload) => { const data = await api.post('/auth/login', payload); authState.token = data?.accessToken || null; return data; },
  captcha: () => api.get('/auth/captcha'),
  register: async (payload) => { const data = await api.post('/auth/register', payload); authState.token = data?.accessToken || null; return data; },
  logout: async () => { try { await api.post('/auth/logout'); } finally { clearSession(); } },
};
