const { chromium } = require('playwright');
const fs = require('fs');

(async () => {
  const browser = await chromium.launch({
    channel: 'msedge',
    headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });
  const ctx = await browser.newContext({
    viewport: { width: 1280, height: 900 },
    userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0',
  });
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', e => errs.push('PAGEERR: ' + (e.stack||e.message||'').slice(0,400)));
  page.on('console', m => { if (m.type()==='error') errs.push('CONSOLE.ERR: ' + m.text().slice(0,300)); });

  try {
    await page.goto('https://q.qq.com/bot/#/home', { waitUntil: 'load', timeout: 30000 });
  } catch (e) { errs.push('GOTO: ' + e.message.slice(0,150)); }
  await page.waitForTimeout(10000);

  const dump = await page.evaluate(() => {
    const root = document.getElementById('app') || document.body;
    return {
      url: location.href,
      title: document.title,
      docHtmlLen: document.documentElement.outerHTML.length,
      appHtmlLen: (document.getElementById('app')? document.getElementById('app').innerHTML.length : -1),
      iframes: Array.from(document.querySelectorAll('iframe')).map(f => f.src),
      htmlHead: document.documentElement.outerHTML.slice(0, 800),
    };
  });
  fs.writeFileSync('inspect.log', JSON.stringify(dump, null, 2) + '\n\nERRORS:\n' + errs.join('\n') + '\n');
  console.log('URL:', dump.url, '| title:', dump.title);
  console.log('docHtmlLen:', dump.docHtmlLen, '| appHtmlLen:', dump.appHtmlLen);
  console.log('iframes:', JSON.stringify(dump.iframes));
  console.log('ERRORS count:', errs.length);
  errs.slice(0,10).forEach(e=>console.log('  '+e.slice(0,200)));
  await browser.close();
})();
