import { test, expect } from '@playwright/test';

// UI regression only. Every API is mocked; unexpected routes fail closed.
const createdAt = '2026-11-05T02:30:00Z';
const runId = '24d41540-9908-4be0-9da7-3a72f598bc94';
const token = role => `ui.${Buffer.from(JSON.stringify({ role })).toString('base64url')}.unsigned`;
const success = data => ({ code: 1, msg: 'success', data });

async function mockWorkspace(page, { admin = false, empty = false, error = false, dashboardStats } = {}) {
  const unexpected = [];
  const writes = [];
  let conversations = empty ? [] : [{ id: 1, title: '从知识中发现新的可能', createdAt, updatedAt: createdAt }];
  let messages = [
    { id: 10, role: 'user', content: '如何把文档整理成可复用的知识？', createdAt },
    {
      id: 11, role: 'assistant', content: '先明确主题，再整理结构与来源。让每一份知识都能被发现、理解和复用。', createdAt,
      sources: [
        { docId: 1, docName: '知识管理实践指南.pdf', chunkIndex: 0, score: .94 },
        { docId: 1, docName: '知识管理实践指南.pdf', chunkIndex: 1, score: .88 },
        { docId: 2, docName: '知识管理实践指南.pdf', chunkIndex: 0, score: .81 },
      ],
    },
  ];
  let documents = empty ? [] : [
    { id: 1, name: '知识管理实践指南.pdf', status: 'COMPLETED', createdAt, updatedAt: createdAt },
    { id: 2, name: '产品使用手册.docx', status: 'COMPLETED', createdAt, updatedAt: createdAt },
    { id: 3, name: '团队协作与常见问题.md', status: 'FAILED', failureReason: '示例：未提取到可用正文，请检查文档内容。', createdAt, updatedAt: createdAt },
  ];
  await page.route('**/api/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();
    const respond = data => route.fulfill({ json: success(data) });
    if (method !== 'GET' && path !== '/api/auth/refresh') writes.push({ path, method, body: request.postData(), requestId: request.headers()['x-request-id'] });
    if (path === '/api/auth/refresh') return respond({ accessToken: token(admin ? 'ADMIN' : 'USER') });
    if (path === '/api/auth/captcha') return respond({ captchaId: 'ui-captcha', expiresInSeconds: 60 });
    if (path === '/api/auth/login' || path === '/api/auth/register') return respond({ accessToken: token('USER') });
    if (path === '/api/admin/auth/login') return respond({ accessToken: token('ADMIN') });
    if (path === '/api/auth/logout') return respond(null);
    if (error) return route.fulfill({ status: 503, json: { code: 3002, msg: '暂时无法获取数据，请稍后重试。', data: null } });
    if (path === '/api/admin/dashboard') return respond(dashboardStats ?? { documentCount: 24, questionCount: 128, runCount: 128, successRate: 98.4, finishedRunCount: 125, mock: false });
    if (path === '/api/chat/conversations') {
      if (method === 'POST') { const item = { id: 2, title: request.postDataJSON().title, createdAt, updatedAt: createdAt }; conversations = [item, ...conversations]; return respond(item); }
      if (method === 'GET') return respond(conversations);
    }
    if (path === '/api/chat/conversations/1' && method === 'PUT') { conversations[0] = { ...conversations[0], ...request.postDataJSON() }; return respond(conversations[0]); }
    if (path === '/api/chat/conversations/1' && method === 'DELETE') { conversations = []; return respond(null); }
    if (path === '/api/chat/messages') {
      if (method === 'GET') return respond(url.searchParams.get('conversationId') === '2' && !writes.some(w => w.path === path) ? [] : messages);
      if (method === 'POST') { messages = [{ id: 12, role: 'user', content: request.postDataJSON().question, createdAt }, { id: 13, role: 'assistant', content: '这是前端模拟回答，不会调用真实模型。', sources: [], createdAt }]; return respond({ answer: messages[1].content, sources: [] }); }
    }
    if (path === '/api/chat/messages/11/feedback' && method === 'PUT') { messages[1] = { ...messages[1], feedback: request.postDataJSON().value }; return respond(messages[1]); }
    if (path === '/api/knowledge/documents' && method === 'GET') return respond(documents);
    if (path === '/api/admin/knowledge/documents') {
      if (method === 'POST') { const item = { id: 4, name: 'ui-upload.md', status: 'COMPLETED', createdAt, updatedAt: createdAt }; documents = [...documents, item]; return respond(item); }
      if (method === 'GET') return respond(documents);
    }
    if (path === '/api/admin/knowledge/documents/4' && method === 'DELETE') { documents = documents.filter(doc => doc.id !== 4); return respond(null); }
    if (path === '/api/admin/agent-runs') return respond({ items: empty ? [] : [{ runId, taskType: 'knowledge_qa', status: 'COMPLETED', startedAt: createdAt }, { runId: '36f91540-9908-4be0-9da7-3a72f598bc94', taskType: 'chitchat', status: 'FAILED', startedAt: createdAt }] });
    if (path === `/api/admin/agent-runs/${runId}`) return respond({ runId, traceId: '9fda1540-9908-4be0-9da7-3a72f598bc94', requestId: '8dda1540-9908-4be0-9da7-3a72f598bc94', taskType: 'knowledge_qa', status: 'COMPLETED', startedAt: createdAt, finishedAt: createdAt, steps: [], toolCalls: [] });
    unexpected.push(`${method} ${path}`);
    return route.fulfill({ status: 403, json: { code: 1003, msg: 'Unexpected mock route; no real API permitted', data: null } });
  });
  return { unexpected, writes };
}

