// Ende-zu-Ende-Test im echten Browser (Playwright): Setup des ersten Admins, danach Login mit 2FA,
// "Passwort vergessen" mit Mail aus Mailpit, neues Passwort, Wiederherstellungscode, Abmelden.
// Danach: Dienst anlegen (auth-admin im Container), OIDC-Anmeldung eines Dienstes mit PKCE durch den Browser,
// Token-Austausch, "Kein Zugang"-Seite, Schlüsselrotation.
// Aufruf: node setup-flow.js <IP des Test-Caddy> <Setup-Token> <Mailpit-Kommando> <Backend-Kommando>
// Der Hostname auth.test wird im Browser auf die IP gelegt. Das Mail-Kommando liefert Mailpit-JSON auf stdout,
// das Backend-Kommando führt ein Programm im Backend-Container aus (z. B. "docker exec auth-backend").
const { chromium } = require('playwright');
const crypto = require('crypto');
const { execSync } = require('child_process');
const [ip, token, mailCmd, backendCmd] = process.argv.slice(2);
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
let debugPage; // bei einem Fehler wird der sichtbare Text dieser Seite ausgegeben
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
  const problems = []; globalThis.problems = problems;
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
  await p1.waitForSelector('#sidebar .me span');
  ok((await p1.textContent('#sidebar .me span')) === 'joshua', 'Login: angemeldet als joshua, Seitenmenü zeigt den Namen');

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
  await p1.goto(`${base}/`); await p1.waitForSelector('#step-password:not([hidden])');
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
  await p3.waitForSelector('#sidebar .me span');
  ok(true, 'Login: Anmeldung mit Wiederherstellungscode (E-Mail als Kennung)');
  await p3.screenshot({ path: process.env.SHOT_DIR ? process.env.SHOT_DIR + '/home.png' : undefined });

  // ---------- OIDC: ein Dienst meldet einen Nutzer an ----------
  const admin = (args) => execSync(`${backendCmd} auth-admin ${args}`, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  const redirect = `${base}/e2e-callback`;
  const reg = admin(`register-service e2e "E2E Dienst" ${redirect}`);
  const clientSecret = reg.match(/Client-Secret:\s+(\S+)/)[1];
  ok(/Client-ID:\s+e2e/.test(reg) && clientSecret.length === 43, 'auth-admin register-service im echten Container (ohne Webserver gestartet)');
  const reg2 = admin(`register-service gesperrt "Gesperrter Dienst" ${base}/gesperrt-callback`);
  ok(reg2.includes('Dienst angelegt'), 'auth-admin: zweiter Dienst, für den niemand das Recht hat');
  ok(admin('grant-service joshua e2e').includes('Zugang'), 'auth-admin grant-service: joshua darf den ersten Dienst');

  const verifier = crypto.randomBytes(32).toString('base64url');
  const challenge = crypto.createHash('sha256').update(verifier).digest('base64url');
  const authorize = (client, cb) => `${base}/oauth2/authorize?response_type=code&client_id=${client}&redirect_uri=${encodeURIComponent(cb)}` +
    `&scope=${encodeURIComponent('openid profile email offline_access')}&state=xyz&code_challenge=${challenge}&code_challenge_method=S256`;

  let { c: c4, p: p4 } = await fresh();
  await p4.goto(authorize('e2e', redirect));
  await p4.waitForSelector('#step-password:not([hidden])');
  ok(p4.url().includes('/login?continue=%2Foauth2%2Fauthorize%3F'), 'OIDC: nicht angemeldet, Weiterleitung zur Login-Seite mit Rücksprung-Adresse');
  await p4.fill('#identifier', 'joshua'); await p4.fill('#password', PW2); await p4.click('#step-password button[type=submit]');
  await p4.waitForSelector('#step-code:not([hidden])');
  await p4.fill('#code', recovery[1]); await p4.click('#step-code button[type=submit]');
  await p4.waitForURL(/e2e-callback\?/);
  const back = new URL(p4.url());
  ok(back.searchParams.get('state') === 'xyz' && back.searchParams.get('code'), 'OIDC: nach dem Login zurück zum Dienst, mit Code und state');
  const tokens = await p4.evaluate(async ({ code, verifier, redirect, clientSecret }) => {
    const r = await fetch('/oauth2/token', { method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', Authorization: 'Basic ' + btoa('e2e:' + clientSecret) },
      body: new URLSearchParams({ grant_type: 'authorization_code', code, redirect_uri: redirect, code_verifier: verifier }) });
    return { status: r.status, body: await r.json() };
  }, { code: back.searchParams.get('code'), verifier, redirect, clientSecret });
  ok(tokens.status === 200 && tokens.body.id_token && tokens.body.refresh_token, 'OIDC: Code gegen Tokens getauscht (ID-Token, Access-Token, Refresh-Token)');
  const claims = JSON.parse(Buffer.from(tokens.body.id_token.split('.')[1], 'base64url').toString());
  ok(claims.iss === base && claims.email === 'joshua@example.com' && claims.email_verified === true && claims.preferred_username === 'joshua' && /^[0-9a-f-]{36}$/.test(claims.sub),
    'OIDC: ID-Token mit iss, sub (UUID), email, email_verified, preferred_username');

  const info = await p4.evaluate(async (at) => { const r = await fetch('/userinfo', { headers: { Authorization: 'Bearer ' + at } }); return { status: r.status, body: await r.json() }; }, tokens.body.access_token);
  ok(info.status === 200 && info.body.email === 'joshua@example.com', 'OIDC: UserInfo liefert die Claims');
  const refresh = (rt) => p4.evaluate(async ({ rt, clientSecret }) => {
    const r = await fetch('/oauth2/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', Authorization: 'Basic ' + btoa('e2e:' + clientSecret) },
      body: new URLSearchParams({ grant_type: 'refresh_token', refresh_token: rt }) });
    return { status: r.status, body: await r.json() };
  }, { rt, clientSecret });
  const refreshed = await refresh(tokens.body.refresh_token);
  ok(refreshed.status === 200 && refreshed.body.refresh_token !== tokens.body.refresh_token, 'OIDC: Refresh funktioniert und rotiert den Refresh-Token');

  // Schlüsselrotation im echten Container: altes Token bleibt prüfbar, neue Tokens nutzen den neuen Schlüssel
  const jwks1 = await p4.evaluate(async () => (await (await fetch('/oauth2/jwks')).json()).keys.map(k => k.kid));
  ok(admin('rotate-signing-key').includes('Neuer Signierschlüssel'), 'auth-admin rotate-signing-key im echten Container');
  const jwks2 = await p4.evaluate(async () => (await (await fetch('/oauth2/jwks')).json()).keys.map(k => k.kid));
  const afterRotation = await refresh(refreshed.body.refresh_token);
  const kidOf = (jwt) => JSON.parse(Buffer.from(jwt.split('.')[0], 'base64url').toString()).kid;
  ok(jwks1.length === 1 && jwks2.length === 2 && jwks2.includes(jwks1[0]), 'OIDC: nach der Rotation stehen alter und neuer Schlüssel im JWKS');
  ok(afterRotation.status === 200 && kidOf(afterRotation.body.id_token) !== jwks1[0] && kidOf(tokens.body.id_token) === jwks1[0], 'OIDC: neue Tokens mit neuem Schlüssel, altes ID-Token trägt den alten');

  // Ein Dienst, für den das Recht fehlt: kein Code, verständliche Seite
  await p4.goto(authorize('gesperrt', `${base}/gesperrt-callback`));
  await p4.waitForSelector('#service');
  ok(p4.url().startsWith(`${base}/access-denied?service=`) && (await p4.textContent('#service')) === 'Gesperrter Dienst', 'OIDC: ohne Recht kein Code, Seite "Kein Zugang" mit Dienstnamen');
  ok((await p4.textContent('h1')) === 'No access', 'OIDC: Seite "Kein Zugang" in der Sprache der Seite (Englisch)');
  await p4.selectOption('#lang', 'de');
  await p4.waitForFunction(() => document.querySelector('h1').textContent === 'Kein Zugang');
  ok(true, 'OIDC: Seite "Kein Zugang" umschaltbar auf Deutsch');
  ok(!p4.url().includes('code='), 'OIDC: kein Code in der Adresse');
  await c4.close();

  // ---------- Admin-Dashboard und 2FA-Seite (p3 ist als joshua mit der Rolle Admin angemeldet) ----------
  // Ohne Anmeldung leitet /admin zum Login um und kehrt danach zurück
  let { c: c5, p: p5 } = await fresh();
  await p5.goto(`${base}/admin`);
  await p5.waitForSelector('#step-password:not([hidden])');
  ok(p5.url().includes('/login?continue=%2Fadmin'), 'Dashboard: ohne Anmeldung Weiterleitung zum Login mit Rücksprung');
  await p5.fill('#identifier', 'joshua'); await p5.fill('#password', PW2); await p5.click('#step-password button[type=submit]');
  await p5.waitForSelector('#step-code:not([hidden])');
  await p5.fill('#code', recovery[3]); await p5.click('#step-code button[type=submit]');
  await p5.waitForURL(`${base}/admin`);
  await p5.waitForSelector('#tabs .tab');
  ok(await p5.locator('#tabs .tab').count() === 7, 'Dashboard: Rücksprung nach dem Login, sieben Reiter für den Admin');
  // Sprache des Kontos auf der Profilseite umstellen: Seitenmenü und Dashboard folgen
  await p5.goto(`${base}/account`);
  await p5.waitForSelector('#profile-language');
  await p5.selectOption('#profile-language', 'de');
  await p5.waitForFunction(() => document.querySelector('#sidebar').textContent.includes('Dienste'));
  await p5.goto(`${base}/admin`);
  await p5.waitForFunction(() => [...document.querySelectorAll('#tabs .tab')].some((t) => t.textContent === 'Benutzer'));
  ok(true, 'Profil: Sprache des Kontos auf Deutsch, Dashboard folgt der Kontosprache');
  await p5.goto(`${base}/account`);
  await p5.selectOption('#profile-language', 'en');
  await p5.waitForFunction(() => document.querySelector('#sidebar').textContent.includes('Services'));
  await c5.close();

  debugPage = p3;
  await p3.goto(`${base}/admin`);
  await p3.waitForSelector('#panel table');
  ok((await p3.textContent('#panel tbody')).includes('joshua'), 'Dashboard: Benutzerliste zeigt joshua');
  await p3.click('#panel tbody button.link');
  await p3.waitForSelector('#panel h3');
  const detail = await p3.textContent('#panel');
  ok(detail.includes('Admin') && detail.includes('admin.users.view'), 'Dashboard: Detailansicht mit Rolle Admin und wirksamen Rechten');

  await p3.click('#tabs .tab:has-text("Roles")');
  await p3.waitForSelector('#panel .card-inner');
  await p3.fill('#panel form input[type=text]', 'Helpers'); await p3.click('#panel form button[type=submit]');
  await p3.waitForSelector('#panel .card-inner:has-text("Helpers")');
  ok(true, 'Dashboard: Rolle angelegt');
  await p3.selectOption('#panel .card-inner:has-text("Helpers") select', 'links.create');
  await p3.click('#panel .card-inner:has-text("Helpers") button:has-text("Set right")');
  await p3.waitForSelector('#panel .card-inner:has-text("Helpers") li:has-text("links.create")');
  ok(true, 'Dashboard: Recht in der Rolle gesetzt');

  await p3.click('#tabs .tab:has-text("Rights")');
  await p3.waitForSelector('#perm-key');
  ok((await p3.textContent('#panel .info')).includes('sign-in requirement'), 'Dashboard: Hinweis, dass ein Recht nur als Anmeldevoraussetzung wirkt');
  await p3.fill('#perm-key', 'media.upload'); await p3.fill('#perm-description', 'Upload'); await p3.click('#panel form button[type=submit]');
  await p3.waitForSelector('#panel tbody tr:has-text("media.upload")');
  ok(true, 'Dashboard: eigenes Recht angelegt');
  await p3.fill('#perm-key', 'admin.mine'); await p3.click('#panel form button[type=submit]');
  await p3.waitForFunction(() => document.querySelector('#notice').textContent.includes('reserved'));
  ok(true, 'Dashboard: reservierter Schlüssel abgelehnt, Fehlertext sichtbar');

  await p3.click('#tabs .tab:has-text("Log")');
  await p3.waitForSelector('#panel tbody tr');
  ok((await p3.textContent('#panel tbody')).includes('role.created'), 'Dashboard: Protokoll zeigt die neuen Einträge');

  await p3.goto(`${base}/account/2fa`);
  await p3.waitForSelector('#status:not(:empty)');
  ok((await p3.textContent('#status')).includes('is on'), '2FA-Seite: Status eingeschaltet');
  ok(!(await p3.isHidden('#mandatory')) && await p3.isHidden('#disable-form'), '2FA-Seite: Admin sieht den Pflicht-Hinweis, Abschalten fehlt');
  await p3.fill('#regen-password', PW2); await p3.fill('#regen-code', recovery[2]); await p3.click('#regen-form button[type=submit]');
  await p3.waitForSelector('#codes li');
  const fresh10 = await p3.locator('#codes li').allTextContents();
  ok(fresh10.length === 10 && !fresh10.includes(recovery[4]), '2FA-Seite: neue Wiederherstellungscodes erzeugt');
  // ---------- Hub, Dienste und Akzentfarbe pro Dienst (Etappe 6) ----------
  await p3.goto(`${base}/admin`);
  await p3.click('#tabs .tab:has-text("Services")');
  await p3.waitForSelector('#e2e-hex');
  await p3.fill('#e2e-url', 'https://dienst.example/');
  await p3.fill('#e2e-desc', 'Test-Dienst');
  await p3.fill('#e2e-hex', '#e5a00d');
  await p3.click('#panel .card-inner:has(#e2e-hex) button:has-text("Save")');
  await p3.waitForFunction(() => document.querySelector('#notice').textContent.includes('Saved'));
  ok(true, 'Dienste: Adresse, Beschreibung und Akzentfarbe im Dashboard gespeichert');
  await p3.fill('#e2e-hex', 'rot'); await p3.click('#panel .card-inner:has(#e2e-hex) button:has-text("Save")');
  await p3.waitForFunction(() => document.querySelector('#notice').textContent.includes('six hex digits'));
  ok(true, 'Dienste: ungültige Farbe wird vom Backend abgelehnt');

  await p3.goto(`${base}/`);
  await p3.waitForSelector('#services tr');
  ok(await p3.locator('#services tr').count() === 1 && (await p3.textContent('#services')).includes('E2E Dienst'), 'Hub: nur der Dienst mit Recht erscheint (der gesperrte nicht)');
  const btn = p3.locator('#services a.btn.svc');
  const colors = await btn.evaluate((el) => { const c = getComputedStyle(el); return { bg: c.backgroundColor, fg: c.color, href: el.href, target: el.target }; });
  ok(colors.bg === 'rgb(229, 160, 13)' && colors.fg === 'rgb(13, 13, 13)', 'Hub: Knopf des Dienstes hat die eingestellte Farbe mit lesbarer Schrift (' + colors.bg + ', ' + colors.fg + ')');
  ok(colors.href === 'https://dienst.example/' && colors.target === '_blank', 'Hub: Dienst öffnet in neuem Tab');
  ok((await p3.textContent('#sidebar nav')).includes('E2E Dienst'), 'Hub: Seitenmenü listet den Dienst');
  ok((await p3.textContent('#stat-services')) === '1', 'Hub: Kennzahlen');
  // Darstellung ist eine Einstellung des Kontos (Profilseite), es gibt keinen Umschalter auf jeder Seite
  ok(await p3.locator('[data-set-theme]').count() === 0, 'Darstellung: kein Umschalter auf der Startseite');
  await p3.goto(`${base}/account`);
  await p3.waitForSelector('#profile-theme');
  await p3.selectOption('#profile-theme', 'light');
  await p3.waitForSelector('#theme-saved:not([hidden])');
  ok(await p3.getAttribute('html', 'data-theme') === 'light', 'Darstellung: Einstellung im Profil wirkt sofort');
  await p3.goto(`${base}/`); await p3.waitForSelector('#services tr');
  ok(await p3.getAttribute('html', 'data-theme') === 'light', 'Darstellung: bleibt auf anderen Seiten und nach dem Neuladen');
  const lightBtn = await p3.locator('#services a.btn.svc').evaluate((el) => getComputedStyle(el).backgroundColor);
  ok(lightBtn === 'rgb(229, 160, 13)', 'Hub: Dienstfarbe gilt in beiden Darstellungen');

  // Farbe vom Dienst überschreibt die Hub-Farbe, außer sie ist festgehalten (Kästchen im Dashboard)
  const dbCmd = backendCmd.replace('auth-backend', 'auth-db');
  const sql = (q) => execSync(`${dbCmd} psql -U auth -d auth -c "${q}"`, { encoding: 'utf8' });
  const btnColor = async () => { await p3.reload(); await p3.waitForSelector('#services tr'); return p3.locator('#services a.btn.svc').evaluate((el) => getComputedStyle(el).backgroundColor); };
  sql("UPDATE services SET reported_accent_color = '#22aa55' WHERE key = 'e2e'");
  ok(await btnColor() === 'rgb(34, 170, 85)', 'Farbe: vom Dienst gemeldete Farbe überschreibt die Hub-Farbe');
  await p3.goto(`${base}/admin`);
  await p3.click('#tabs .tab:has-text("Services")');
  await p3.waitForSelector('#e2e-lock');
  ok((await p3.textContent('#panel .card-inner:has(#e2e-lock)')).includes('#22aa55'), 'Dashboard: zeigt die vom Dienst gemeldete Farbe');
  await p3.check('#e2e-lock');
  await p3.click('#panel .card-inner:has(#e2e-lock) button:has-text("Save")');
  await p3.waitForFunction(() => document.querySelector('#notice').textContent.includes('Saved'));
  await p3.goto(`${base}/`);
  ok(await btnColor() === 'rgb(229, 160, 13)', 'Farbe: mit gesetztem Kästchen bleibt die Hub-Farbe');
  await p3.goto(`${base}/account`); await p3.waitForSelector('#profile-theme');
  await p3.selectOption('#profile-theme', 'dark'); await p3.waitForSelector('#theme-saved:not([hidden])');
  await p3.goto(`${base}/`); await p3.waitForSelector('#services tr');
  if (process.env.SHOT_DIR) await p3.screenshot({ path: process.env.SHOT_DIR + '/hub.png', fullPage: true });

  // ---------- Dienst im Dashboard anlegen und die Dienst-Schnittstelle benutzen ----------
  await p3.goto(`${base}/admin`);
  await p3.click('#tabs .tab:has-text("Services")');
  await p3.waitForSelector('#new-service');
  await p3.click('#new-service summary');
  await p3.fill('#new-key', 'media'); await p3.fill('#new-name', 'Medien'); await p3.fill('#new-redirects', 'https://media.example/cb');
  await p3.click('#new-service button[type=submit]');
  await p3.waitForSelector('#secret-value');
  const mediaSecret = (await p3.textContent('#secret-value')).trim();
  ok(mediaSecret.length === 43 && (await p3.textContent('#secret-client-id')) === 'media', 'Dienste: Dienst im Dashboard angelegt, Client-ID und Geheimnis werden einmal angezeigt');
  await p3.click('#tabs .tab:has-text("Users")'); await p3.click('#tabs .tab:has-text("Services")'); await p3.waitForSelector('#service-media');
  ok(!(await p3.content()).includes(mediaSecret), 'Dienste: Das Geheimnis ist danach nirgends mehr zu sehen');
  ok((await p3.inputValue('#media-redirects')) === 'https://media.example/cb', 'Dienste: Rücksprung-Adresse steht im Dienst');
  await p3.fill('#media-redirects', 'https://media.example/cb\nhttps://media.example/zwei');
  await p3.click('#service-media button:has-text("Save redirect addresses")');
  await p3.waitForFunction(() => document.querySelector('#notice').textContent.includes('Saved'));
  ok(true, 'Dienste: Rücksprung-Adressen geändert');
  await p3.fill('#media-redirects', 'javascript:alert(1)');
  await p3.click('#service-media button:has-text("Save redirect addresses")');
  await p3.waitForFunction(() => document.querySelector('#notice').textContent.length > 0 && !document.querySelector('#notice').textContent.includes('Saved'));
  ok(true, 'Dienste: ungültige Rücksprung-Adresse wird abgelehnt');

  // Der Dienst holt sich ein Token (client_credentials) und nutzt die Dienst-Schnittstelle
  const form = (o) => new URLSearchParams(o).toString();
  const svc = (path, init, token) => p3.evaluate(async ({ path, init, token }) => {
    const r = await fetch(path, { ...init, headers: { ...(init && init.headers), Authorization: 'Bearer ' + token } });
    let body = null; try { body = await r.json(); } catch (e) { /* leer */ }
    return { status: r.status, body };
  }, { path, init, token });
  const tokenRes = await p3.evaluate(async ({ secret }) => {
    const r = await fetch('/oauth2/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', Authorization: 'Basic ' + btoa('media:' + secret) },
      body: 'grant_type=client_credentials&scope=service' });
    return { status: r.status, body: await r.json() };
  }, { secret: mediaSecret });
  ok(tokenRes.status === 200 && tokenRes.body.access_token && !tokenRes.body.refresh_token, 'Dienst-Schnittstelle: Token per client_credentials');
  const serviceToken = tokenRes.body.access_token;
  let perms = await svc(`/api/service/users/${claims.sub}/permissions`, {}, serviceToken);
  ok(perms.status === 200 && perms.body.access === false, 'Dienst-Schnittstelle: ohne Recht auf den Dienst ist access false');
  ok(admin('grant-service joshua media').includes('Zugang'), 'auth-admin grant-service für den neuen Dienst');
  // media.upload gibt es seit dem Dashboard-Test oben schon
  sql("INSERT INTO permissions (key, type, builtin) VALUES ('vault.nicht', 'boolean', false)");
  sql("INSERT INTO user_permissions (user_id, permission_key) SELECT u.id, k FROM users u, (VALUES ('media.upload'), ('vault.nicht')) v(k) WHERE u.username = 'joshua'");
  perms = await svc(`/api/service/users/${claims.sub}/permissions`, {}, serviceToken);
  ok(perms.body.access === true && perms.body.permissions.length === 1 && perms.body.permissions[0].key === 'media.upload', 'Dienst-Schnittstelle: Rechte des Users nur aus dem eigenen Namensraum (media.*)');
  const other = await svc(`/api/service/users/${claims.sub}/permissions?prefix=vault.`, {}, serviceToken);
  ok(other.status === 400, 'Dienst-Schnittstelle: fremder Namensraum wird abgelehnt');
  const noToken = await svc('/api/service/self', {}, 'kaputt');
  ok(noToken.status === 401, 'Dienst-Schnittstelle: ungültiges Token gibt 401');
  const reportRes = await svc('/api/service/self', { method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ accentColor: '#22aa55', status: { state: 'up', ttlSeconds: 300 } }) }, serviceToken);
  ok(reportRes.status === 200, 'Dienst-Schnittstelle: Dienst meldet Farbe und Status');
  await p3.goto(`${base}/`); await p3.waitForSelector('#services tr:has-text("Medien")');
  const medienRow = await p3.locator('#services tr:has-text("Medien")').evaluate((el) => ({ swatch: getComputedStyle(el.querySelector('.svc-swatch')).backgroundColor, tag: el.querySelector('.tag').textContent }));
  ok(medienRow.swatch === 'rgb(34, 170, 85)' && /online/i.test(medienRow.tag), 'Hub: gemeldete Farbe und gemeldeter Status (online) erscheinen im Hub (' + medienRow.swatch + ')');

  // ---------- Einladungslinks (Etappe 5) ----------
  // joshua bekommt über das Dashboard die Rechte zum Erstellen von Links (die Rolle Admin gibt sie nicht von selbst)
  await p3.goto(`${base}/admin`);
  await p3.waitForSelector('#panel table');
  await p3.click('#panel tbody button.link');
  await p3.waitForSelector('#panel h3');
  const direct = '#panel section:has(h3:has-text("Direct rights"))';
  await p3.selectOption(`${direct} select`, 'links.create');
  await p3.click(`${direct} button:has-text("Set right")`);
  await p3.waitForSelector(`${direct} li:has-text("links.create")`);
  await p3.selectOption(`${direct} select`, 'links.max_active');
  await p3.fill(`${direct} input[type=number]`, '5');
  await p3.click(`${direct} button:has-text("Set right")`);
  await p3.waitForSelector(`${direct} li:has-text("links.max_active: 5")`);
  ok(true, 'Links: Rechte links.create und links.max_active über das Dashboard vergeben');

  await p3.goto(`${base}/account/links`);
  await p3.waitForSelector('#create-form:not([hidden])');
  ok((await p3.textContent('#usage')).includes('0') && (await p3.textContent('#usage')).includes('5'), 'Links: Seite zeigt offene Links und Limit');
  await p3.click('#create-form button[type=submit]');
  await p3.waitForSelector('#created:not([hidden])');
  const inviteUrl = await p3.textContent('#created-url');
  ok(/^https:\/\/auth\.test\/invite\/[A-Za-z0-9_-]{43}$/.test(inviteUrl), 'Links: neuer Link wird einmal angezeigt');
  await p3.waitForSelector('#list tbody tr');
  ok((await p3.textContent('#list tbody')).includes('active'), 'Links: Liste zeigt den Link als aktiv');

  const mailsBefore = mailbox().messages.length;
  let { c: c6, p: p6 } = await fresh();
  await p6.goto(inviteUrl);
  await p6.waitForSelector('#step-form:not([hidden])');
  ok(true, 'Registrierung: Link geöffnet, Formular erscheint (Öffnen verbraucht nicht)');
  await p6.goto(inviteUrl);
  await p6.waitForSelector('#step-form:not([hidden])');
  await p6.fill('#username', 'joshua'); await p6.fill('#email', 'x@example.com');
  await p6.fill('#password', PW2); await p6.fill('#password2', PW2); await p6.click('#step-form button[type=submit]');
  await p6.waitForFunction(() => document.querySelector('#error').textContent.length > 0);
  ok((await p6.textContent('#error')).includes('already taken'), 'Registrierung: vergebener Username gibt Formularfehler, Link bleibt gültig');
  await p6.fill('#username', 'Anna'); await p6.fill('#email', 'Anna@Example.com');
  await p6.click('#step-form button[type=submit]');
  await p6.waitForSelector('#step-done:not([hidden])');
  ok(true, 'Registrierung: Konto angelegt, Hinweis auf die Bestätigungsmail');
  await p6.goto(inviteUrl);
  await p6.waitForSelector('#state-invalid:not([hidden])');
  ok(true, 'Registrierung: benutzter Link zeigt die neutrale Fehlerseite');

  const newMails = await waitForMail(mailsBefore + 1);
  const confirmMail = mailText(newMails[0].ID);
  ok(confirmMail.To[0].Address === 'anna@example.com', 'Registrierung: Bestätigungsmail an die neue Adresse');
  await p6.goto(confirmMail.Text.match(/https:\/\/auth\.test\/confirm\/[A-Za-z0-9_-]+/)[0]);
  await p6.waitForSelector('#step-confirm:not([hidden])');
  await p6.click('#confirm');
  await p6.waitForSelector('#step-done:not([hidden])');
  await loginPage(p6);
  await p6.fill('#identifier', 'anna'); await p6.fill('#password', PW2); await p6.click('#step-password button[type=submit]');
  await p6.waitForSelector('#sidebar .me span');
  ok((await p6.textContent('#sidebar .me span')) === 'anna', 'Registrierung: nach der Bestätigung Anmeldung als anna');
  await p6.goto(`${base}/account/links`);
  await p6.waitForSelector('#cannot:not([hidden])');
  ok(true, 'Links: anna hat kein Recht zum Erstellen, die Seite sagt es');
  await c6.close();

  await p3.goto(`${base}/admin#links`);
  await p3.waitForSelector('#panel tbody tr');
  ok((await p3.textContent('#panel tbody')).includes('anna') && (await p3.textContent('#panel tbody')).includes('used'), 'Dashboard: Reiter Links zeigt den benutzten Link mit anna');

  // IP-Sperre: vier falsche Links, dann ist die Adresse gesperrt, ein Admin hebt es auf
  let { c: c7, p: p7 } = await fresh();
  for (let i = 1; i <= 4; i++) { await p7.goto(`${base}/invite/falsch${i}`); await p7.waitForSelector('#state-invalid:not([hidden])'); }
  await p7.goto(`${base}/invite/falsch5`);
  await p7.waitForSelector('#state-blocked:not([hidden])');
  ok(true, 'IP-Sperre: nach vier falschen Links ist die Adresse für Links gesperrt');
  await loginPage(p7);
  ok(true, 'IP-Sperre: der Login ist nicht betroffen');
  await p3.click('#tabs .tab:has-text("Settings")');
  await p3.waitForSelector('#panel li button:has-text("Lift block")');
  await p3.click('#panel li button:has-text("Lift block")');
  await p3.waitForSelector('#panel :text("No address is blocked.")');
  await p7.goto(`${base}/invite/falsch6`);
  await p7.waitForSelector('#state-invalid:not([hidden])');
  ok(true, 'IP-Sperre: Admin hebt sie im Dashboard auf');
  await c7.close();

  await p3.goto(`${base}/`); await p3.waitForSelector('#sidebar .me span');

  // fetch im Browser: Playwrights eigener Request-Client kennt die Host-Zuordnung für auth.test nicht
  const get = (u) => p3.evaluate(async (url) => { const r = await fetch(url); return { s: r.status, h: Object.fromEntries(r.headers) }; }, u);
  // ---------- Eingebetteter Dienst (Öffnungsmodus embedded) ----------
  sql("UPDATE services SET open_mode = 'embedded' WHERE key = 'e2e'");
  await p3.route('https://dienst.example/**', (route) => route.fulfill({ status: 200, contentType: 'text/html', body: '<p id="in-frame">eingebettet</p>' }));
  await p3.goto(`${base}/`); await p3.waitForSelector('#services tr');
  const sideHref = await p3.locator('#sidebar a.service').first().evaluate((el) => ({ href: el.getAttribute('href'), target: el.getAttribute('target') }));
  const rowHref = await p3.locator('#services a.btn.svc').evaluate((el) => ({ href: el.getAttribute('href'), target: el.getAttribute('target') }));
  ok(sideHref.href === '/app/e2e' && !sideHref.target && rowHref.href === '/app/e2e' && !rowHref.target, 'Einbetten: Seitenmenü und Knopf führen zu /app/e2e statt in einen neuen Tab');
  await p3.click('#sidebar a.service');
  await p3.waitForURL(`${base}/app/e2e`);
  await p3.waitForSelector('iframe.embed-frame');
  const fr = await p3.locator('iframe.embed-frame').evaluate((el) => ({ src: el.getAttribute('src'), sandbox: el.getAttribute('sandbox'), title: el.title }));
  ok(fr.src === 'https://dienst.example/' && fr.title === 'E2E Dienst' && fr.sandbox.includes('allow-same-origin') && !fr.sandbox.includes('allow-top-navigation'),
    'Einbetten: Rahmen mit Adresse des Dienstes, sandbox ohne allow-top-navigation');
  await p3.frameLocator('iframe.embed-frame').locator('#in-frame').waitFor();
  ok(true, 'Einbetten: Inhalt des Dienstes erscheint im Rahmen');
  const eh = (await get(`${base}/app/e2e`)).h;
  ok((eh['content-security-policy'] || '').includes('frame-src https:') && (eh['content-security-policy'] || '').includes("frame-ancestors 'none'") && eh['x-frame-options'] === 'DENY',
    'Einbetten: Seite erlaubt https-Rahmen, ist selbst aber nicht einbettbar');
  sql("UPDATE services SET open_mode = 'new_tab' WHERE key = 'e2e'");
  await p3.goto(`${base}/app/e2e`);
  await p3.waitForURL(`${base}/`);
  ok(true, 'Einbetten: ein Dienst im Modus neuer Tab wird nicht geladen (zurück zur Startseite)');
  await p3.unroute('https://dienst.example/**');

  // ---------- Abmelden ----------
  await p3.click('#sidebar .me button');
  await p3.waitForURL(`${base}/login`);
  await p3.goto(`${base}/`); await p3.waitForSelector('#step-password:not([hidden])');
  ok(true, 'Abmelden: Sitzung beendet, die Startseite führt zum Login');

  // ---------- Setup-Link ist tot, Header ----------
  await page.goto(`${base}/login`);
  await page.waitForSelector('#step-password:not([hidden])');
  ok((await get(`${base}/api/setup/${token}`)).s === 404, 'Setup-Link nach dem Admin tot');
  const h = (await get(`${base}/login`)).h;
  ok((h['content-security-policy'] || '').includes("frame-ancestors 'none'") && h['cache-control'] === 'no-store', 'Login-Seite: frame-ancestors none, no-store');
  // Sicherheits-Header aller Seiten der UI (frame-ancestors none, nosniff, kein Referrer)
  for (const path of ['/', '/login', '/forgot', '/access-denied', '/admin', '/account', '/account/2fa', '/account/links', '/invite/abc', '/reset/abc', '/confirm/abc']) {
    const hh = (await get(`${base}${path}`)).h;
    const good = (hh['content-security-policy'] || '').includes("frame-ancestors 'none'") && (hh['content-security-policy'] || '').includes("default-src 'none'")
      && hh['x-frame-options'] === 'DENY' && hh['x-content-type-options'] === 'nosniff' && hh['referrer-policy'] === 'no-referrer';
    ok(good, 'Header: ' + path + ' mit CSP, frame-ancestors none, nosniff, no-referrer');
  }
  ok(((await get(`${base}/login`)).h['strict-transport-security'] || '').includes('max-age=31536000'), 'Header: HSTS kommt von Caddy (aus deploy/Caddyfile.snippet)');
  const apiH = (await get(`${base}/api/auth/me`)).h;
  ok(!apiH['access-control-allow-origin'] && apiH['x-content-type-options'] === 'nosniff', 'Header: API ohne CORS-Freigabe, mit nosniff');
  ok(problems.length === 0, 'keine Konsolenfehler / CSP-Verstöße ' + JSON.stringify(problems));
  await browser.close();
})().catch(async (e) => {
  console.log('FAIL: Ausnahme', e.message);
  console.log('Fehler im Browser:', JSON.stringify(globalThis.problems || []));
  if (debugPage) console.log('Seite zur Zeit des Fehlers:', await debugPage.evaluate(() => document.body.innerText.slice(0, 2500)).catch(() => '?'));
  process.exit(1);
});
