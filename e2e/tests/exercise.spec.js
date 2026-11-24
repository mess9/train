import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { Buffer } from 'node:buffer';
import { Phone, today, key, url } from '../lib.js';

// Собственный аккаунт: большая история не меняет рекорды сценариев других файлов.
async function fixture(request) {
  const created = await request.post(`${url()}/api/admin/users`, {
    headers: { 'X-Api-Key': key() }, data: { email: `exercise-${randomUUID()}@e2e.local` },
  });
  expect(created.ok(), await created.text()).toBe(true);
  const ownKey = (await created.json()).key.key;
  const headers = { 'X-Api-Key': ownKey };
  const json = async response => {
    expect(response.ok(), await response.text()).toBe(true);
    return response.json();
  };
  const exercise = async (name, type, extra = {}) => json(await request.post(`${url()}/api/exercises`, {
    headers, data: { nameRu: name, recordType: type, ...extra },
  }));
  const at = days => {
    const date = new Date(); date.setHours(10, 0, 0, 0); date.setDate(date.getDate() - days);
    return date.toISOString();
  };
  const session = async (ex, days, values, marks = {}, type = ex.recordType) => {
    const id = randomUUID(), blockId = randomUUID(), itemId = randomUUID();
    const startedAt = at(days), finishedAt = new Date(Date.parse(startedAt) + 1200000).toISOString();
    await json(await request.put(`${url()}/api/workouts/${id}`, { headers, data: {
      data: { id, startedAt, finishedAt, layout: { blocks: [{ id: blockId, role: 'MAIN', items: [{
        id: itemId, exerciseId: ex.id, recordType: type, perSide: false, sets: [], ...marks,
      }] }] } }, baseRev: 0, opId: randomUUID(),
    } }));
    for (const [index, value] of values.entries()) {
      const setId = randomUUID();
      await json(await request.put(`${url()}/api/workouts/${id}/sets/${setId}`, { headers, data: {
        data: { id: setId, workoutId: id, blockId, itemId, setNo: index + 1, exerciseId: ex.id,
          recordType: type, perSide: false, done: true, doneAt: finishedAt, ...value }, baseRev: 0, opId: randomUUID(),
      } }));
    }
    return id;
  };
  const plan = async (title, blocks) => json(await request.put(`${url()}/api/plans/${randomUUID()}`, {
    headers, data: { title, date: today(), body: { blocks } },
  }));
  return { ownKey, headers, json, exercise, session, plan };
}

function controls(page, phone) {
  const top = async () => {
    await page.mouse.move(180, 400); await page.mouse.wheel(0, -30000); await page.waitForTimeout(300);
  };
  // Рамки Compose обрезаются видимой областью; для точки нужен canvas целиком.
  const down = async (locator, minHeight = 45) => {
    let previous;
    for (let i = 0; i < 70; i++) {
      const box = await locator.boundingBox();
      if (box && box.height >= minHeight && box.y >= 0 && box.y + box.height <= 800) {
        if (previous && Math.abs(previous.y - box.y) < 1) return box;
        previous = box;
      } else {
        previous = null;
        await page.mouse.move(180, 400);
        await page.mouse.wheel(0, box?.height > 0 && box.y < 100 ? -250 : 250);
      }
      await page.waitForTimeout(200);
    }
    throw new Error(`не виден ${locator}: ${await phone.dump()}`);
  };
  const link = name => page.getByRole('button', { name: `Упражнение и прогресс: ${name}`, exact: true });
  const back = async () => { await top(); await phone.press('Назад из упражнения'); };
  return { top, down, link, back };
}

