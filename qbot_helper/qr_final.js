const { chromium } = require('playwright');
const CDN = 'https://qqminiapp.cdn-go.cn/qq-open-platform/19bcbf74/assets/';
const MIRROR = 'https://q.qq.com/h5/assets-retry/';

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true, args: ['--no-sandbox','--disable-dev-shm-usage'] });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 3 });
  const page = await ctx.newPage();
  await page.route('**://qqminiapp.cdn-go.cn/**', async (route) => {
    const url = route.request().url(); const mapped = url.replace(CDN, MIRROR);
    try { const resp = await fetch(mapped); const buf = Buffer.from(await resp.arrayBuffer()); const ct = resp.headers.get('content-type')||'application/octet-stream'; await route.fulfill({ status: resp.status, headers: { 'content-type': ct, 'access-control-allow-origin': '*' }, body: buf }); }
    catch (e) { await route.fulfill({ status: 502, body: 'x' }); }
  });
  try { await page.goto('https://q.qq.com/bot/#/home', { waitUntil: 'load', timeout: 30000 }); } catch (e) {}

  // wait for ptlogin iframe via frames list
  let frame = null;
  for (let i=0;i<30 && !frame;i++){
    const frames = page.frames();
    frame = frames.find(f => /ptlogin2/.test(f.url()));
    if (!frame) await page.waitForTimeout(1000);
  }
  if (!frame) {
    console.log('NO FRAME. frames:', JSON.stringify(page.frames().map(f=>f.url()).slice(0,8)));
    await browser.close(); return;
  }
  console.log('FRAME_URL:', frame.url().slice(0,80));
  // wait for an img inside frame (the QR)
  await frame.waitForSelector('img', { timeout: 15000 }).catch(()=>{});
  const qrInfo = await frame.evaluate(() => {
    const imgs = Array.from(document.querySelectorAll('img'));
    const qr = imgs.find(im => /ptqrshow|qrcode|qr/i.test(im.src)) || imgs.find(im => im.naturalWidth>=80);
    if (!qr) return null;
    return { src: qr.src.slice(0,140), nw: qr.naturalWidth, nh: qr.naturalHeight };
  });
  console.log('QR_IN_FRAME:', JSON.stringify(qrInfo));
  if (qrInfo) {
    const h = await frame.evaluateHandle(() => {
      const imgs = Array.from(document.querySelectorAll('img'));
      return imgs.find(im => /ptqrshow|qrcode|qr/i.test(im.src)) || imgs.find(im => im.naturalWidth>=80);
    });
    const el = h.asElement();
    if (el) { await el.screenshot({ path: 'qr.png' }); console.log('qr.png written, natural', qrInfo.nw+'x'+qrInfo.nh); }
  }
  await browser.close();
})();
