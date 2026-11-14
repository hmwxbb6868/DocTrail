import { api } from './client';

export const knowledgeApi = {
  list: (config) => api.get('/knowledge/documents', config),
};
