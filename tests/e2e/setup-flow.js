// Ende-zu-Ende-Test im echten Browser (Playwright): Setup des ersten Admins, danach Login mit 2FA,
// "Passwort vergessen" mit Mail aus Mailpit, neues Passwort, Wiederherstellungscode, Abmelden.
// Aufruf: node setup-flow.js <IP des Test-Caddy> <Setup-Token> <Mailpit-Kommando>
// Der Hostname auth.test wird im Browser auf die IP gelegt. Das Mail-Kommando liefert Mailpit-JSON auf stdout.
const { chromium } = require('playwright');
const crypto = require('crypto');
const { execSync } = require('child_process');
const [ip, token, mailCmd] = process.argv.slice(2);
const base = 'https://auth.test';

function totp(b32) {
  const A = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'; let bits = 0, val = 0; const out = [];
  for (const c of b32) { val = (val << 5) | A.indexOf(c); bits += 5; if (bits >= 8) { out.push((val >> (bits - 8)) & 255); bits -= 8; } }
  const step = Math.floor(Date.now() / 30000) + (totp.offset || 0); const buf = Buffer.alloc(8); buf.writeBigUInt64BE(BigInt(step));
  const h = crypto.createHmac('sha1', Buffer.from(out)).update(buf).digest(); const o = h[h.length - 1] & 15;
  return String(((h.readUInt32BE(o) & 0x7fffffff) % 1000000)).padStart(6, '0');
}
const ok = (c, m) => { if (!c) { console.log('FAIL:', m); process.exitCode = 1; } else console.log('ok:', m); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const PW1 = 'correct horse battery staple';
const PW2 = 'a brand new passphrase 2026';

function mailbox() { return JSON.parse(execSync(mailCmd + '/api/v1/messages', { encoding: 'utf8' })); }
async function waitForMail(count) {
  for (let i = 0; i < 40; i++) { const m = mailbox(); if ((m.messages || []).length >= count) return m.messages; await sleep(500); }
  throw new Error('keine Mail angekommen');
}
function mailText(id) { return JSON.parse(execSync(`${mailCmd}/api/v1/message/${id}`, { encoding: 'utf8' })); }

// Der Browser soll den Test-Caddy direkt ansprechen, nicht über einen Umgebungs-Proxy
for (const k of Object.keys(process.env)) if (/^(https?|all|no)_proxy$/i.test(k)) delete process.env[k];

(async () => {
  const browser = await chromium.launch({ args: ['--no-proxy-server', `--host-resolver-rules=MAP auth.test ${ip}`] });
  const ctx = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 900, height: 1100 } });
  const page = await ctx.newPage();
  const problems = [];
  page.on('console', m => { if (['error', 'warning'].includes(m.type()) && !m.text().startsWith('Failed to load resource')) problems.push(m.text()); });
  page.on('pageerror', e => problems.push('pageerror ' + e.message));

  // ---------- Setup des ersten Admins ----------
  await page.goto(`${base}/setup/${token}`);
  await page.waitForSelector('#step-account:not([hidden])');
  ok((await page.textContent('#password-hint')).includes('15 characters') && (await page.locator('[data-i18n="password.guidance"]').textContent()).includes('Tip'), 'Setup: Hinweis und Hilfestellung unter dem Passwortfeld');
  await page.fill('#password', 'geheim'); await page.check('[data-show-password]');
  ok(await page.getAttribute('#password', 'type') === 'text' && await page.getAttribute('#password2', 'type') === 'text', 'Setup: "Passwort anzeigen" schaltet beide Felder um');
  await page.uncheck('[data-show-password]');
  ok(await page.getAttribute('#password', 'type') === 'password', 'Setup: "Passwort anzeigen" wieder aus');
  await page.fill('#username', 'Joshua');
  await page.fill('#email', 'joshua@example.com');
  await page.fill('#password', PW1); await page.fill('#password2', PW1);
  await page.click('#step-account button[type=submit]');
  await page.waitForSelector('#step-totp:not([hidden])');
  const secret = (await page.textContent('#secret')).replace(/\s/g, '');
  await page.fill('#totp', totp(secret));
  await page.click('#step-totp button[type=submit]');
  await page.waitForSelector('#step-done:not([hidden])');
  const recovery = await page.locator('#codes li').allTextContents();
  ok(recovery.length === 10, 'Setup: Admin angelegt, 10 Wiederherstellungscodes');

  // ---------- Login: Passwort + TOTP ----------
  async function loginPage(p) { await p.goto(`${base}/login`); await p.waitForSelector('#step-password:not([hidden])'); }
  const fresh = async () => { const c = await browser.newContext({ ignoreHTTPSErrors: true }); const p = await c.newPage(); p.on('pageerror', e => problems.push('pageerror ' + e.message)); return { c, p }; };

  let { c: c1, p: p1 } = await fresh();
  await loginPage(p1);
  await p1.fill('#identifier', 'joshua'); await p1.fill('#password', 'falsches passwort hier');
  await p1.click('#step-password button[type=submit]');
  await p1.waitForFunction(() => document.querySelector('#password-error').textContent.length > 0);
  ok((await p1.textContent('#password-error')).includes('wrong'), 'Login: falsches Passwort zeigt Fehlertext');

  await p1.fill('#password', PW1); await p1.check('#remember');
  await p1.click('#step-password button[type=submit]');
  await p1.waitForSelector('#step-code:not([hidden])');
  ok(true, 'Login: nach dem Passwort wird der 2FA-Code verlangt');
  // Hier ist noch keine Anmeldung: Startseite zeigt "Anmelden"
  await p1.fill('#code', '000000'); await p1.click('#step-code button[type=submit]');
  await p1.waitForFunction(() => document.querySelector('#code-error').textContent.length > 0);
  ok(true, 'Login: falscher Code abgelehnt');
  totp.offset = 1; // der Code des aktuellen Fensters wurde beim Setup schon benutzt, das nächste gilt
  await p1.fill('#code', totp(secret)); await p1.click('#step-code button[type=submit]');
  await p1.waitForURL(`${base}/`);
  await p1.waitForSelector('#signed-in:not([hidden])');
  ok((await p1.textContent('#name')) === 'joshua', 'Login: angemeldet als joshua, Startseite zeigt den Namen');

  // Cookie-Eigenschaften im echten Browser
  const cookies = await c1.cookies();
  const sid = cookies.find(c => c.name === '__Host-sid'); const xsrf = cookies.find(c => c.name === 'XSRF-TOKEN');
  ok(sid && sid.httpOnly && sid.secure && sid.sameSite === 'Lax' && sid.path === '/' , 'Sitzungs-Cookie: __Host-sid, HttpOnly, Secure, SameSite=Lax');
  ok(sid && sid.expires > Date.now() / 1000 + 29 * 86400, 'Sitzungs-Cookie: mit "Eingeloggt bleiben" rund 30 Tage');
  ok(xsrf && xsrf.secure && !xsrf.httpOnly && xsrf.sameSite === 'Lax', 'CSRF-Cookie: Secure, SameSite=Lax, für die UI lesbar');
  ok(!(await page.evaluate(() => document.cookie.includes('__Host-sid'))), 'Sitzungs-Cookie ist für JavaScript nicht lesbar');

  // ---------- Passwort vergessen ----------
  const before = (mailbox().messages || []).length;
  let { c: c2, p: p2 } = await fresh();
  await p2.goto(`${base}/forgot`);
  await p2.fill('#email', 'unbekannt@example.com'); await p2.click('#form button[type=submit]');
  await p2.waitForSelector('#sent:not([hidden])');
  const unknownText = await p2.textContent('#sent');
  await p2.fill('#email', 'Joshua@Example.com'); await p2.click('#form button[type=submit]');
  await p2.waitForSelector('#sent:not([hidden])');
  ok((await p2.textContent('#sent')) === unknownText, 'Passwort vergessen: gleiche Meldung für bekannte und unbekannte Adresse');
  const msgs = await waitForMail(before + 1);
  await sleep(1500);
  ok(mailbox().messages.length === before + 1, 'Passwort vergessen: genau eine Mail (nur an die bekannte Adresse)');
  const mail = mailText(msgs[0].ID);
  ok(mail.Subject === 'Reset your password' && mail.To[0].Address === 'joshua@example.com', 'Reset-Mail an die richtige Adresse, Englisch');
  const link = mail.Text.match(/https:\/\/auth\.test\/reset\/[A-Za-z0-9_-]+/)[0];

  await p2.goto(link);
  await p2.waitForSelector('#step-form:not([hidden])');
  await p2.fill('#password', 'kurz'); await p2.fill('#password2', 'kurz'); await p2.click('#step-form button[type=submit]');
  await p2.waitForFunction(() => document.querySelector('#error').textContent.length > 0);
  ok((await p2.textContent('#error')).includes('15'), 'Reset: zu kurzes Passwort abgelehnt, Link bleibt gültig');
  await p2.fill('#password', 'qwertyuiopasdfghjkl'); await p2.fill('#password2', 'qwertyuiopasdfghjkl'); await p2.click('#step-form button[type=submit]');
  await p2.waitForFunction(() => document.querySelector('#error').textContent.includes('leaked'));
  ok(true, 'Reset: Passwort von der Sperrliste abgelehnt');
  await p2.fill('#password', 'Joshua-Joshua-2026-JOSHUA'); await p2.fill('#password2', 'Joshua-Joshua-2026-JOSHUA'); await p2.click('#step-form button[type=submit]');
  await p2.waitForFunction(() => document.querySelector('#error').textContent.includes('username'));
  ok(true, 'Reset: aus dem Usernamen abgeleitetes Passwort abgelehnt, mit Grund');
  await p2.fill('#password', PW2); await p2.fill('#password2', PW2); await p2.click('#step-form button[type=submit]');
  await p2.waitForSelector('#step-done:not([hidden])');
  ok(true, 'Reset: neues Passwort gesetzt');
  await p2.goto(link); await p2.waitForSelector('#state-invalid:not([hidden])');
  ok(true, 'Reset: Link ist verbraucht (neutrale Fehlerseite)');

  // Alte Sitzung von oben ist beendet
  await p1.goto(`${base}/`); await p1.waitForSelector('#signed-out:not([hidden])');
  ok(true, 'Reset: bestehende Sitzung wurde beendet');

  // ---------- Login mit neuem Passwort und Wiederherstellungscode ----------
  let { p: p3 } = await fresh();
  await loginPage(p3);
  await p3.fill('#identifier', 'joshua@example.com'); await p3.fill('#password', PW1);
  await p3.click('#step-password button[type=submit]');
  await p3.waitForFunction(() => document.querySelector('#password-error').textContent.length > 0);
  ok(true, 'Login: altes Passwort geht nach dem Reset nicht mehr');
  await p3.fill('#password', PW2); await p3.click('#step-password button[type=submit]');
  await p3.waitForSelector('#step-code:not([hidden])');
  await p3.fill('#code', recovery[0].toLowerCase()); await p3.click('#step-code button[type=submit]');
  await p3.waitForSelector('#signed-in:not([hidden])');
  ok(true, 'Login: Anmeldung mit Wiederherstellungscode (E-Mail als Kennung)');
  await p3.screenshot({ path: process.env.SHOT_DIR ? process.env.SHOT_DIR + '/home.png' : undefined });

  // ---------- Abmelden ----------
  await p3.click('#logout');
  await p3.waitForURL(`${base}/login`);
  await p3.goto(`${base}/`); await p3.waitForSelector('#signed-out:not([hidden])');
  ok(true, 'Abmelden: Sitzung beendet');

  // ---------- Setup-Link ist tot, Header ----------
  // fetch im Browser: Playwrights eigener Request-Client kennt die Host-Zuordnung für auth.test nicht
  await page.goto(`${base}/`);
  const get = (u) => page.evaluate(async (url) => { const r = await fetch(url); return { s: r.status, h: Object.fromEntries(r.headers) }; }, u);
  ok((await get(`${base}/api/setup/${token}`)).s === 404, 'Setup-Link nach dem Admin tot');
  const h = (await get(`${base}/login`)).h;
  ok((h['content-security-policy'] || '').includes("frame-ancestors 'none'") && h['cache-control'] === 'no-store', 'Login-Seite: frame-ancestors none, no-store');
  ok(problems.length === 0, 'keine Konsolenfehler / CSP-Verstöße ' + JSON.stringify(problems));
  await browser.close();
})().catch(e => { console.log('FAIL: Ausnahme', e.message); process.exit(1); });
