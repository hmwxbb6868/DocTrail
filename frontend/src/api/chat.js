import { api } from './client';
import { parseSse } from './sse';

export const chatApi = {
  listConversations: () => api.get('/chat/conversations'),
  createConversation: (payload = {}) => api.post('/chat/conversations', payload),
  updateConversation: (conversationId, payload) => api.put(`/chat/conversations/${conversationId}`, payload),
  deleteConversation: (conversationId) => api.delete(`/chat/conversations/${conversationId}`),
  listMessages: (conversationId) => api.get(`/chat/messages`, { params: { conversationId } }),
  submitFeedback: (messageId, payload) => api.put(`/chat/messages/${messageId}/feedback`, payload),
  sendMessage: (payload, requestId) => api.post('/chat/messages', payload, { headers: { 'X-Request-Id': requestId } }),
  streamMessage: async function* (payload, requestId, signal) {
    const response = await fetch('/api/chat/stream/messages', { method: 'POST', credentials: 'include', signal, headers: { 'Content-Type': 'application/json', 'X-Request-Id': requestId }, body: JSON.stringify(payload) });
    if (!response.ok) throw new Error(`流式请求失败 (${response.status})`);
    yield* parseSse(response.body);
  },
};
