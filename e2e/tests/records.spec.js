import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { Phone, gym, jsonFile, today, key, url } from '../lib.js';

test.describe.configure({ mode: 'serial' });

test('рекорд: кубок у лучшего подхода, офлайн-перезапуск, правка и снятие отметки, итоги и compare', async ({ page, context, request }, info) => {
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  await page.setViewportSize({ width: 375, height: 812 });
  const name = 'Жим рекордный';
  const ex = gym('exercise', 'add', '--ru', name, '--type', 'WEIGHT_REPS').id;
  const fresh = gym('exercise', 'add', '--ru', 'Впервые', '--type', 'REPS').id;
  const headers = { 'X-Api-Key': key() };
  const id = randomUUID(), blockId = randomUUID(), itemId = randomUUID();
  const startedAt = new Date(Date.now() - 86400000).toISOString();
  const finishedAt = new Date(Date.now() - 86400000 + 1200000).toISOString();
  const old = await request.put(`${url()}/api/workouts/${id}`, { headers, data: {
    data: { id, startedAt, finishedAt, layout: { blocks: [{ id: blockId, role: 'MAIN', items: [{ id: itemId, exerciseId: ex, recordType: 'WEIGHT_REPS', perSide: false, sets: [] }] }] } },
    baseRev: 0, opId: randomUUID(),
  } });
  expect(old.ok(), await old.text()).toBe(true);
  const setId = randomUUID();
  const oldSet = await request.put(`${url()}/api/workouts/${id}/sets/${setId}`, { headers, data: {
    data: { id: setId, workoutId: id, blockId, itemId, setNo: 1, exerciseId: ex, recordType: 'WEIGHT_REPS', perSide: false, weight: 10, reps: 10, done: true, doneAt: finishedAt },
    baseRev: 0, opId: randomUUID(),
  } });
  expect(oldSet.ok(), await oldSet.text()).toBe(true);
  const plan = gym('plan', 'put', jsonFile({ date: today(), title: 'E2E рекорды', body: { blocks: [{ items: [
    { exerciseId: ex, sets: [{ weight: 12, repsMax: 10 }, { weight: 12.01, repsMax: 10 }] },
    { exerciseId: fresh, recordType: 'REPS', sets: [{ repsMax: 5 }] },
  ] }] } }));
  const nextPlan = gym('plan', 'put', jsonFile({ date: today(), title: 'E2E следующая офлайн', body: { blocks: [{ items: [
    { exerciseId: ex, sets: [{ weight: 11, repsMax: 10 }] },
  ] }] } }));
  const phone = new Phone(page);
  await phone.open();
  await phone.fill(page.getByRole('textbox').first(), key(), 0);
  await phone.press('Подключить');
  await phone.tap(phone.button(/^E2E рекорды/));
  await phone.press('Начать тренировку');
  await phone.rate(name, 'Норм');
  await expect(page.getByLabel(/^Рекорд подхода 1:/)).toBeVisible();
  await expect(page.getByText(/рекорд/).filter({ hasText: /12/ }).first()).toBeVisible();
  await info.attach('первый рекорд: семантика', { body: await phone.dump(), contentType: 'text/plain' });
  await page.screenshot({ path: info.outputPath('records-first.png') });
  await phone.tap(phone.button(new RegExp(`^${name}`)));
  await expect(page.getByText(/рекорд/).filter({ hasText: /12/ })).toHaveCount(0);
  await expect(page.getByLabel(/^Рекорд подхода 1:/)).toBeVisible();
  await phone.rate(name, 'Норм');
  await expect(page.getByLabel(/^Рекорд подхода 1:/)).toHaveCount(0);
  await expect(page.getByLabel(/^Рекорд подхода 2:/)).toBeVisible();
  await page.screenshot({ path: info.outputPath('records-workout.png') });

  // Подходы уже на сервере, но планка сессии остаётся от вчерашнего результата.
  const workoutId = gym('workout', 'list').find(w => w.planId === plan.id).id;
  await expect.poll(async () => (await (await request.get(`${url()}/api/records?ids=${ex}`, { headers })).json()).exercises[0]?.workoutId).toBe(workoutId);
  await page.evaluate(() => navigator.serviceWorker.ready);
  await context.setOffline(true);
  await page.reload();
  await expect(phone.button(`Меню: ${name}`)).toBeVisible();
  await expect(page.getByLabel(new RegExp(`^${name}:.* · рекорд$`))).toBeVisible();
  await phone.tap(phone.button(new RegExp(`^${name}`)));
  await expect(page.getByLabel(/^Рекорд подхода 2:/)).toBeVisible();
  await context.setOffline(false);

  // Правка сделанного подхода сразу переносит кубок на первый.
  await phone.tap(page.getByLabel(/^Рекорд подхода 2:/));
  await phone.set(name, 0, 9);
  await expect(page.getByLabel(/^Рекорд подхода 1:/)).toBeVisible();
  await phone.tap(page.getByLabel(/^Рекорд подхода 1:/));
  await phone.rate(name, 'Норм');
  await expect(page.getByLabel(/^Рекорд подхода 1:/)).toHaveCount(0);
  await phone.rate(name, 'Норм');
  await expect(page.getByLabel(/^Рекорд подхода 1:/)).toBeVisible();
  await phone.rate('Впервые', 'Норм');
  await expect(page.getByLabel(/^Впервые:.* · рекорд$/)).toHaveCount(0);
  await phone.tap(phone.button('Завершить').first());
  await phone.see('Тренировка завершена');
  await phone.see('РЕКОРДЫ');
  await phone.see('Рекорд объёма — 210 кг');
  await phone.see(`${name} — 12×10`);
  await phone.see('Было 10×10');
  await page.screenshot({ path: info.outputPath('records-finish.png') });
  await expect.poll(() => gym('compare', workoutId, '--json').volumeRecord?.volumeKg).toBe(100);
  const comparison = gym('compare', workoutId, '--json');
  expect(comparison.blocks.flatMap(b => b.items.flatMap(i => i.sets)).filter(s => s.record)).toHaveLength(1);
  // 11 кг бьют исходные 10, но не только что сделанные 12. Связи нет и для второй тренировки.
  await context.setOffline(true);
  await phone.press('Готово');
  await phone.tap(phone.button(/^E2E следующая офлайн/));
  await phone.press('Начать тренировку');
  // Вторая тренировка начинается целиком офлайн, включая загрузку планки рекордов.
  await page.screenshot({ path: info.outputPath('records-next-offline.png') });
  await expect(phone.button(`Меню: ${name}`)).toBeVisible();
  await phone.rate(name, 'Норм');
  await expect(page.getByLabel(/^Рекорд подхода/)).toHaveCount(0);
  await expect(page.getByLabel(new RegExp(`^${name}:.* · рекорд$`))).toHaveCount(0);
  await phone.tap(phone.button('Завершить').first());
  await phone.see('Тренировка завершена');
  await expect(page.getByText('РЕКОРДЫ', { exact: true })).toHaveCount(0);
  await phone.press('Готово');
  await context.setOffline(false);
  await expect.poll(() => Boolean(gym('workout', 'list').find(w => w.planId === nextPlan.id)?.finishedAt), { timeout: 60_000 }).toBe(true);
  const nextWorkout = gym('workout', 'list').find(w => w.planId === nextPlan.id);
  await expect.poll(() => gym('workout', 'get', nextWorkout.id).sets.filter(s => s.done && !s.deleted).length).toBe(1);
  expect(pageErrors).toEqual([]);
});

