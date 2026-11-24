import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { Phone, key, url } from '../lib.js';
import { backFixture } from '../back-fixture.js';

async function connected(page) {
  await page.setViewportSize({ width: 375, height: 812 });
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  const data = await backFixture(url(), key());
  const phone = new Phone(page);
  await phone.open(); await phone.fill(page.getByRole('textbox').first(), data.key, 0); await phone.press('Подключить');
  return { data, phone, errors };
}

const back = async page => { await page.evaluate(() => history.back()); await page.waitForTimeout(250); };
const top = async page => { await page.mouse.move(180, 200); await page.mouse.wheel(0, -30000); await page.waitForTimeout(250); };

test('назад: фото и подтверждение, календарь, черновик, занятая загрузка, экраны и forward', async ({ page }, info) => {
  const { data, phone, errors } = await connected(page);
  await phone.press('Замеры'); await phone.press('Сравнить фото');
  const compareDepth = await page.evaluate(() => history.state);
  const image = page.getByRole('button', { name: 'Открыть фото После: 2026-11-20', exact: true });
  await phone.tap(image); await phone.press('В корзину');
  await expect(phone.button('Удалить')).toBeVisible();
  await back(page);
  await expect(phone.button('Удалить')).toHaveCount(0); await expect(phone.button('Закрыть')).toBeVisible();
  await back(page);
  await expect(phone.button('Закрыть')).toHaveCount(0); await expect(image).toBeVisible();
  await page.goForward();
  await expect.poll(() => page.evaluate(() => history.state)).toBe(compareDepth);
  await expect(phone.button('Закрыть')).toHaveCount(0);
  await top(page); await phone.press('Дата…'); await phone.see('Дата снимка «До»');
  await back(page); await expect(page.getByText('Дата снимка «До»', { exact: true })).toHaveCount(0);
  await expect(image).toBeVisible();
  await back(page); await expect(phone.button('Сравнить фото')).toHaveCount(1);

  const preparePhoto = async () => {
    await page.evaluate(png => {
      const bytes = Uint8Array.from(atob(png), c => c.charCodeAt(0));
      const file = new File([bytes], 'back.png', { type: 'image/png', lastModified: Date.now() });
      window.bridge.pickPhoto = camera => window.bridge.usePhoto(file, camera);
    }, data.png);
    await phone.press('Камера'); await phone.see('Не сохранять');
  };
  await preparePhoto(); await back(page);
  await expect(page.getByText('Не сохранять', { exact: true })).toHaveCount(0);
  await expect(phone.button('Камера')).toHaveCount(1);
  await preparePhoto();
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  await page.route('**/api/photos/**', async route => {
    if (route.request().method() === 'PUT') await gate;
    await route.continue();
  });
  await phone.press('Сохранить'); await phone.see('Загружаю…');
  const busyDepth = await page.evaluate(() => history.state);
  await back(page);
  await phone.see('Загружаю…');
  await phone.see('Фото прогресса');
  await expect.poll(() => page.evaluate(() => history.state)).toBe(busyDepth);
  const uploaded = page.waitForResponse(response => response.request().method() === 'PUT' && response.url().includes('/api/photos/'));
  release(); await uploaded;
  await expect.poll(() => page.evaluate(() => history.state)).toBe(busyDepth - 1);
  await expect(page.getByText('Загружаю…', { exact: true })).toHaveCount(0);
  await expect(phone.button('Камера')).toBeVisible();
  await page.unroute('**/api/photos/**');
  await back(page); await expect(phone.button(/^E2E системная назад/)).toBeVisible();
  await phone.press('История'); await back(page);
  await expect(phone.button(/^E2E системная назад/)).toBeVisible();
  await phone.press('Настройки'); await back(page);
  await expect(phone.button(/^E2E системная назад/)).toBeVisible();
  await phone.tap(phone.button(/^E2E системная назад/)); await back(page);
  await expect(phone.button('Начать тренировку')).toHaveCount(0);
  expect(page.url()).toBe(url() + '/');
  expect(errors).toEqual([]);
  await info.attach('слои закрыты до корня', { body: await phone.dump(), contentType: 'text/plain' });
});