test('упражнение: рекорд, плато, 100 точек, месяцы, старый разбор и все входы с возвратом', async ({ page, request }, info) => {
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.setViewportSize({ width: 375, height: 812 });
  const f = await fixture(request);
  const bench = await f.exercise('Жим с историей', 'WEIGHT_REPS', { implements: 2, musclesPrimary: ['pectorals'] });
  await f.json(await request.patch(`${url()}/api/exercises/${bench.id}`, { headers: f.headers,
    data: { instructions: ['Сведи лопатки', 'Опускай гантели спокойно'] },
  }));
  const filler = await f.exercise('Разминка для возврата', 'TIME');
  const workouts = [];
  for (let i = 0; i <= 100; i++) {
    const values = [{ weight: i === 97 ? 30 : i > 97 ? 28 : 20, reps: 10 }];
    if (i === 100) values.push({ weight: 27, reps: 8 });
    workouts.push(await f.session(bench, 220 - i * 2, values, { liked: i === 100, disliked: i === 99 }));
  }
  await f.json(await request.put(`${url()}/api/journal/${randomUUID()}`, { headers: f.headers, data: {
    date: today(), decision: 'СЕКРЕТНЫЙ_ЖУРНАЛ', reason: 'контекст только тренеру', review: 'после проверки', exerciseIds: [bench.id],
  } }));
  await f.plan('E2E экран упражнения', [
    ...Array.from({ length: 4 }, () => ({ role: 'WARMUP', items: [{ exerciseId: filler.id, sets: [{ seconds: 20 }] }] })),
    { items: [{ exerciseId: bench.id, sets: [{ weight: 28, repsMax: 10 }] }] },
  ]);
  const history = (await f.json(await request.get(`${url()}/api/history?ids=${bench.id}&recent=100`, { headers: f.headers })))[0];
  expect(history.recent).toHaveLength(100);
  expect(history.earlier).toHaveLength(1);
  expect(history.sinceRecord).toBe(3);
  expect(history.journal).toHaveLength(1);
  const requests = [];
  page.on('request', r => requests.push(r.url()));
  const phone = new Phone(page), c = controls(page, phone);
  await phone.open();
  await phone.fill(page.getByRole('textbox').first(), f.ownKey, 0);
  await phone.press('Подключить');
  await phone.tap(phone.button(/^E2E экран упражнения/));
  await c.down(c.link(bench.nameRu).first(), 39);
  await phone.tap(c.link(bench.nameRu).first());
  await phone.see('Рекорд 2×30×10');
  await phone.see('Плато 3 тренировки');
  await expect(page.getByText('СЕКРЕТНЫЙ_ЖУРНАЛ')).toHaveCount(0);
  const chart = page.getByLabel('Прогресс упражнения', { exact: true });
  const box = await c.down(chart, 149);
  const scores = history.recent.map(s => s.score), low = Math.min(...scores) - 0.5, high = Math.max(...scores) + 0.5;
  await phone.tapAt(box.x + box.width - 8, box.y + 128 - 116 * (history.recent[0].score - low) / (high - low));
  await expect(page.getByText(/^\d+ .+ \d{4} · 2×28×10$/)).toBeVisible();
  await page.screenshot({ path: info.outputPath('exercise-progress.png') });
  expect(requests.some(raw => { const u = new URL(raw); return u.pathname === '/api/history' && u.searchParams.get('ids') === bench.id && u.searchParams.get('recent') === '100'; })).toBe(true);
  const rows = page.getByRole('button', { name: /^Тренировка упражнения:/ });
  await expect(rows).toHaveCount(9); // 8 последних и один месяц за окном 100.
  await expect(page.getByRole('button', { name: /27×8, так же, понравилось$/ })).toHaveCount(1);
  await expect(page.getByRole('button', { name: /не понравилось$/ })).toHaveCount(1);
  await c.down(phone.button('Ещё тренировки (92)'));
  await phone.press('Ещё тренировки (92)');
  await expect(rows).toHaveCount(17);
  const month = history.earlier[0].best;
  const oldDate = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' })
    .format(new Date(`${month.date}T12:00:00`)).replace(/ г\.$/, '');
  const oldRow = page.getByRole('button', { name: new RegExp(`^Тренировка упражнения: ${oldDate},`) });
  await c.down(oldRow, 65);
  await phone.tap(oldRow);
  await expect(c.link(bench.nameRu).first()).toBeVisible();
  expect(requests.some(u => new URL(u).pathname === `/api/workouts/${workouts[0]}`)).toBe(true);
  await expect(page.getByText('Тренировка не найдена', { exact: true })).toHaveCount(0);
  // Вход из разбора по названию, назад — снова этот разбор.
  await phone.tap(c.link(bench.nameRu).last());
  await phone.see('Плато 3 тренировки');
  await c.back();
  await phone.press('К упражнению');
  await c.back(); // Из исходного упражнения в план.
  await c.top();
  await c.down(phone.button('Начать тренировку'));
  await phone.press('Начать тренировку');
  const menu = phone.button(`Меню: ${bench.nameRu}`);
  await c.down(menu, 31);
  const before = await menu.boundingBox();
  await phone.tap(c.link(bench.nameRu).first());
  await phone.see('Плато 3 тренировки');
  await c.back();
  await expect.poll(async () => Math.abs((await menu.boundingBox()).y - before.y)).toBeLessThan(2);
  await phone.tap(menu);
  await phone.press('Упражнение и прогресс');
  await phone.see('Плато 3 тренировки');
  await c.back();
  await phone.rate(bench.nameRu, 'Норм');
  await phone.tap(phone.button('Завершить').first());
  await phone.press('Завершить'); // Подтверждение пропущенной разминки.
  await phone.see('Тренировка завершена');
  await c.down(c.link(bench.nameRu).first(), 39);
  await phone.tap(c.link(bench.nameRu).first());
  await phone.see('Рекорд 2×30×10');
  await c.back();
  await phone.see('Тренировка завершена');
  await info.attach('семантика итогов после возврата', { body: await phone.dump(), contentType: 'text/plain' });
  expect(errors).toEqual([]);
});

