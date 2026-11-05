// Офлайн: отметки копятся в очереди телефона, с возвратом сети уходят — на сервере ровно по одной.
// Заодно заминка, которой нет в плане («+ заминка» видна, только когда в плане её нет)
import { test, expect } from '@playwright/test';
import { Phone, gym, jsonFile, today, key } from '../lib.js';

test.describe.configure({ mode: 'serial' });

let context;
let page;
let phone;
let planId;
const ex = {};

test.beforeAll(async ({ browser }) => {
  context = await browser.newContext();
  page = await context.newPage();
  phone = new Phone(page);
});

test.afterAll(async () => {
  await context?.close();
});

test.afterEach(async ({}, info) => {
  if (info.status !== info.expectedStatus && phone) {
    await info.attach('a11y', { body: await phone.dump(), contentType: 'text/plain' });
    await info.attach('screen', { body: await page.screenshot(), contentType: 'image/png' });
  }
});

/** Выполненные живые подходы тренировки по плану — так, как их видит агент. */
function doneSets() {
  const w = gym('workout', 'list', '--from', today()).find((x) => x.planId === planId);
  if (!w) return [];
  return gym('workout', 'get', w.id).sets.filter((s) => s.done && !s.deleted);
}

test('офлайн: подход и заминка в очереди, после сети — ровно по одному', async () => {
  ex.calf = gym('exercise', 'add', '--ru', 'Подъём на носки', '--type', 'REPS').id;
  ex.fold = gym('exercise', 'add', '--ru', 'Наклон к ногам сидя', '--type', 'TIME').id;
  planId = gym('plan', 'put', jsonFile({
    date: today(),
    title: 'E2E офлайн',
    body: { blocks: [{ role: 'MAIN', items: [{ exerciseId: ex.calf, sets: [{ repsMin: 15, repsMax: 20 }, { repsMin: 15, repsMax: 20 }] }] }] },
  })).id;

  await phone.open();
  await phone.fill(page.getByRole('textbox').first(), key(), 0);
  await phone.press('Подключить');
  await phone.see('СЕГОДНЯ');
  await phone.tap(phone.button(/^E2E офлайн/));
  await phone.press('Начать тренировку');
  await expect(phone.button('Меню: Подъём на носки')).toBeVisible();
  // Начало тренировки ушло на сервер, пока сеть была
  await expect.poll(() => gym('workout', 'list', '--from', today()).some((x) => x.planId === planId), { timeout: 30_000 }).toBe(true);

  await context.setOffline(true);
  await phone.rate('Подъём на носки', 'Норм');
  await phone.see('в очереди');

  // Заминка не по плану — из справочника
  await phone.press('+ заминка');
  await phone.see('Заминка: первое упражнение');
  await phone.fill(page.getByRole('textbox').last(), 'Наклон', 0);
  await phone.tap(phone.button(/^Наклон к ногам сидя/));
  await phone.set('Наклон к ногам сидя', 0, 30);
  await phone.rate('Наклон к ногам сидя', 'Легко');

  // Без сети сервер ничего не получил
  expect(doneSets()).toHaveLength(0);

  await context.setOffline(false);
  await expect.poll(() => doneSets().length, { timeout: 60_000, intervals: [1000] }).toBe(2);
  await phone.gone('в очереди');
  // Повторных отправок нет: подождали ещё — по-прежнему по одному
  await page.waitForTimeout(3000);
  const sets = doneSets();
  expect(sets.filter((s) => s.exerciseId === ex.calf)).toEqual([expect.objectContaining({ setNo: 1, reps: 20, rating: 'NORMAL' })]);
  expect(sets.filter((s) => s.exerciseId === ex.fold)).toEqual([expect.objectContaining({ setNo: 1, seconds: 30, rating: 'EASY' })]);

  // Заминка — отдельный блок с ролью COOLDOWN
  const w = gym('workout', 'list', '--from', today()).find((x) => x.planId === planId);
  const blocks = gym('workout', 'get', w.id).workout.layout.blocks;
  expect(blocks.map((b) => b.role)).toEqual(['MAIN', 'COOLDOWN']);
  expect(blocks[1].items[0]).toMatchObject({ exerciseId: ex.fold, added: true });

  // Закрыть тренировку, чтобы не висела активной для других сценариев
  await phone.tap(phone.button('Завершить').first());
  await phone.see('Завершить тренировку?');
  await phone.press('Завершить');
  await phone.see('Тренировка завершена');
  await phone.press('Готово');
  await phone.see('СЕГОДНЯ');
});
