const { chromium } = require('playwright');
const fs = require('fs');

const CDN = 'https://qqminiapp.cdn-go.cn/qq-open-platform/19bcbf74/assets/';
const MIRROR = 'https://q.qq.com/h5/assets-retry/';
const OUT = __dirname;
const MAX_MS = 12 * 60 * 1000;
const TARGET = 'https://q.qq.com/bot/#/home';

function status(obj) { fs.writeFileSync(OUT + '/status.json', JSON.stringify({ ts: Date.now(), ...obj }, null, 2)); }
function log(...a) { const s = a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' '); fs.appendFileSync(OUT + '/run.log', s + '\n'); }

const scanLogin = [];
const botApi = [];
function recordReq(r) {
  const u = r.url();
  const item = { method: r.method(), url: u };
  if (/ptlogin2\.qq\.com|xlogin|ptqrshow|ptqrlogin|ptlogin/i.test(u)) scanLogin.push({ type: 'request', ...item });
  else if (/q\.qq\.com\/bot|guild|robot|developer|app\.qq|bots\.qq/i.test(u) && !/assets|cdn|captcha|aegis|tam\.cdn/i.test(u)) botApi.push({ type: 'request', ...item });
}
async function recordResp(r) {
  const u = r.url();
  const ct = r.headers()['content-type'] || '';
  if (!ct.includes('json')) return;
  const item = { method: r.request().method(), status: r.status(), url: u };
  try { item.body = (await r.text()).slice(0, 6000); } catch {}
  if (/ptlogin2\.qq\.com|xlogin|ptqrshow|ptqrlogin|ptlogin/i.test(u)) scanLogin.push({ type: 'response', ...item });
  else if (/q\.qq\.com\/bot|guild|robot|developer|app\.qq|bots\.qq/i.test(u) && !/assets|cdn|captcha|aegis|tam\.cdn/i.test(u)) botApi.push({ type: 'response', ...item });
}

function loginCookiePresent(cookies) {
  return cookies.some(c => /skey|p_skey|p_uin|uin/i.test(c.name));
}

async function dumpDiagnostic(page, ctx, reason) {
  let cookies = [], url = '', body = '';
  try { cookies = await ctx.cookies(); } catch (e) {}
  try { url = page.url(); } catch (e) {}
  try { body = await page.evaluate(() => document.body ? document.body.innerText.replace(/\s+/g, ' ').slice(0, 800) : ''); } catch (e) {}
  const result = {
    reason,
    finalUrl: url,
    skeyPresent: loginCookiePresent(cookies),
    cookieNames: cookies.map(c => c.name + '@' + c.domain),
    bodySample: body,
    scanLoginApi: scanLogin,
    botApi: botApi,
  };
  fs.writeFileSync(OUT + '/results.json', JSON.stringify(result, null, 2));
  log('DIAGNOSTIC DUMP reason=' + reason + ' skey=' + result.skeyPresent + ' cookies=' + result.cookieNames.length + ' url=' + url);
}

