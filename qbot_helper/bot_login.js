const { chromium } = require('playwright');
const fs = require('fs');

const CDN = 'https://qqminiapp.cdn-go.cn/qq-open-platform/19bcbf74/assets/';
const MIRROR = 'https://q.qq.com/h5/assets-retry/';
const OUT = __dirname;
const MAX_MS = 15 * 60 * 1000;

function status(obj) { fs.writeFileSync(OUT + '/status.json', JSON.stringify({ ts: Date.now(), ...obj }, null, 2)); }
function log(...a) { const s = a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' '); fs.appendFileSync(OUT + '/run.log', s + '\n'); }

(async () => {
  status({ phase: 'starting' });
  const browser = await chromium.launch({ channel: 'msedge', headless: true, args: ['--no-sandbox','--disable-dev-shm-usage'] });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 3 });
  const page = await ctx.newPage();

  await page.route('**://qqminiapp.cdn-go.cn/**', async (route) => {
    const url = route.request().url(); const mapped = url.replace(CDN, MIRROR);
    try { const resp = await fetch(mapped); const buf = Buffer.from(await resp.arrayBuffer()); const ct = resp.headers.get('content-type')||'application/octet-stream'; await route.fulfill({ status: resp.status, headers: { 'content-type': ct, 'access-control-allow-origin': '*' }, body: buf }); }
    catch (e) { await route.fulfill({ status: 502, body: 'x' }); }
  });

  // network capture (only after login)
  let recording = false;
  const captured = [];
  page.on('request', r => {
    if (!recording) return;
    const u = r.url();
    if (/bot|app|developer|guild|robot|q\.qq\.com\/bot|bots\.qq/i.test(u) && !/assets|cdn|captcha|aegis|tam\.cdn/i.test(u)) {
      captured.push({ type: 'request', method: r.method(), url: u, headers: r.headers() });
    }
  });
  page.on('response', async r => {
    if (!recording) return;
    const u = r.url();
    const ct = r.headers()['content-type'] || '';
    if (/bot|app|developer|guild|robot|q\.qq\.com\/bot|bots\.qq/i.test(u) && ct.includes('json') && !/assets|cdn|captcha|aegis/i.test(u)) {
      try { const body = await r.text(); captured.push({ type: 'response', method: r.request().method(), status: r.status(), url: u, body: body.slice(0, 4000) }); } catch {}
    }
  });

  const start = Date.now();
  let loggedIn = false;
  let lastQrSrc = '';

  try {
    await page.goto('https://q.qq.com/bot/#/home', { waitUntil: 'load', timeout: 30000 });
  } catch (e) { log('goto warn', e.message.slice(0,160)); }

  async function captureQr() {
    try {
      let frame = null;
      for (let i = 0; i < 20 && !frame; i++) { frame = page.frames().find(f => /ptlogin2/.test(f.url())); if (!frame) await page.waitForTimeout(800); }
      if (!frame) return false;
      await frame.waitForSelector('img', { timeout: 8000 }).catch(()=>{});
      const info = await frame.evaluate(() => {
        const imgs = Array.from(document.querySelectorAll('img'));
        const qr = imgs.find(im => /ptqrshow|qrcode|qr/i.test(im.src)) || imgs.find(im => im.naturalWidth >= 80);
        return qr ? { src: qr.src, nw: qr.naturalWidth, nh: qr.naturalHeight } : null;
      });
      if (!info) return false;
      if (info.src === lastQrSrc) return true; // unchanged
      lastQrSrc = info.src;
      const h = await frame.evaluateHandle(() => { const imgs = Array.from(document.querySelectorAll('img')); return imgs.find(im => /ptqrshow|qrcode|qr/i.test(im.src)) || imgs.find(im => im.naturalWidth >= 80); });
      const el = h.asElement();
      if (el) { await el.screenshot({ path: OUT + '/qr.png' }); log('qr captured', info.nw + 'x' + info.nh); return true; }
    } catch (e) { log('captureQr err', e.message.slice(0,120)); }
    return false;
  }

  async function detectState() {
    return await page.evaluate(() => {
      const txt = document.body ? document.body.innerText : '';
      const iframeGone = !document.querySelector('iframe.ptlogin__iframe');
      const btn = Array.from(document.querySelectorAll('button,div,a,span')).find(e => (e.innerText||'').trim() === '确认登录');
      let needConfirm = false;
      if (btn) {
        const r = btn.getClientRects();
        const cs = getComputedStyle(btn);
        needConfirm = r.length > 0 && cs.visibility !== 'hidden' && cs.display !== 'none' && btn.offsetParent !== null;
      }
      return {
        hasScanText: txt.includes('用手机打开QQ扫一扫'),
        hasConsole: txt.includes('我的机器人') || txt.includes('机器人管理') || txt.includes('机器人列表') || txt.includes('控制台'),
        url: location.href,
        iframeGone,
        needConfirm,
      };
    });
  }

  // Phase 1: wait for QR + login
  status({ phase: 'waiting_qr', note: '请用手机QQ扫码并在手机上点确认登录' });
  let qrOk = false;
  for (let i = 0; i < MAX_MS/3000 && !loggedIn; i++) {
    qrOk = await captureQr();
    const st = await detectState();
    if (st.needConfirm) {
      log('modal 确认登录 detected, clicking');
      try { await page.locator('text=确认登录').first().click({ timeout: 3000 }); } catch (e) { log('click confirm err', e.message.slice(0,120)); }
      await page.waitForTimeout(2000);
      continue;
    }
    if (!st.hasScanText && (st.hasConsole || st.url.includes('/bot/'))) {
      loggedIn = true;
      log('LOGIN DETECTED url=', st.url);
      break;
    }
    if (Date.now() - start > MAX_MS) { status({ phase: 'timeout' }); break; }
    if (i % 5 === 0) status({ phase: 'waiting_qr', elapsedSec: Math.floor((Date.now()-start)/1000), qrOk });
    await page.waitForTimeout(3000);
  }

  if (!loggedIn) {
    status({ phase: 'login_failed', qrOk });
    await browser.close();
    return;
  }

  // Phase 2: capture bot-list API + credentials
  status({ phase: 'capturing' });
  recording = true;
  await page.waitForTimeout(4000);
  // try to open 我的机器人 to trigger list API
  try { await page.locator('text=我的机器人').first().click({ timeout: 4000 }); log('clicked 我的机器人'); } catch (e) { log('no 我的机器人 link'); }
  await page.waitForTimeout(5000);

  // try also navigate to common bot list route if nothing captured
  if (!captured.some(c => c.type === 'response')) {
    try { await page.goto('https://q.qq.com/bot/#/myBot', { waitUntil: 'networkidle', timeout: 15000 }); } catch {}
    await page.waitForTimeout(4000);
  }

  // extract AppID / secrets from visible page
  let extracted = {};
  try {
    extracted = await page.evaluate(() => {
      const txt = document.body ? document.body.innerText : '';
      const out = { rawTextSample: txt.slice(0, 1500) };
      const m = txt.match(/AppID[^\d]{0,10}(\d{6,20})/i) || txt.match(/应用ID[^\d]{0,10}(\d{6,20})/i) || txt.match(/机器人ID[^\d]{0,10}(\d{6,20})/i);
      if (m) out.appId = m[1];
      // storage tokens
      try {
        const ls = {}; for (let i=0;i<localStorage.length;i++){const k=localStorage.key(i); ls[k]=localStorage.getItem(k);} out.localStorage = ls;
        const ss = {}; for (let i=0;i<sessionStorage.length;i++){const k=sessionStorage.key(i); ss[k]=sessionStorage.getItem(k);} out.sessionStorage = ss;
      } catch(e){ out.storageErr = e.message; }
      return out;
    });
  } catch (e) {}
  try {
    const cookies = await ctx.cookies();
    extracted.cookies = cookies.map(c => ({ name: c.name, domain: c.domain, value: c.value.slice(0,40) + (c.value.length>40?'…':'') }));
  } catch (e) {}
  try { await page.screenshot({ path: OUT + '/console.png', fullPage: false }); } catch {}

  const result = {
    loginUrl: page.url(),
    capturedRequests: captured.filter(c => c.type === 'request'),
    capturedResponses: captured.filter(c => c.type === 'response'),
    extracted,
  };
  fs.writeFileSync(OUT + '/results.json', JSON.stringify(result, null, 2));
  status({ phase: 'done', capturedCount: captured.length, appId: extracted.appId || null });
  log('DONE. captured=', captured.length);
  await browser.close();
})().catch(e => { status({ phase: 'error', error: e.message }); log('FATAL', e.stack || e.message); process.exit(1); });
