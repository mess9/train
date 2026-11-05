// Помощники e2e: агент (CLI `gym` и HTTP) и владелец (PWA на canvas через a11y-дерево Compose, ADR 0012)
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { expect } from '@playwright/test';
import { root } from './stand.js';

export const url = () => process.env.E2E_URL;
export const key = () => process.env.E2E_KEY;

// ---------- Агент ----------

const tmp = mkdtempSync(join(tmpdir(), 'train-e2e-'));
let n = 0;

/** Файл JSON для `gym … put <файл>`. */
export function jsonFile(value) {
  const f = join(tmp, `${++n}.json`);
  writeFileSync(f, JSON.stringify(value), 'utf8');
  return f;
}

/**
 * `gym <args>` — тот же CLI, что у агента: собранный `:cli:installDist`, запущенный прямо через java
 * (без .bat: аргументы с кириллицей и пробелами без шелла). Вывод — JSON.
 */
export function gym(...args) {
  const out = execFileSync('java', [
    '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Dsun.jnu.encoding=UTF-8', '--enable-native-access=ALL-UNNAMED',
    '-cp', join(root, 'cli/build/install/gym/lib/*'), 'train.cli.MainKt', ...args,
  ], {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, GYM_URL: url(), GYM_API_KEY: key(), GYM_CONFIG: join(tmp, 'none') },
  });
  return out.trim() ? JSON.parse(out) : null;
}

/** Сегодня по часам машины — так же считает и браузер теста. */
export function today() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

// ---------- Владелец: телефон ----------

/**
 * Compose рисует в canvas, но выставляет семантику в DOM: shadow root → `#cmp_a11y_root`, узлы
 * с ролями, текстами, `aria-label` и настоящими координатами. Слой прозрачный и не ловит нажатия:
 * находим узел, жмём мышью в центр его рамки — нажатие получает canvas, как от пальца.
 */
export class Phone {
  constructor(page) {
    this.page = page;
  }

  async open() {
    await this.page.goto(url() + '/');
  }

  /** Все узлы семантики: роль, подпись, свой текст, рамка. */
  async nodes() {
    return this.page.evaluate(() => {
      const host = [...document.querySelectorAll('div')].find((e) => e.shadowRoot);
      const top = host?.shadowRoot.getElementById('cmp_a11y_root');
      if (!top) return [];
      return [...top.querySelectorAll('*')].map((e) => {
        const r = e.getBoundingClientRect();
        const own = [...e.childNodes].filter((c) => c.nodeType === 3).map((c) => c.textContent).join('');
        return {
          role: e.getAttribute('role') ?? '',
          label: e.getAttribute('aria-label') ?? '',
          text: own,
          all: e.textContent,
          x: r.x, y: r.y, w: r.width, h: r.height,
        };
      }).filter((n) => n.w > 0 && n.h > 0);
    });
  }

  /** Узлы, когда дерево перестало меняться (два одинаковых снимка подряд, до 3 с). */
  async settled() {
    let prev = '';
    for (let i = 0; i < 15; i++) {
      const ns = await this.nodes();
      const now = JSON.stringify(ns);
      if (now === prev) return ns;
      prev = now;
      await this.page.waitForTimeout(200);
    }
    return JSON.parse(prev);
  }

  /** Снимок экрана текстом — в отчёт упавшего теста. */
  async dump() {
    return (await this.nodes()).map((n) => `${n.role}${n.label ? `{${n.label}}` : ''} ${JSON.stringify(n.text || (n.role ? n.all : ''))} @${n.x | 0},${n.y | 0} ${n.w | 0}x${n.h | 0}`).join('\n');
  }

  async tapAt(x, y) {
    await this.page.mouse.click(x, y);
    await this.page.waitForTimeout(150);
  }

  /** Нажать узел. Узел за краем экрана (рамка 0×0) — сначала прокрутить к нему. */
  async tap(locator) {
    const box = await this.reveal(locator);
    await this.tapAt(box.x + box.width / 2, box.y + box.height / 2);
  }

  /** Прокрутка колёсиком, пока узел не окажется на экране целиком; рамка узла. */
  async reveal(locator, timeout = 15_000) {
    const view = this.page.viewportSize();
    const end = Date.now() + timeout;
    let dir = 1;
    let tries = 0;
    let last = null;
    for (;;) {
      const box = await locator.boundingBox({ timeout: 2_000 }).catch(() => null);
      // Узел виден целиком и рамка не сдвинулась за 200 мс: семантику Compose обновляет с
      // запаздыванием, после прокрутки и перерисовки координаты какое-то время старые
      const fits = box && box.height > 4 && box.y >= 0 && box.y + box.height <= view.height;
      if (fits && last && Math.abs(last.x - box.x) < 1 && Math.abs(last.y - box.y) < 1) return box;
      last = fits ? box : null;
      if (fits) {
        if (Date.now() > end) return box;
        await this.page.waitForTimeout(200);
        continue;
      }
      if (Date.now() > end) {
        // Полный снимок — в файл: в сообщении об ошибке он обрезан
        mkdirSync(join(root, 'e2e/test-results'), { recursive: true });
        writeFileSync(join(root, 'e2e/test-results/last-a11y.txt'), await this.dump());
        throw new Error(`не нашёл на экране: ${locator} (снимок — e2e/test-results/last-a11y.txt)`);
      }
      if (box && box.height > 0) dir = box.y < view.height / 2 ? -1 : 1;
      else if (++tries % 8 === 0) dir = -dir;
      await this.page.mouse.move(view.width / 2, view.height / 2);
      await this.page.mouse.wheel(0, dir * 250);
      await this.page.waitForTimeout(200);
    }
  }

