// Ende-zu-Ende-Test der Setup-Seite im echten Browser (Playwright). Wird von run.sh gestartet.
const { chromium } = require('playwright');
const crypto = require('crypto');
const fs = require('fs');
// Aufruf: node setup-flow.js <Basis-URL> <Setup-Token>
const [base, token] = process.argv.slice(2);

function totp(b32) {
  const A = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'; let bits = 0, val = 0; const out = [];
  for (const c of b32) { val = (val << 5) | A.indexOf(c); bits += 5; if (bits >= 8) { out.push((val >> (bits - 8)) & 255); bits -= 8; } }
  const step = Math.floor(Date.now() / 30000); const buf = Buffer.alloc(8); buf.writeBigUInt64BE(BigInt(step));
  const h = crypto.createHmac('sha1', Buffer.from(out)).update(buf).digest(); const o = h[h.length - 1] & 15;
  return String(((h.readUInt32BE(o) & 0x7fffffff) % 1000000)).padStart(6, '0');
}
const ok = (c, m) => { if (!c) { console.log('FAIL:', m); process.exitCode = 1; } else console.log('ok:', m); };

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 900, height: 1100 }, deviceScaleFactor: 1 });
  const problems = [];
  // 400/404 der absichtlich falschen Eingaben sind erwartet. Wichtig sind Skript- und CSP-Fehler.
  page.on('console', m => { if (['error', 'warning'].includes(m.type()) && !m.text().startsWith('Failed to load resource')) problems.push(m.text()); });
  page.on('pageerror', e => problems.push('pageerror ' + e.message));

  await page.goto(`${base}/setup/${token}`);
  await page.waitForSelector('#step-account:not([hidden])');
  ok(await page.title() === 'Set up the first admin', 'Titel Englisch (Standard)');

  // Sprache umschalten
  await page.selectOption('#lang', 'de');
  await page.waitForFunction(() => document.querySelector('#step-account h1').textContent.includes('Admin-Account'));
  ok(true, 'Umschalten auf Deutsch');
  await page.selectOption('#lang', 'en');
  await page.waitForFunction(() => document.querySelector('#step-account h1').textContent.includes('Create the admin'));

  // zu kurzes Passwort -> Backend-Fehlercode, Text aus Sprachdatei
  await page.fill('#username', 'Joshua');
  await page.fill('#email', 'joshua@example.com');
  await page.fill('#password', 'kurz'); await page.fill('#password2', 'kurz');
  await page.click('#step-account button[type=submit]');
  await page.waitForSelector('#step-totp:not([hidden])');
  await page.fill('#totp', '000000');
  await page.click('#step-totp button[type=submit]');
  await page.waitForSelector('#step-account:not([hidden])');
  ok((await page.textContent('#account-error')).includes('at least 15'), 'Passwort zu kurz: Fehlertext Englisch');
  await page.selectOption('#lang', 'de');
  await page.waitForFunction(() => document.querySelector('#account-error').textContent.includes('mindestens 15'));
  ok(true, 'Fehlertext wechselt mit der Sprache');

  // richtig
  await page.fill('#password', 'correct horse battery staple'); await page.fill('#password2', 'correct horse battery staple');
  await page.click('#step-account button[type=submit]');
  await page.waitForSelector('#step-totp:not([hidden])');
  ok(await page.locator('#qr svg').count() === 1, 'QR-Code als SVG angezeigt');
  const secret = (await page.textContent('#secret')).replace(/\s/g, '');
  ok(/^[A-Z2-7]{32}$/.test(secret), 'Geheimnis 32 Zeichen Base32 (160 Bit)');
  await page.screenshot({ path: process.env.SHOT_DIR ? process.env.SHOT_DIR + '/step2.png' : undefined });

  // falscher Code zuerst
  await page.fill('#totp', '000000');
  await page.click('#step-totp button[type=submit]');
  await page.waitForFunction(() => document.querySelector('#totp-error').textContent.length > 0);
  ok((await page.textContent('#totp-error')).includes('Code ist falsch'), 'falscher TOTP-Code abgelehnt (Deutsch)');
  await page.fill('#totp', totp(secret));
  await page.click('#step-totp button[type=submit]');
  await page.waitForSelector('#step-done:not([hidden])');
  const codes = await page.locator('#codes li').allTextContents();
  ok(codes.length === 10 && codes.every(c => /^([A-Z2-9]{4}-){3}[A-Z2-9]{4}$/.test(c)), '10 Wiederherstellungscodes angezeigt');
  ok(await page.getAttribute('#next', 'aria-disabled') === 'true', 'Weiter gesperrt bis bestätigt');
  await page.check('#saved');
  ok(await page.getAttribute('#next', 'aria-disabled') === 'false', 'Weiter nach Bestätigung frei');
  await page.screenshot({ path: process.env.SHOT_DIR ? process.env.SHOT_DIR + '/step3.png' : undefined });

  // Link ist verbraucht
  await page.goto(`${base}/setup/${token}`);
  await page.waitForSelector('#state-invalid:not([hidden])');
  ok(true, 'Link nach Benutzung: neutrale Fehlerseite');
  const r = await page.request.post(`${base}/api/setup/${token}/complete`, { data: {} });
  ok(r.status() === 404, 'API nach Setup tot (404)');

  // Frames verboten
  const f = await page.request.get(`${base}/setup/${token}`);
  ok((f.headers()['content-security-policy'] || '').includes("frame-ancestors 'none'"), 'frame-ancestors none');
  ok(problems.length === 0, 'keine Konsolenfehler / CSP-Verstöße ' + JSON.stringify(problems));
  await browser.close();
})();
