const { chromium } = require('playwright');

(async () => {
  let browser;
  try {
    browser = await chromium.launch({
      channel: 'msedge',
      headless: true,
      args: ['--no-sandbox', '--disable-dev-shm-usage'],
    });
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await ctx.newPage();
    await page.goto('https://q.qq.com/bot/#/home', { waitUntil: 'domcontentloaded', timeout: 30000 });
    await page.waitForTimeout(3000);
    const title = await page.title();
    const url = page.url();
    console.log('TITLE:', title);
    console.log('URL:', url);
    await page.screenshot({ path: 'test_home.png', fullPage: false });
    console.log('screenshot saved');
    await browser.close();
    console.log('DONE');
  } catch (e) {
    console.error('ERR:', e && e.stack ? e.stack : e);
    if (browser) try { await browser.close(); } catch {}
    process.exit(2);
  }
})();