test('назад: меню, инструменты, правка подхода, отмена порядка, сохранность и завершение тренировки', async ({ page, request }, info) => {
  const { data, phone, errors } = await connected(page);
  const headers = { 'X-Api-Key': data.key };
  await phone.tap(phone.button(/^E2E системная назад/)); await phone.press('Начать тренировку');
  const menu = phone.button('Меню: Жим для назад');
  await phone.tap(menu); await phone.press('Упражнение и прогресс');
  await phone.see('Выполненных подходов ещё нет'); await back(page); await expect(menu).toBeVisible();
  await phone.tap(menu); await phone.see('Заменить упражнение'); await back(page);
  await expect(phone.button('Заменить упражнение')).toHaveCount(0); await expect(menu).toBeVisible();
  await phone.press('Таймер и секундомер'); await phone.see('Секундомер'); await back(page);
  await expect(phone.button('Секундомер')).toHaveCount(0); await expect(menu).toBeVisible();
  await phone.rate('Жим для назад', 'Норм');
  await phone.tap(phone.button(/^Жим для назад:/));
  await phone.tap(page.getByLabel(/^Подход 1: 10×10/));
  await phone.set('Жим для назад', 0, 11);
  await back(page); await expect(menu).toBeVisible();
  await expect(page.getByLabel(/^Подход 1: 11×10/)).toBeVisible();
  await phone.tap(menu); await phone.press('Изменить порядок'); await phone.press('Ниже: Жим для назад');
  // Возврат из упражнения сохраняет и режим, и исходный порядок для его отмены.
  await phone.tap(page.getByRole('button', { name: 'Упражнение и прогресс: Жим для назад', exact: true }).first());
  await phone.see('На последней тренировке'); await back(page);
  await expect(phone.button('Закрепить')).toBeVisible(); await back(page);
  await expect(phone.button('Закрепить')).toHaveCount(0);
  const workout = async () => (await (await request.get(`${url()}/api/workouts`, { headers })).json()).find(w => w.planId === data.plan);
  await expect.poll(async () => (await workout())?.layout.blocks[0].items[0].exerciseId).toBe(data.bench);
  await phone.tap(phone.button('Завершить').first()); await phone.see('Завершить тренировку?'); await back(page);
  await expect(page.getByText('Завершить тренировку?', { exact: true })).toHaveCount(0);
  await back(page); await expect(menu).toHaveCount(0);
  await expect.poll(async () => (await workout())?.finishedAt ?? null).toBe(null);
  await page.reload(); await expect(menu).toBeVisible();
  const id = (await workout()).id;
  await expect.poll(async () => (await (await request.get(`${url()}/api/workouts/${id}`, { headers })).json()).sets.filter(s => s.done).map(s => s.weight)).toEqual([11]);
  await phone.tap(phone.button('Завершить').first()); await phone.press('Завершить');
  await phone.see('Тренировка завершена'); await back(page);
  await expect(page.getByText('Тренировка завершена', { exact: true })).toHaveCount(0);
  await page.reload(); await expect(menu).toHaveCount(0);
  await phone.press('История'); await phone.tap(phone.button(/E2E системная назад/));
  await phone.press('Удалить тренировку'); await phone.see('Удалить тренировку насовсем?'); await back(page);
  await expect(page.getByText('Удалить тренировку насовсем?', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /^Упражнение и прогресс: Жим для назад$/ }).first()).toHaveCount(1);
  await back(page); await back(page);
  await expect.poll(async () => !!(await workout())?.finishedAt).toBe(true);
  expect(errors).toEqual([]);
  await info.attach('сохранённый факт', { body: JSON.stringify(await (await request.get(`${url()}/api/workouts/${id}`, { headers })).json(), null, 2), contentType: 'application/json' });
});

test('назад: подтверждение забрать тренировку закрывается даже на корне', async ({ page, request }) => {
  const data = await backFixture(url(), key());
  const id = randomUUID();
  const headers = { 'X-Api-Key': data.key, 'X-Device': 'another-back-test' };
  const response = await request.put(`${url()}/api/workouts/${id}`, { headers, data: {
    data: { id, startedAt: new Date().toISOString(), device: 'another-back-test', deviceKind: 'web', layout: { blocks: [] } },
    baseRev: 0, opId: randomUUID(),
  } });
  expect(response.ok(), await response.text()).toBe(true);
  const phone = new Phone(page);
  await phone.open(); await phone.fill(page.getByRole('textbox').first(), data.key, 0); await phone.press('Подключить');
  await phone.press('Забрать'); await phone.see('Забрать тренировку сюда?');
  await back(page); await expect(page.getByText('Забрать тренировку сюда?', { exact: true })).toHaveCount(0);
  await expect(phone.button('Забрать')).toHaveCount(1);
  const current = await (await request.get(`${url()}/api/workouts/${id}`, { headers })).json();
  expect(current.workout.device).toBe('another-back-test');
});
