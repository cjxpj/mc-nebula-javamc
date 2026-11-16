const { chromium } = require('playwright');
const fs = require('fs');
const CDN = 'https://qqminiapp.cdn-go.cn/qq-open-platform/19bcbf74/assets/';
const MIRROR = 'https://q.qq.com/h5/assets-retry/';

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true, args: ['--no-sandbox','--disable-dev-shm-usage'] });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await ctx.newPage();
  await page.route('**://qqminiapp.cdn-go.cn/**', async (route) => {
    const url = route.request().url(); const mapped = url.replace(CDN, MIRROR);
    try { const resp = await fetch(mapped); const buf = Buffer.from(await resp.arrayBuffer()); const ct = resp.headers.get('content-type')||'application/octet-stream'; await route.fulfill({ status: resp.status, headers: { 'content-type': ct, 'access-control-allow-origin': '*' }, body: buf }); }
    catch (e) { await route.fulfill({ status: 502, body: 'x' }); }
  });
  const ptResp = [];
  page.on('response', r => { const u=r.url(); if (/ptlogin|qrcode|qrlogin/i.test(u)) ptResp.push(r.status()+' '+u.slice(0,160)); });

  try { await page.goto('https://q.qq.com/bot/#/home', { waitUntil: 'load', timeout: 30000 }); } catch (e) {}
  await page.waitForTimeout(9000);

  const info = await page.evaluate(() => {
    const cx = 862, cy = 178;
    const el = document.elementFromPoint(cx, cy);
    const describe = (e) => e ? ({ tag: e.tagName, id: e.id, cls: (e.className||'').toString().slice(0,60), src: (e.src||'').slice(0,120), nw: e.naturalWidth||0, nh: e.naturalHeight||0 }) : null;
    const atPoint = describe(el);
    const imgs = Array.from(document.querySelectorAll('img')).map(im => ({ src:(im.src||'').slice(0,120), nw:im.naturalWidth, nh:im.naturalHeight, x:Math.round(im.getBoundingClientRect().x), y:Math.round(im.getBoundingClientRect().y) })).filter(i=>/ptlogin|qrcode|qr/i.test(i.src));
    return { atPoint, qrImgs: imgs };
  });
  console.log('ELEMENT_AT_QR_CENTER:', JSON.stringify(info.atPoint));
  console.log('QR_IMGS:', JSON.stringify(info.qrImgs));
  console.log('PT_RESPONSES:'); ptResp.slice(0,20).forEach(r=>console.log('  '+r));

  // try to screenshot element at point if it's an img
  if (info.atPoint && info.atPoint.tag === 'IMG') {
    const h = await page.evaluateHandle((cx,cy)=>document.elementFromPoint(cx,cy), 862, 178);
    const el = h.asElement();
    if (el) { await el.screenshot({ path: 'qr_clean.png' }); console.log('qr_clean.png written (img)'); }
  } else {
    console.log('QR not an IMG; will crop by coordinates');
  }
  await browser.close();
})();
