import { test, expect } from '@playwright/test';

// Frontend-only tokens. Every /api request is intercepted below; no real login,
// database operation, or paid model call is permitted by this test suite.
function frontendToken(role) {
  const payload = Buffer.from(JSON.stringify({ role })).toString('base64url');
  return `frontend-test.${payload}.unsigned`;
}

function success(data) {
  return { code: 1, msg: 'success', data };
}

test('restored USER cannot mount the administrator dashboard', async ({ page }) => {
  const unexpected = [];
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/auth/refresh') {
      await route.fulfill({ json: success({ accessToken: frontendToken('USER'), mock: true }) });
      return;
    }
    unexpected.push(path);
    await route.fulfill({ status: 403, json: { code: 1003, msg: 'forbidden', data: null } });
  });

  await page.goto('/admin/dashboard');
  await expect(page).toHaveURL(/\/admin\/login$/);
  await expect(page.getByRole('heading', { name: '管理端登录' })).toBeVisible();
  expect(unexpected).toEqual([]);
});

test('restored ADMIN can mount the administrator dashboard', async ({ page }) => {
  const unexpected = [];
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/auth/refresh') {
      await route.fulfill({ json: success({ accessToken: frontendToken('ADMIN'), mock: true }) });
      return;
    }
    if (path === '/api/admin/dashboard') {
      await route.fulfill({ json: success({ documentCount: 1, questionCount: 1, runCount: 1, successRate: 100, mock: true }) });
      return;
    }
    unexpected.push(path);
    await route.fulfill({ status: 403, json: { code: 1003, msg: 'forbidden', data: null } });
  });

  await page.goto('/admin/dashboard');
  await expect(page.getByRole('heading', { name: '仪表盘', exact: true })).toBeVisible();
  await expect(page.locator('.metric-card').first().locator('strong')).toHaveText('1');
  expect(unexpected).toEqual([]);
});

test('an ADMIN session replacing USER on reload redirects before chat APIs; USER login restores history', async ({ page }) => {
  let currentRole = 'USER';
  const chatRequests = [];
  const unexpected = [];
  const createdAt = '2026-11-04T00:00:00Z';
  await page.route('**/api/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    if (path === '/api/auth/refresh') {
      await route.fulfill({ json: success({ accessToken: frontendToken(currentRole), mock: true }) });
      return;
    }
    if (path === '/api/auth/login' && request.method() === 'POST') {
      currentRole = 'USER';
      await route.fulfill({ json: success({ accessToken: frontendToken('USER'), mock: true }) });
      return;
    }
    if (path.startsWith('/api/chat/')) {
      chatRequests.push(`${request.method()} ${path}`);
      if (currentRole === 'USER' && request.method() === 'GET') {
        if (path === '/api/chat/conversations') {
          await route.fulfill({ json: success([{ id: 1, title: '已保存的角色回归会话', createdAt, updatedAt: createdAt, mock: true }]) });
          return;
        }
        if (path === '/api/chat/messages' && url.searchParams.get('conversationId') === '1') {
          await route.fulfill({ json: success([
            { id: 1, conversationId: 1, role: 'user', content: '已有测试问题', createdAt, sources: [], mock: true },
            { id: 2, conversationId: 1, role: 'assistant', content: '已有测试回答', createdAt,
              sources: [{ docId: 1, docName: 'role-regression.md', chunkIndex: 0, score: 1 }], mock: true },
          ]) });
          return;
        }
      }
    }
    // Fail closed: even a mistaken click cannot reach a real model/API.
    unexpected.push(`${request.method()} ${path}`);
    await route.fulfill({ status: 403, json: { code: 1003, msg: 'forbidden', data: null } });
  });

  await page.goto('/chat');
  await page.getByRole('button', { name: /已保存的角色回归会话/ }).click();
  await expect(page.locator('.message-bubble').last()).toHaveText('已有测试回答');
  await expect(page.locator('.source-list')).toContainText('role-regression.md');

  // A later login in a shared cookie session changes the role recovered by refresh.
  currentRole = 'ADMIN';
  const requestsBeforeReload = chatRequests.length;
  await page.reload();
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible();
  expect(chatRequests).toHaveLength(requestsBeforeReload);
  await expect(page.locator('.chat-page')).toHaveCount(0);

  await page.getByLabel('用户名').fill('frontend-test-user');
  await page.getByLabel('密码').fill('frontend-test-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page).toHaveURL(/\/chat$/);
  await page.getByRole('button', { name: /已保存的角色回归会话/ }).click();
  await expect(page.locator('.message-bubble').last()).toHaveText('已有测试回答');
  await expect(page.locator('.source-list')).toContainText('role-regression.md');
  expect(unexpected).toEqual([]);
  expect(chatRequests.every(request => request.startsWith('GET '))).toBe(true);
});
