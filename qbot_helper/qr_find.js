const { chromium } = require('playwright');
const fs = require('fs');

(async () => {
  const log = [];
  const browser = await chromium.launch({
    channel: 'msedge',
    headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await ctx.newPage();

  const qrResponses = [];
  page.on('response', r => {
    const u = r.url();
    if (/qr|ptlogin|scan|login\/qrcode|qrcode/i.test(u)) {
      qrResponses.push(r.status() + ' ' + u);
    }
  });
  page.on('console', m => {
    const t = m.text();
    if (/qr|扫码|登录|login|ticket/i.test(t)) log.push('[console] ' + m.type() + ' ' + t.slice(0,200));
  });

  try {
    await page.goto('https://q.qq.com/bot/#/home', { waitUntil: 'domcontentloaded', timeout: 30000 });
  } catch (e) { log.push('goto warn: ' + e.message.slice(0,150)); }

  // wait for app to boot and render login UI
  await page.waitForTimeout(12000);

  const info = await page.evaluate(() => {
    const out = {};
    out.url = location.href;
    // find images
    out.imgs = Array.from(document.querySelectorAll('img')).map(i => ({ src: (i.src||'').slice(0,200), w: i.naturalWidth, h: i.naturalHeight, alt: i.alt||'' })).filter(i=>i.w>20||i.src);
    out.canvases = document.querySelectorAll('canvas').length;
    // text hits
    const kw = ['扫码登录','二维码','扫一扫','QQ扫码','微信登录','手机号'];
    const hits = [];
    document.querySelectorAll('*').forEach(el => {
      const t = (el.innerText||'').trim();
      if (t && kw.some(k=>t.includes(k)) && t.length<40) hits.push(t);
    });
    out.textHits = [...new Set(hits)].slice(0,20);
    out.bodyLen = (document.body?document.body.innerText:'').length;
    return out;
  });
  log.push('URL=' + info.url);
  log.push('IMGS=' + JSON.stringify(info.imgs.slice(0,15)));
  log.push('CANVASES=' + info.canvases);
  log.push('TEXT_HITS=' + JSON.stringify(info.textHits));
  log.push('BODY_LEN=' + info.bodyLen);
  log.push('QR_RESPONSES=' + JSON.stringify(qrResponses.slice(0,15)));

  // try to screenshot an img that looks like a QR (square-ish, decent size)
  let shot = false;
  for (const im of info.imgs) {
    if (im.w >= 80 && im.h >= 80) {
      try {
        const el = await page.$('img[src="' + (im.src.startsWith('http')? im.src : '') + '"]');
      } catch {}
    }
  }
  await page.screenshot({ path: 'qr_page.png' });
  log.push('saved qr_page.png');

  fs.writeFileSync('qr_find.log', log.join('\n') + '\n');
  await browser.close();
  console.log(log.join('\n'));
})();