(async () => {
  status({ phase: 'starting' });
  const browser = await chromium.launch({ channel: 'msedge', headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage'] });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 3 });
  const page = await ctx.newPage();

  await page.route('**://qqminiapp.cdn-go.cn/**', async (route) => {
    const url = route.request().url(); const mapped = url.replace(CDN, MIRROR);
    try { const resp = await fetch(mapped); const buf = Buffer.from(await resp.arrayBuffer()); const ct = resp.headers.get('content-type') || 'application/octet-stream'; await route.fulfill({ status: resp.status, headers: { 'content-type': ct, 'access-control-allow-origin': '*' }, body: buf }); }
    catch (e) { await route.fulfill({ status: 502, body: 'x' }); }
  });

  page.on('request', recordReq);
  page.on('response', recordResp);

  const start = Date.now();
  let loggedIn = false;
  let lastQrSrc = '';
  let qrTries = 0;

  try { await page.goto(TARGET, { waitUntil: 'load', timeout: 30000 }); } catch (e) { log('goto warn', e.message.slice(0, 160)); }

  async function captureQr() {
    try {
      let frame = null;
      for (let i = 0; i < 6 && !frame; i++) { frame = page.frames().find(f => /ptlogin2/.test(f.url())); if (!frame) await page.waitForTimeout(700); }
      if (!frame) return false;
      const info = await frame.evaluate(() => {
        const imgs = Array.from(document.querySelectorAll('img'));
        const qr = imgs.find(im => /ptqrshow|qrcode|qr/i.test(im.src)) || imgs.find(im => im.naturalWidth >= 80);
        return qr ? { src: qr.src, nw: qr.naturalWidth } : null;
      });
      if (!info) return false;
      if (info.src === lastQrSrc) return true;
      lastQrSrc = info.src;
      const h = await frame.evaluateHandle(() => { const imgs = Array.from(document.querySelectorAll('img')); return imgs.find(im => /ptqrshow|qrcode|qr/i.test(im.src)) || imgs.find(im => im.naturalWidth >= 80); });
      const el = h.asElement();
      if (el) { await el.screenshot({ path: OUT + '/qr.png' }); log('qr captured', info.nw + 'px (try ' + (++qrTries) + ')'); return true; }
    } catch (e) { log('captureQr err', e.message.slice(0, 120)); }
    return false;
  }

  async function handleModal() {
    const st = await page.evaluate(() => {
      const confirmBtns = Array.from(document.querySelectorAll('button,div,a,span')).filter(e => (e.innerText || '').trim() === '确认登录');
      let need = false, count = confirmBtns.length;
      for (const b of confirmBtns) { const cs = getComputedStyle(b); if (b.getClientRects().length > 0 && cs.visibility !== 'hidden' && cs.display !== 'none' && b.offsetParent !== null) { need = true; break; } }
      return { need, count };
    });
    if (!st.need) return false;
    log('modal 确认登录 visible (count=' + st.count + '), selecting first subject then confirm');
    try {
      await page.evaluate(() => {
        const rows = Array.from(document.querySelectorAll('li, [role=option], .subject-item, [class*="subject"], [class*="account"], [class*="list-item"]'));
        const pick = rows.find(r => r.querySelector('img,span') && !(r.innerText || '').includes('确认登录'));
        if (pick) pick.click();
      });
      await page.waitForTimeout(700);
    } catch (e) {}
    try { await page.locator('text=确认登录').first().click({ timeout: 3000 }); log('clicked 确认登录'); } catch (e) { log('click 确认登录 err', e.message.slice(0, 100)); }
    await page.waitForTimeout(2500);
    return true;
  }

  async function consoleReady() {
    const txt = await page.evaluate(() => document.body ? document.body.innerText : '').catch(() => '');
    return ['我的机器人', '机器人管理', '机器人列表', '应用管理', '创建机器人'].some(s => txt.includes(s));
  }

  status({ phase: 'waiting_qr', note: '请扫最新发的二维码并在手机点确认登录' });
  for (let i = 0; i < MAX_MS / 2000 && !loggedIn; i++) {
    await captureQr();
    const cookies = await ctx.cookies().catch(() => []);
    const skey = loginCookiePresent(cookies);
    const cr = await consoleReady().catch(() => false);
    if (skey || cr) {
      loggedIn = true;
      log('LOGIN DETECTED skey=' + skey + ' console=' + cr + ' url=' + page.url());
      break;
    }
    await handleModal();
    if (i % 10 === 0) {
      const cs = await ctx.cookies().catch(() => []);
      log('heartbeat i=' + i + ' url=' + page.url().slice(0, 60) + ' cookies=' + cs.length + ' skey=' + loginCookiePresent(cs) + ' console=' + cr);
    }
    if (Date.now() - start > MAX_MS) { status({ phase: 'timeout' }); break; }
    await page.waitForTimeout(2000);
  }

  if (!loggedIn) {
    status({ phase: 'login_failed', qrTries });
    await dumpDiagnostic(page, ctx, 'login_failed_no_skey');
    await browser.close();
    return;
  }

  // ---- logged in: reach bot console & capture list API ----
  status({ phase: 'reaching_console' });
  if (!(await consoleReady().catch(() => false))) {
    try { await page.goto(TARGET, { waitUntil: 'load', timeout: 30000 }); } catch (e) {}
  }
  for (let i = 0; i < 20; i++) {
    if (await consoleReady().catch(() => false)) { log('console text found iter', i); break; }
    await page.waitForTimeout(1500);
  }
  await page.waitForTimeout(5000);
  for (const label of ['我的机器人', '机器人管理', '机器人列表', '应用管理']) {
    try { await page.locator('text=' + label).first().click({ timeout: 3000 }); log('clicked', label); await page.waitForTimeout(4000); break; } catch {}
  }
  await page.waitForTimeout(4000);
  if (!botApi.some(c => c.type === 'response')) {
    try { await page.goto('https://q.qq.com/bot/#/myBot', { waitUntil: 'networkidle', timeout: 15000 }); } catch {}
    await page.waitForTimeout(5000);
  }

  let extracted = {};
  try {
    extracted = await page.evaluate(() => {
      const txt = document.body ? document.body.innerText : '';
      const out = { rawTextSample: txt.slice(0, 2500) };
      const m = txt.match(/AppID[^\d]{0,10}(\d{6,20})/i) || txt.match(/应用ID[^\d]{0,10}(\d{6,20})/i) || txt.match(/机器人ID[^\d]{0,10}(\d{6,20})/i) || txt.match(/appid["'=\s:]{0,4}(\d{6,20})/i);
      if (m) out.appId = m[1];
      try { const ls = {}; for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); ls[k] = localStorage.getItem(k); } out.localStorage = ls; } catch (e) { out.storageErr = e.message; }
      return out;
    });
  } catch (e) {}
  try {
    const cookies = await ctx.cookies();
    extracted.cookies = cookies.map(c => ({ name: c.name, domain: c.domain, httpOnly: c.httpOnly, value: c.value.slice(0, 48) + (c.value.length > 48 ? '…' : '') }));
  } catch (e) {}
  try { await page.screenshot({ path: OUT + '/console.png', fullPage: false }); } catch {}

  const result = { loginUrl: page.url(), scanLoginApi: scanLogin, botApi: botApi, extracted };
  fs.writeFileSync(OUT + '/results.json', JSON.stringify(result, null, 2));
  status({ phase: 'done', scanLoginCount: scanLogin.length, botApiCount: botApi.length, appId: extracted.appId || null });
  log('DONE scanLogin=' + scanLogin.length + ' botApi=' + botApi.length);
  await browser.close();
})().catch(async e => { status({ phase: 'error', error: e.message }); log('FATAL', e.stack || e.message); try { const b = await chromium.launch({ channel: 'msedge', headless: true }); } catch {} process.exit(1); });
