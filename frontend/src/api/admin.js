import { api, authState } from './client';

export const adminApi = {
  login: async (payload) => { const data = await api.post('/admin/auth/login', payload); authState.token = data?.accessToken || null; return data; },
  stats: () => api.get('/admin/dashboard'),
  documents: (config) => api.get('/admin/knowledge/documents', config),
  upload: (file, config) => { const form = new FormData(); form.append('file', file); return api.post('/admin/knowledge/documents', form, { timeout: 180000, ...config }); },
  removeDocument: (docId, config) => api.delete(`/admin/knowledge/documents/${docId}`, { timeout: 180000, ...config }),
  runs: (params) => api.get('/admin/agent-runs', { params }),
  run: (runId) => api.get(`/admin/agent-runs/${runId}`),
};
