const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({
    channel: 'msedge',
    headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await ctx.newPage();

  page.on('console', m => console.log('[console]', m.type(), m.text().slice(0, 300)));
  page.on('pageerror', e => console.log('[pageerror]', e.message.slice(0, 300)));
  page.on('response', r => {
    const u = r.url();
    if (u.includes('bot') || u.includes('login') || u.includes('qr') || u.includes('pass')) {
      console.log('[resp]', r.status(), u.slice(0, 160));
    }
  });

  try {
    await page.goto('https://q.qq.com/bot/#/home', { waitUntil: 'networkidle', timeout: 30000 });
  } catch (e) { console.log('goto warn:', e.message.slice(0,200)); }
  await page.waitForTimeout(4000);

  console.log('URL:', page.url());
  const html = await page.content();
  console.log('HTML length:', html.length);
  // look for qr / iframe / canvas
  const info = await page.evaluate(() => {
    const out = {};
    out.iframes = Array.from(document.querySelectorAll('iframe')).map(f => f.src || '(no src)');
    out.canvases = document.querySelectorAll('canvas').length;
    out.imgs = Array.from(document.querySelectorAll('img')).map(i => i.src || i.alt || '(img)').slice(0, 20);
    out.bodyText = (document.body ? document.body.innerText : '').slice(0, 500);
    // find elements containing keywords
    const kw = ['扫码','二维码','登录','QR','qr'];
    const hits = [];
    document.querySelectorAll('*').forEach(el => {
      const t = (el.innerText || el.textContent || '').trim();
      if (t && kw.some(k => t.includes(k)) && t.length < 60) hits.push(t);
    });
    out.keywordHits = [...new Set(hits)].slice(0, 30);
    return out;
  });
  console.log('IFRAMES:', JSON.stringify(info.iframes));
  console.log('CANVASES:', info.canvases);
  console.log('IMGS:', JSON.stringify(info.imgs));
  console.log('BODYTEXT:', JSON.stringify(info.bodyText));
  console.log('KEYWORDHITS:', JSON.stringify(info.keywordHits));

  await page.screenshot({ path: 'diag.png', fullPage: false });
  await browser.close();
  console.log('DONE');
})();