  button(name) {
    return this.page.getByRole('button', { name, exact: typeof name === 'string' });
  }

  text(t) {
    return this.page.getByText(t, { exact: typeof t === 'string' });
  }

  /**
   * Текст на экране. Подстрокой: тексты внутри нажимаемого (лист, карточка) Compose сливает в один
   * узел — «Удалить подходГоблет-присед · …».
   */
  async see(t) {
    await expect(this.page.getByText(t).first()).toBeVisible();
  }

  async gone(t) {
    await expect(this.page.getByText(t)).toHaveCount(0);
  }

  async press(name) {
    await this.tap(this.button(name).last());
  }

  /** Поле ввода: стереть и набрать. `clear` — сколько раз Backspace (поле времени держит курсор в конце). */
  async fill(locator, value, clear = 8) {
    await this.tap(locator);
    for (let i = 0; i < clear; i++) await this.page.keyboard.press('Backspace');
    await this.page.keyboard.type(String(value), { delay: 30 });
    await this.page.waitForTimeout(150);
  }

  /**
   * Строки подходов карточки упражнения: всё между её кебабом «Меню: <название>» и следующей
   * карточкой или пунктирной кнопкой. Строка — поля по порядку и кнопки оценок.
   */
  async rows(exercise) {
    await this.reveal(this.button(`Меню: ${exercise}`));
    const ns = await this.settled();
    const menu = ns.find((n) => n.label === `Меню: ${exercise}`);
    const stop = ns.filter((n) => n.y > menu.y + 10 && n.role === 'button' && (n.label.startsWith('Меню: ') || n.all.startsWith('+ ')))
      .reduce((m, n) => Math.min(m, n.y), Infinity);
    const inside = ns.filter((n) => n.y > menu.y + menu.h && n.y < stop);
    const rows = new Map();
    for (const n of inside) {
      if (n.role !== 'textbox' && !(n.role === 'button' && ['Легко', 'Норм', 'Тяжело'].includes(n.all))) continue;
      const k = Math.round(n.y / 10);
      if (!rows.has(k)) rows.set(k, { y: n.y, fields: [], rate: {} });
      const r = rows.get(k);
      if (n.role === 'textbox') r.fields.push(n); else r.rate[n.all] = n;
    }
    return [...rows.values()].sort((a, b) => a.y - b.y).map((r) => ({ ...r, fields: r.fields.sort((a, b) => a.x - b.x) }));
  }

  /** Строка подхода, которую карточка показывает первой: в свёрнутой — следующий подход. */
  async row(exercise, i = 0) {
    const rows = await this.rows(exercise);
    if (!rows[i]) throw new Error(`у «${exercise}» нет строки ${i}: ${rows.length}\n${await this.dump()}`);
    return rows[i];
  }

  async rate(exercise, rating, i = 0) {
    const r = await this.row(exercise, i);
    const b = r.rate[rating];
    await this.tapAt(b.x + b.w / 2, b.y + b.h / 2);
  }

  /** Поле строки подхода: `field` — номер поля слева направо (кг, повт / повт, +кг / время). */
  async set(exercise, field, value, i = 0, clear = 8) {
    const f = (await this.row(exercise, i)).fields[field];
    await this.tapAt(f.x + f.w / 2, f.y + f.h / 2);
    for (let k = 0; k < clear; k++) await this.page.keyboard.press('Backspace');
    await this.page.keyboard.type(String(value), { delay: 30 });
    await this.page.waitForTimeout(150);
  }

  async fieldText(exercise, field, i = 0) {
    return (await this.row(exercise, i)).fields[field].all;
  }

  /**
   * Поле ввода под заголовком `label`: стереть и набрать. По геометрии — у сфокусированного поля
   * Compose держит в дереве ещё и узел курсора (`textbox` шириной 1), `getByRole('textbox')` его
   * тоже находит.
   */
  async fillBelow(label, value, clear = 8) {
    await this.reveal(this.text(label));
    const ns = await this.settled();
    const head = ns.find((n) => n.text === label || n.all === label);
    const f = ns.filter((n) => n.role === 'textbox' && n.w > 20 && n.y > head.y).sort((a, b) => a.y - b.y)[0];
    if (!f) throw new Error(`нет поля под «${label}»\n${await this.dump()}`);
    await this.tapAt(f.x + f.w / 2, f.y + f.h / 2);
    for (let i = 0; i < clear; i++) await this.page.keyboard.press('Backspace');
    await this.page.keyboard.type(String(value), { delay: 30 });
    await this.page.waitForTimeout(150);
  }

  /** Пункт меню упражнения (кебаб). */
  async menu(exercise, item) {
    await this.tap(this.button(`Меню: ${exercise}`));
    await this.tap(this.button(item).last());
  }
}
