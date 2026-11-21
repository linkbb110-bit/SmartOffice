// 连接隔离测试实例；Playwright 可由外部工具提供，不增加产品运行依赖。
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const baseUrl = process.argv[2];
if (!baseUrl || !process.env.SO_UI_TEST_USERNAME || !process.env.SO_UI_TEST_PASSWORD) {
  throw new Error('需要隔离实例 URL、SO_UI_TEST_USERNAME 和 SO_UI_TEST_PASSWORD');
}
(async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 }, reducedMotion: 'reduce' });
    await page.goto(baseUrl + '/login');
    await page.getByPlaceholder('用户名', {exact:true}).fill(process.env.SO_UI_TEST_USERNAME);
    await page.getByPlaceholder('密码', {exact:true}).fill(process.env.SO_UI_TEST_PASSWORD);
    await page.getByRole('button', {name:'登录',exact:true}).click();
    await page.waitForURL('**/dashboard');
    await page.reload(); await page.locator('.console-content').waitFor();
    await page.keyboard.press('Tab');
    assert.equal(await page.evaluate(()=>document.activeElement.getAttribute('aria-label')), '切换导航', '收起的侧栏不应获得焦点');
    await page.keyboard.press('Enter');
    assert.equal(await page.evaluate(()=>document.activeElement.closest('aside')!==null),true,'展开后焦点应进入侧栏');
    // 双向循环都应留在当前弹层，Escape 回到触发按钮。
    await page.keyboard.press('Shift+Tab');
    assert.equal(await page.evaluate(()=>document.activeElement.closest('aside')!==null),true);
    await page.keyboard.press('Tab');
    assert.equal(await page.evaluate(()=>document.activeElement.closest('aside')!==null),true);
    await page.keyboard.press('Escape');
    assert.equal(await page.evaluate(()=>document.activeElement.getAttribute('aria-label')),'切换导航');
    await page.keyboard.press('Enter');
    await page.locator('aside').getByRole('link',{name:'我的预约',exact:true}).click();await page.waitForURL('**/bookings');
    assert.equal(await page.locator('.console.nav-open').count(),0);
    console.log('PASS mobile keyboard focus, loop, Escape and navigation');
  }finally{await browser.close();}
})().catch(error=>{console.error(error.message);process.exitCode=1;});