for (const viewport of [{ width: 1440, height: 960 }, { width: 390, height: 844 }]) {
  for (const path of ['/login', '/register', '/admin/login', '/chat', '/knowledge', '/admin/dashboard', '/admin/knowledge', '/admin/agent-runs']) {
    test(`design ${viewport.width}px ${path}`, async ({ page }, testInfo) => {
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.setViewportSize(viewport);
      const mock = await mockWorkspace(page, { admin: path.startsWith('/admin/') });
      await page.goto(path);
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      await expect(page.locator('.loading')).toHaveCount(0);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      if (path === '/chat') await expect(page.getByRole('button', { name: '发送', exact: true })).toBeVisible();
      if (path === '/admin/dashboard') await expect(page.locator('.metric-card').first().locator('strong')).toHaveText('24');
      await page.screenshot({ path: testInfo.outputPath('page.png'), fullPage: true });
      expect(errors).toEqual([]);
      expect(mock.unexpected).toEqual([]);
      expect(mock.writes).toEqual([]);
    });
  }
}

for (const { finishedRunCount, expected } of [{ finishedRunCount: 0, expected: '—' }, { finishedRunCount: 1, expected: '0%' }]) {
  test(`dashboard displays ${expected} with ${finishedRunCount} finished runs`, async ({ page }) => {
    await mockWorkspace(page, {
      admin: true,
      dashboardStats: { documentCount: 0, questionCount: finishedRunCount, runCount: 1, successRate: 0, finishedRunCount, mock: false },
    });
    await page.goto('/admin/dashboard');
    const successCard = page.locator('.metric-card').filter({ has: page.getByText('成功率', { exact: true }) });
    await expect(successCard.locator('strong')).toHaveText(expected);
  });
}