test('упражнение: пустая история, кэш медиа офлайн, восстановление сети и разные единицы', async ({ page, context, request }, info) => {
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.setViewportSize({ width: 375, height: 812 });
  const f = await fixture(request);
  const empty = await f.exercise('Новое упражнение с медиа', 'TIME', { musclesPrimary: ['quadriceps'] });
  await f.json(await request.patch(`${url()}/api/exercises/${empty.id}`, { headers: f.headers,
    data: { instructions: ['Держи корпус ровно'] },
  }));
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==', 'base64');
  await f.json(await request.put(`${url()}/api/exercises/${empty.id}/media`, { headers: { ...f.headers, 'Content-Type': 'image/png' }, data: png }));
  const mixed = await f.exercise('Прыжки или время', 'REPS');
  await f.session(mixed, 5, [{ seconds: 60 }], {}, 'TIME');
  for (let days = 4; days >= 1; days--) await f.session(mixed, days, [{ reps: 10 }]);
  const mixedHistory = (await f.json(await request.get(`${url()}/api/history?ids=${mixed.id}&recent=100`, { headers: f.headers })))[0];
  expect(mixedHistory.sinceRecord).toBe(3);
  expect(mixedHistory.recent.find(s => s.scoreUnit === 'REPS' && s.previousScoreUnit === 'SECONDS').trend).toBe('DOWN');
  await f.plan('E2E пустая история', [{ items: [
    { exerciseId: empty.id, sets: [{ seconds: 30 }] }, { exerciseId: mixed.id, sets: [{ repsMax: 10 }] },
  ] }]);
  const phone = new Phone(page), c = controls(page, phone);
  await phone.open();
  await phone.fill(page.getByRole('textbox').first(), f.ownKey, 0);
  await phone.press('Подключить');
  await phone.tap(phone.button(/^E2E пустая история/));
  await phone.tap(c.link(empty.nameRu).first());
  await phone.see('Выполненных подходов ещё нет');
  await expect.poll(() => page.locator('img.gif').evaluateAll(imgs => imgs.some(img => img.complete && img.naturalWidth > 0))).toBe(true);
  await phone.see('Держи корпус ровно');
  await expect(page.getByLabel('Прогресс упражнения', { exact: true })).toHaveCount(0);
  await page.evaluate(() => navigator.serviceWorker.ready);
  await c.back();
  await context.setOffline(true);
  await phone.tap(c.link(empty.nameRu).first());
  await phone.see('Показана последняя загруженная история');
  await phone.see('Выполненных подходов ещё нет');
  await phone.see('Держи корпус ровно');
  await expect.poll(() => page.locator('img.gif').evaluateAll(imgs => imgs.some(img => img.complete && img.naturalWidth > 0))).toBe(true);
  await page.screenshot({ path: info.outputPath('exercise-offline.png') });
  await context.setOffline(false);
  await phone.gone('Показана последняя загруженная история');
  await phone.see('Выполненных подходов ещё нет');
  await c.back();
  await phone.tap(c.link(mixed.nameRu).first());
  await phone.see('В истории разные показатели — выбери график');
  await phone.see('Рекорд 10 повт');
  await expect(page.getByText(/^Плато /)).toHaveCount(0);
  const boundary = page.getByRole('button', { name: /10 повт, нет сравнения$/ });
  await expect(boundary).toHaveCount(1);
  await expect(boundary).not.toContainText('▼');
  await expect(page.getByRole('button', { name: /10 повт, ниже прошлой$/ })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /10 повт, так же$/ })).toHaveCount(3);
  await phone.press('Секунды');
  const chart = await c.down(page.getByLabel('Прогресс упражнения', { exact: true }), 149);
  await phone.tapAt(chart.x + (42 + chart.width - 8) / 2, chart.y + 70);
  await expect(page.getByText(/^\d+ .+ \d{4} · 1:00$/)).toBeVisible();
  await phone.see('Одна тренировка — линия появится со второй');
  await page.screenshot({ path: info.outputPath('exercise-time.png') });
  await c.down(page.getByRole('button', { name: /1:00, нет сравнения$/ }), 59);
  await page.screenshot({ path: info.outputPath('exercise-mixed-history.png') });
  expect(errors).toEqual([]);
});