test('нет сохранённой истории и метод недоступен: ложных рекордов нет', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  const ex = gym('exercise', 'list').find(e => e.nameRu === 'Жим рекордный');
  expect(ex).toBeTruthy();
  // Отдельный браузерный контекст без кэша; старый сервер ещё не поддерживает /records.
  await page.route('**/api/records**', route => route.fulfill({ status: 404, contentType: 'application/json', body: '{"error":"нет метода"}' }));
  gym('plan', 'put', jsonFile({ date: today(), title: 'E2E без истории рекордов', body: { blocks: [{ items: [
    { exerciseId: ex.id, sets: [{ weight: 30, repsMax: 10 }] },
  ] }] } }));
  const phone = new Phone(page);
  await phone.open();
  await phone.fill(page.getByRole('textbox').first(), key(), 0);
  await phone.press('Подключить');
  await phone.tap(phone.button(/^E2E без истории рекордов/));
  await phone.press('Начать тренировку');
  await phone.rate(ex.nameRu, 'Норм');
  await expect(page.getByLabel(/^Рекорд подхода/)).toHaveCount(0);
  await expect(page.getByLabel(new RegExp(`^${ex.nameRu}:.* · рекорд$`))).toHaveCount(0);
  await phone.tap(phone.button('Завершить').first());
  await phone.see('Тренировка завершена');
  await expect(page.getByText('РЕКОРДЫ', { exact: true })).toHaveCount(0);
  await phone.press('Готово');
  expect(pageErrors).toEqual([]);
});
