const { chromium } = require('playwright');
const fs = require('fs');

const CDN = 'https://qqminiapp.cdn-go.cn/qq-open-platform/19bcbf74/assets/';
const MIRROR = 'https://q.qq.com/h5/assets-retry/';

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true, args: ['--no-sandbox','--disable-dev-shm-usage'] });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await ctx.newPage();
  await page.route('**://qqminiapp.cdn-go.cn/**', async (route) => {
    const url = route.request().url();
    const mapped = url.replace(CDN, MIRROR);
    try { const resp = await fetch(mapped); const buf = Buffer.from(await resp.arrayBuffer()); const ct = resp.headers.get('content-type')||'application/octet-stream'; await route.fulfill({ status: resp.status, headers: { 'content-type': ct, 'access-control-allow-origin': '*' }, body: buf }); }
    catch (e) { await route.fulfill({ status: 502, body: 'mirror fail' }); }
  });

  try { await page.goto('https://q.qq.com/bot/#/home', { waitUntil: 'load', timeout: 30000 }); } catch (e) { console.log('goto', e.message.slice(0,100)); }

  // poll for QR img
  let chosen = null;
  for (let i=0; i<40 && !chosen; i++) {
    const cands = await page.evaluate(() => {
      const arr = [];
      document.querySelectorAll('img').forEach(im => {
        const r = im.getBoundingClientRect();
        arr.push({ src: (im.src||'').slice(0,120), nw: im.naturalWidth, nh: im.naturalHeight, x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) });
      });
      return arr;
    });
    // pick candidate: square-ish, decent size, in top-right area
    const pick = cands.filter(c => c.nw>=120 && c.nh>=120 && c.w>0 && c.h>0 && Math.abs(c.nw-c.nh)<60 && c.x>500 && c.y<460)
                     .sort((a,b)=> (b.nw*b.nh)-(a.nw*a.nh))[0];
    if (pick) chosen = pick;
    else await page.waitForTimeout(1000);
  }
  if (chosen) {
    console.log('CHOSEN:', JSON.stringify(chosen));
    // screenshot the element
    const el = page.locator('img').filter({ has: null });
    // re-find by matching src
    const handle = await page.evaluateHandle((src) => {
      return Array.from(document.querySelectorAll('img')).find(im => im.src === src);
    }, chosen.src);
    const elem = handle.asElement();
    if (elem) { await elem.screenshot({ path: 'qr.png' }); console.log('qr.png written'); }
    else console.log('element handle null');
  } else {
    console.log('NO QR FOUND'); await page.screenshot({ path: 'qr_fallback.png' });
  }
  await browser.close();
})();
