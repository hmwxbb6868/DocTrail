import { test, expect } from '@playwright/test';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repoDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const fixturePath = path.join(repoDir, 'frontend', 'e2e', 'fixtures', 'e2e-knowledge.md');
const adminUsername = process.env.DOCTRAIL_E2E_ADMIN_USERNAME;
const adminPassword = process.env.DOCTRAIL_E2E_ADMIN_PASSWORD;
const userUsername = process.env.DOCTRAIL_E2E_USER_USERNAME;
const userPassword = process.env.DOCTRAIL_E2E_USER_PASSWORD;

test('登录、上传解析、问答引用和历史刷新完整闭环', async ({ browser }) => {
  for (const [name, value] of Object.entries({ adminUsername, adminPassword, userUsername, userPassword })) {
    expect(value, `${name} must be set before running the E2E test`).toBeTruthy();
  }

  const adminContext = await browser.newContext();
  const adminPage = await adminContext.newPage();
  await adminPage.goto('/admin/login');
  await adminPage.getByLabel('用户名').fill(adminUsername);
  await adminPage.getByLabel('密码').fill(adminPassword);
  await adminPage.getByRole('button', { name: '进入控制台' }).click();
  await expect(adminPage).toHaveURL(/\/admin\/dashboard/);

  await adminPage.goto('/admin/knowledge');
  await adminPage.locator('input[type="file"]').setInputFiles(fixturePath);
  const documentRow = adminPage.locator('tbody tr').filter({ hasText: 'e2e-knowledge.md' });
  await expect(documentRow).toContainText('已完成', { timeout: 180_000 });
  await adminContext.close();

  const userContext = await browser.newContext();
  const userPage = await userContext.newPage();
  await userPage.goto('/login');
  await userPage.getByLabel('用户名').fill(userUsername);
  await userPage.getByLabel('密码').fill(userPassword);
  await userPage.getByRole('button', { name: '登录' }).click();
  await expect(userPage).toHaveURL(/\/chat/);

  await userPage.getByRole('button', { name: '＋ 开启新对话' }).click();
  const question = 'E2E 验收文档中的验收短语是什么？';
  await userPage.getByPlaceholder('输入你的问题...').fill(question);
  await userPage.getByRole('button', { name: '发送' }).click();

  const assistantMessage = userPage.locator('article.from-agent').last();
  await expect(assistantMessage.locator('.message-bubble')).not.toContainText('正在思考', { timeout: 180_000 });
  await expect(assistantMessage.locator('.message-bubble')).not.toBeEmpty();
  await expect(assistantMessage.locator('.source-list')).toContainText('e2e-knowledge.md');

  await userPage.reload();
  await userPage.locator('.conversation-item').filter({ hasText: question }).first().click();
  await expect(userPage.locator('article.from-user').filter({ hasText: question })).toBeVisible();
  const reloadedAssistantMessage = userPage.locator('article.from-agent').last();
  await expect(reloadedAssistantMessage.locator('.message-bubble')).not.toBeEmpty();
  await expect(reloadedAssistantMessage.locator('.source-list')).toContainText('e2e-knowledge.md');
  await userContext.close();
});
