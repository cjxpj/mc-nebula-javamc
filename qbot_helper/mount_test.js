const { chromium } = require('playwright');
const fs = require('fs');

const CDN = 'https://qqminiapp.cdn-go.cn/qq-open-platform/19bcbf74/assets/';
const MIRROR = 'https://q.qq.com/h5/assets-retry/';

(async () => {
  const browser = await chromium.launch({
    channel: 'msedge', headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await ctx.newPage();

  let mirrored = 0;
  await page.route('**://qqminiapp.cdn-go.cn/**', async (route) => {
    const url = route.request().url();
    const mapped = url.replace(CDN, MIRROR);
    try {
      const resp = await fetch(mapped);
      const buf = Buffer.from(await resp.arrayBuffer());
      const ct = resp.headers.get('content-type') || 'application/octet-stream';
      mirrored++;
      await route.fulfill({ status: resp.status, headers: { 'content-type': ct, 'access-control-allow-origin': '*' }, body: buf });
    } catch (e) {
      await route.fulfill({ status: 502, body: 'mirror fail: ' + e.message });
    }
  });

  const log = [];
  page.on('console', m => { if (/qr|扫码|登录|login|ticket|error|fail/i.test(m.text())) log.push('[c]'+m.type()+' '+m.text().slice(0,160)); });

  try { await page.goto('https://q.qq.com/bot/#/home', { waitUntil: 'load', timeout: 30000 }); }
  catch (e) { log.push('GOTO: ' + e.message.slice(0,120)); }

  // wait for mount
  let mounted = false;
  try {
    await page.waitForFunction(() => document.querySelector('#app') && document.querySelector('#app').children.length > 0, { timeout: 20000 });
    mounted = true;
  } catch { mounted = false; }
  await page.waitForTimeout(3000);

  const info = await page.evaluate(() => {
    const app = document.querySelector('#app');
    const imgs = Array.from(document.querySelectorAll('img')).map(i => ({ src: (i.src||'').slice(0,140), w: i.naturalWidth, h: i.naturalHeight }));
    const txt = [];
    document.querySelectorAll('*').forEach(el => {
      const t = (el.innerText||'').trim();
      if (t && /扫码|二维码|扫一扫|登录|我的机器人|机器人/.test(t) && t.length<40) txt.push(t);
    });
    return { url: location.href, appChildren: app? app.children.length : -1, imgs: imgs.slice(0,20), txt: [...new Set(txt)].slice(0,20) };
  });
  log.push('MOUNTED=' + mounted + ' URL=' + info.url + ' appChildren=' + info.appChildren);
  log.push('IMGS=' + JSON.stringify(info.imgs));
  log.push('TXT=' + JSON.stringify(info.txt));
  log.push('MIRRORED=' + mirrored);

  // screenshot whole page + try QR element
  await page.screenshot({ path: 'mount_full.png' });
  let qrShot = false;
  for (const im of info.imgs) {
    if (im.w >= 60 && im.h >= 60) {
      try { const el = page.locator('img').filter({ has: null }); } catch {}
    }
  }
  // screenshot any likely QR img
  const qrEl = await page.$('img[src*="qr"], img[src*="ptlogin"], img[src*="qrcode"]');
  if (qrEl) { await qrEl.screenshot({ path: 'mount_qr.png' }); qrShot = true; log.push('qr element shot'); }
  else log.push('no qr img selector matched');

  fs.writeFileSync('mount_test.log', log.join('\n')+'\n');
  console.log(log.join('\n'));
  await browser.close();
})();
