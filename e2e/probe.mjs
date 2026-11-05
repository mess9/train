import { chromium, devices } from '@playwright/test';
const b = await chromium.launch();
const ctx = await b.newContext({ ...devices['Pixel 7'], locale: 'ru-RU' });
const p = await ctx.newPage();
p.on('request', r => { if (r.url().includes('/api/')) console.log('REQ', r.method(), r.url(), JSON.stringify(r.headers()['x-api-key'])); });
p.on('console', m => { if (m.type() === 'error') console.log('CONSOLE', m.text()); });
await p.goto('http://localhost:7413/');
const dump = async () => p.evaluate(() => {
  const host = [...document.querySelectorAll('div')].find(e => e.shadowRoot);
  const root = host.shadowRoot.getElementById('cmp_a11y_root');
  const out = [];
  (function walk(e, d) {
    const r = e.getBoundingClientRect();
    const a = [...e.attributes].filter(x => x.name !== 'style').map(x => x.name + '=' + x.value).join(' ');
    const t = [...e.childNodes].filter(n => n.nodeType === 3).map(n => n.textContent).join('');
    if (a || t) out.push(' '.repeat(d) + a + ' ' + JSON.stringify(t) + ` @${r.x|0},${r.y|0} ${r.width|0}x${r.height|0}`);
    [...e.children].forEach(c => walk(c, d + 1));
  })(root, 0);
  return out.join('\n');
});
const tap = async (loc) => { const bb = await loc.boundingBox(); await p.mouse.click(bb.x + bb.width / 2, bb.y + bb.height / 2); };
await p.getByRole('textbox').waitFor();
await tap(p.getByRole('textbox'));
await p.keyboard.type('e2e-key');
await tap(p.getByRole('button', { name: 'Подключить' }));
await p.waitForTimeout(6000); console.log(await p.evaluate(() => Object.keys(localStorage).join(',')));
await p.screenshot({ path: 'scratch/home.png' }); console.log(await dump());
const steps = process.argv.slice(2);
for (const s of steps) {
  console.log('==== tap', s);
  await tap(s.startsWith('b:') ? p.getByRole('button', { name: new RegExp(s.slice(2)) }).first() : p.getByText(s, { exact: true }).first());
  await p.waitForTimeout(1500);
  console.log(await dump());
}
await p.screenshot({ path: 'scratch/last.png' });
await b.close();
