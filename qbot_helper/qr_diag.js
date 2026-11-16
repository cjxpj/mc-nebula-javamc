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
  try { await page.goto('https://q.qq.com/bot/#/home', { waitUntil: 'load', timeout: 30000 }); } catch (e) {}
  await page.waitForTimeout(6000);

  const data = await page.evaluate(() => {
    const out = { imgs: [], canvases: [], bgDivs: [] };
    document.querySelectorAll('img').forEach(im => { const r = im.getBoundingClientRect(); out.imgs.push({ src:(im.src||'').slice(0,90), nw:im.naturalWidth, nh:im.naturalHeight, x:Math.round(r.x), y:Math.round(r.y), w:Math.round(r.width), h:Math.round(r.height) }); });
    document.querySelectorAll('canvas').forEach(c => { const r=c.getBoundingClientRect(); out.canvases.push({ x:Math.round(r.x), y:Math.round(r.y), w:Math.round(r.width), h:Math.round(r.height) }); });
    document.querySelectorAll('div').forEach(d => { const s = d.getAttribute('style')||''; if (/background[^;]*url\(.*(qr|qrcode|ptlogin)/i.test(s)) { const r=d.getBoundingClientRect(); out.bgDivs.push({ x:Math.round(r.x), y:Math.round(r.y), w:Math.round(r.width), h:Math.round(r.height), style: s.slice(0,120) }); } });
    return out;
  });
  console.log('IMGS('+data.imgs.length+'):'); data.imgs.slice(0,30).forEach(i=>console.log('  ', JSON.stringify(i)));
  console.log('CANVASES('+data.canvases.length+'):', JSON.stringify(data.canvases));
  console.log('BG_DIVS('+data.bgDivs.length+'):'); data.bgDivs.forEach(d=>console.log('  ', JSON.stringify(d)));

  // locate login card by text
  let clip = null;
  try {
    const handle = await page.evaluateHandle(() => {
      const els = Array.from(document.querySelectorAll('*'));
      const t = els.find(e => (e.innerText||'').includes('用手机打开QQ扫一扫'));
      if (!t) return null;
      // walk up to a card-like container
      let el = t;
      for (let k=0;k<6;k++){ el = el.parentElement; if(!el) break; const r = el.getBoundingClientRect(); if (r.width>150 && r.height>150) return el; }
      return t;
    });
    const elem = handle.asElement();
    if (elem) { const bb = await elem.boundingBox(); console.log('LOGIN_CARD_BBOX:', JSON.stringify(bb)); clip = bb; await elem.screenshot({ path: 'qr_card.png' }); console.log('qr_card.png written'); }
  } catch(e){ console.log('card err', e.message.slice(0,100)); }

  await browser.close();
})();