test('history, citations, feedback, rename and confirmed deletion retain their requests', async ({ page }) => {
  const mock = await mockWorkspace(page);
  await page.goto('/chat');
  await page.getByRole('button', { name: /从知识中发现新的可能/ }).click();
  await expect(page.locator('.source-list')).toContainText('知识管理实践指南.pdf');
  await expect(page.locator('.source-list > span')).toHaveText([
    '来源：知识管理实践指南.pdf · 0.94',
    '来源：知识管理实践指南.pdf · 0.81',
  ]);
  await page.getByRole('button', { name: '赞同', exact: true }).click();
  await expect(page.getByRole('button', { name: '赞同', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await page.getByRole('button', { name: '重命名' }).click();
  await page.getByLabel('会话标题').fill('整理后的知识笔记');
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await expect(page.getByRole('heading', { name: '整理后的知识笔记' })).toBeVisible();
  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: '删除', exact: true }).click();
  await expect(page.getByRole('heading', { name: '你好，今天想探索什么？' })).toBeVisible();
  expect(mock.writes.map(({ method, path }) => `${method} ${path}`)).toEqual(['PUT /api/chat/messages/11/feedback', 'PUT /api/chat/conversations/1', 'DELETE /api/chat/conversations/1']);
  expect(mock.unexpected).toEqual([]);
});

test('send keeps the existing payload and request-id; source history still comes from API', async ({ page }) => {
  const mock = await mockWorkspace(page, { empty: true });
  await page.goto('/chat');
  await page.getByRole('button', { name: '＋ 开启新对话', exact: true }).click();
  await expect(page.getByPlaceholder('输入你的问题...')).toBeVisible();
  await page.getByLabel('输入你的问题').fill('这是一个前端模拟问题');
  await page.getByRole('button', { name: '发送', exact: true }).click();
  await expect(page.locator('.message-bubble').last()).toHaveText('这是前端模拟回答，不会调用真实模型。');
  const send = mock.writes.find(write => write.path === '/api/chat/messages');
  expect(JSON.parse(send.body)).toEqual({ question: '这是一个前端模拟问题', conversationId: 2 });
  expect(send.requestId).toMatch(/^[0-9a-f-]{36}$/);
  expect(mock.unexpected).toEqual([]);
});

test('administrator upload and delete keep multipart and existing paths', async ({ page }) => {
  const mock = await mockWorkspace(page, { admin: true });
  await page.goto('/admin/knowledge');
  await page.locator('input[type=file]').setInputFiles({ name: 'ui-upload.md', mimeType: 'text/markdown', buffer: Buffer.from('# Frontend mock document') });
  const row = page.getByRole('row').filter({ hasText: 'ui-upload.md' });
  await expect(row).toBeVisible();
  page.once('dialog', dialog => dialog.accept());
  await row.getByRole('button', { name: '删除', exact: true }).click();
  await expect(row).toHaveCount(0);
  expect(mock.writes.map(({ method, path }) => `${method} ${path}`)).toEqual(['POST /api/admin/knowledge/documents', 'DELETE /api/admin/knowledge/documents/4']);
  expect(mock.writes[0].body).toContain('name="file"; filename="ui-upload.md"');
  expect(mock.unexpected).toEqual([]);
});

test('user knowledge is read-only and mobile administrator navigation works', async ({ page }) => {
  let mock = await mockWorkspace(page);
  await page.goto('/knowledge');
  await expect(page.getByRole('button', { name: '上传文档' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '删除', exact: true })).toHaveCount(0);
  expect(mock.writes).toEqual([]);
  await page.unroute('**/api/**');
  mock = await mockWorkspace(page, { admin: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/admin/dashboard');
  await page.getByRole('button', { name: '打开导航' }).click();
  await page.getByRole('navigation', { name: '管理导航' }).getByRole('link', { name: 'Agent 运行记录' }).click();
  await expect(page.getByRole('heading', { name: 'Agent 运行记录', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '打开导航' })).toBeVisible();
  expect(mock.unexpected).toEqual([]);
});

test('run details retain complete data and close with Escape', async ({ page }, testInfo) => {
  const mock = await mockWorkspace(page, { admin: true });
  await page.goto('/admin/agent-runs');
  await page.getByRole('button', { name: '查看详情' }).first().click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.locator('.detail-fields')).toContainText(runId);
  await page.getByText('完整执行数据', { exact: false }).click();
  await expect(page.locator('.raw-details pre')).toContainText('requestId');
  await page.screenshot({ path: testInfo.outputPath('run-detail.png'), fullPage: true });
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(mock.unexpected).toEqual([]);
});

test('long history and multiline input keep the composer visible on a narrow screen', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 640 });
  const mock = await mockWorkspace(page);
  await page.route('**/api/chat/messages?**', route => route.fulfill({ json: success([
    { id: 10, role: 'user', content: '请整理完整的文档内容。', createdAt },
    { id: 11, role: 'assistant', content: '这是用于验证滚动容器的长回答。\n'.repeat(80), createdAt, sources: [{ docId: 1, docName: 'long-document-name-'.repeat(20) + '.pdf', chunkIndex: 0, score: .9 }] },
  ]) }));
  await page.goto('/chat');
  await page.getByRole('button', { name: /从知识中发现新的可能/ }).click();
  await expect(page.locator('.source-list')).toContainText('long-document-name');
  const input = page.getByLabel('输入你的问题');
  await input.fill('多行输入，检查编辑器高度。\n'.repeat(20));
  const editor = await input.boundingBox();
  const sendButton = await page.getByRole('button', { name: '发送', exact: true }).boundingBox();
  expect(editor.height).toBeLessThanOrEqual(140);
  expect(sendButton.y + sendButton.height).toBeLessThan(640);
  expect(await page.locator('.message-list').evaluate(element => element.scrollHeight > element.clientHeight)).toBe(true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(mock.unexpected).toEqual([]);
  expect(mock.writes).toEqual([]);
});

test('empty and error states remain readable', async ({ page }) => {
  await mockWorkspace(page, { admin: true, empty: true });
  await page.goto('/admin/knowledge');
  await expect(page.getByRole('heading', { name: '让知识在这里汇集' })).toBeVisible();
  await page.unroute('**/api/**');
  const mock = await mockWorkspace(page, { admin: true, error: true });
  await page.goto('/admin/knowledge');
  await expect(page.getByRole('alert')).toContainText('暂时无法获取数据');
  await expect(page.getByRole('heading', { name: '暂时无法获取文档' })).toBeVisible();
  expect(mock.unexpected).toEqual([]);
});
