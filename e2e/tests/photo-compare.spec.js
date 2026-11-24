import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { Buffer } from 'node:buffer';
import { Phone, key, url } from '../lib.js';

async function setup(page, request) {
  const read = async response => {
    expect(response.ok(), await response.text()).toBe(true);
    return response.json();
  };
  const user = await read(await request.post(`${url()}/api/admin/users`, {
    headers: { 'X-Api-Key': key() }, data: { email: `photos-${randomUUID()}@e2e.local` },
  }));
  const ownKey = user.key.key, headers = { 'X-Api-Key': ownKey };
  const photo = async (date, pose, color, label) => {
    // Большой исходник: приложение должно декодировать уменьшенную пару, а не оригинал.
    const base64 = await page.evaluate(({ color, label }) => {
      const canvas = document.createElement('canvas'); canvas.width = 1500; canvas.height = 2400;
      const ctx = canvas.getContext('2d'); ctx.fillStyle = color; ctx.fillRect(0, 0, 1500, 2400);
      ctx.fillStyle = '#dbe4eb'; ctx.beginPath(); ctx.arc(750, 480, 180, 0, Math.PI * 2); ctx.fill();
      ctx.fillRect(500, 700, 500, 800); ctx.fillRect(500, 1500, 180, 650); ctx.fillRect(820, 1500, 180, 650);
      ctx.fillRect(280, 750, 170, 750); ctx.fillRect(1050, 750, 170, 750);
      ctx.font = 'bold 100px sans-serif'; ctx.textAlign = 'center'; ctx.fillText(label, 750, 180);
      return canvas.toDataURL('image/png').split(',')[1];
    }, { color, label });
    const id = randomUUID();
    const params = new URLSearchParams({ takenAt: `${date}T12:00:00Z`, ...(pose == null ? {} : { pose }) });
    return read(await request.put(`${url()}/api/photos/${id}?${params}`, {
      headers: { ...headers, 'Content-Type': 'image/png' }, data: Buffer.from(base64, 'base64'),
    }));
  };
  const weight = async (date, value) => {
    const id = randomUUID();
    await read(await request.put(`${url()}/api/measurements/${id}`, { headers, data: {
      data: { id, type: 'weight', value, unit: 'kg', measuredAt: `${date}T12:00:00Z` }, baseRev: 0, opId: randomUUID(),
    } }));
  };
  return { ownKey, headers, photo, weight };
}

function controls(page, phone) {
  const top = async () => {
    await page.mouse.move(180, 200); await page.mouse.wheel(0, -30000); await page.waitForTimeout(300);
  };
  const down = async (locator, minHeight = 30) => {
    const bottom = page.viewportSize().height - (await phone.button('Главная').count() ? 72 : 4);
    let previous;
    for (let i = 0; i < 60; i++) {
      const box = await locator.boundingBox();
      if (box && box.height >= minHeight && box.y >= 0 && box.y + box.height <= bottom) {
        if (previous && Math.abs(previous.y - box.y) < 1) return box;
        previous = box;
      } else {
        previous = null;
        await page.mouse.move(180, 300);
        await page.mouse.wheel(0, box?.height > 0 && box.y < 100 ? -200 : 200);
      }
      await page.waitForTimeout(200);
    }
    throw new Error(`не виден ${locator}: ${await phone.dump()}`);
  };
  const enter = async ownKey => {
    await phone.open(); await phone.fill(page.getByRole('textbox').first(), ownKey, 0); await phone.press('Подключить');
    await phone.press('Замеры'); await down(phone.button('Сравнить фото')); await phone.press('Сравнить фото');
  };
  const slot = (name, date) => page.getByRole('button', { name: `Открыть фото ${name}: ${date}`, exact: true });
  return { top, down, enter, slot };
}

test('фото до/после: месяцы, вес, оба слота, календарь, просмотр, удаление и восстановление сети', async ({ page, context, request }, info) => {
  await page.setViewportSize({ width: 375, height: 812 });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const f = await setup(page, request);
  await f.photo('2026-08-18', 'front', '#4b334e', 'AUG');
  await f.photo('2026-09-20', 'front', '#345061', 'SEP');
  await f.photo('2026-10-20', 'front', '#41542b', 'OCT');
  await f.photo('2026-11-20', 'front', '#245b4b', 'NOV');
  await f.photo('2026-11-23', 'side', '#36536a', 'SINGLE'); // Самое свежее, но ракурс без пары.
  await f.photo('2026-11-09', 'Мой ракурс', '#583a2b', 'CUSTOM 1');
  await f.photo('2026-11-10', 'Мой ракурс', '#6b4131', 'CUSTOM 2');
  await f.photo('2026-10-01', null, '#405275', 'IMPORT 1');
  await f.photo('2026-10-01', null, '#584260', 'IMPORT 2'); // Одинаковое время, разные снимки.
  await f.weight('2026-08-19', 80); await f.weight('2026-09-20', 79.5);
  await f.weight('2026-10-20', 79); await f.weight('2026-11-19', 77.5);
  const phone = new Phone(page), c = controls(page, phone);
  await c.enter(f.ownKey);
  await expect(c.slot('До', '2026-10-20')).toHaveCount(1);
  await expect(c.slot('После', '2026-11-20')).toHaveCount(1);
  await expect(phone.button('Сбоку')).toHaveCount(0);
  await phone.press('3 мес');
  await expect(c.slot('До', '2026-08-18')).toHaveCount(1);
  await expect(page.getByLabel('Разница фото: 3 мес 2 дня · −2,5 кг', { exact: true })).toBeVisible();
  await c.down(c.slot('После', '2026-11-20'), 180);
  await expect(phone.button('Повторить загрузку фото')).toHaveCount(0);
  await page.screenshot({ path: info.outputPath('photo-compare.png') });
  // Подпись выбирает активную сторону, снимок открывает Viewer и не меняет её.
  await phone.press('После');
  await phone.tap(c.slot('До', '2026-08-18'));
  await phone.see('В корзину'); await phone.press('Закрыть');
  await expect(page.getByLabel('Лента фото: После', { exact: true })).toHaveCount(1);
  const oct = page.getByRole('button', { name: /^Снимок 2: 2026-10-20 .*для После$/ });
  await c.down(oct, 90); await phone.tap(oct);
  await expect(c.slot('После', '2026-10-20')).toHaveCount(1);
  await c.top(); await phone.press('До'); await phone.press('Дата…');
  await phone.see('Дата снимка «До»');
  // Compose/Wasm не экспортирует Disabled в aria-disabled: проверяем озвучку и реальный тап.
  await expect(phone.button('Предыдущий месяц, недоступно')).toHaveCount(1);
  await phone.press('Предыдущий месяц, недоступно');
  await phone.see('Август 2026');
  await phone.press('Следующий месяц');
  await expect(page.getByRole('button', { name: 'Дата: 2026-09-20, есть фото', exact: true })).toBeEnabled();
  await phone.tap(page.getByRole('button', { name: 'Дата: 2026-09-15', exact: true }));
  await expect(c.slot('До', '2026-09-20')).toHaveCount(1); // День без снимка → ближайший.
  await expect(page.getByLabel('Разница фото: 1 мес · −0,5 кг', { exact: true })).toHaveCount(1);
  await phone.press('Дата…'); await phone.press('Отмена');
  await expect(c.slot('До', '2026-09-20')).toHaveCount(1);
  await phone.press('Мой ракурс');
  await expect(page.getByLabel('Разница фото: 1 день', { exact: true })).toHaveCount(1); // Ближайший вес дальше 7 дней.
  await phone.press('Без ракурса');
  await expect(page.getByLabel('Разница фото: В один день', { exact: true })).toHaveCount(1);
  for (const label of ['1 мес', '2 мес', '3 мес']) await expect(phone.button(`${label}, недоступно`)).toHaveCount(1);
  await phone.tap(c.slot('До', '2026-10-01')); await phone.press('В корзину'); await phone.press('Удалить');
  await expect(phone.button('Без ракурса')).toHaveCount(0); // После удаления осталось одно импортное фото.
  await expect(c.slot('После', '2026-11-20')).toHaveCount(1);
  await c.top(); await phone.press('К замерам');
  // Без связи загруженные фото остаются на экране с пометкой, а не прячутся
  await context.setOffline(true); await phone.press('Сравнить фото');
  await phone.see('Показаны фото с последней загрузки — обновятся, когда будет связь');
  await expect(c.slot('После', '2026-11-20')).toHaveCount(1);
  await context.setOffline(false);
  await expect(page.getByText('Показаны фото с последней загрузки — обновятся, когда будет связь', { exact: true })).toHaveCount(0);
  expect(errors).toEqual([]);
  await info.attach('семантика сравнения', { body: await phone.dump(), contentType: 'text/plain' });
});

test('фото до/после: одной фотографии недостаточно, ошибка загрузки пары исправляется повтором', async ({ page, request }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const f = await setup(page, request);
  const old = await f.photo('2026-10-20', 'front', '#42536b', 'OLD');
  const phone = new Phone(page), c = controls(page, phone);
  await phone.open(); await phone.fill(page.getByRole('textbox').first(), f.ownKey, 0); await phone.press('Подключить');
  await phone.press('Замеры');
  await expect.poll(() => page.getByText('20 октября · спереди', { exact: false }).count()).toBe(1);
  await expect(phone.button('Сравнить фото')).toHaveCount(0);
  await f.photo('2026-11-20', 'front', '#375848', 'NEW');
  await page.reload(); await phone.press('Замеры');
  await c.down(phone.button('Сравнить фото'));
  const file = `**/api/photos/${old.id}/file`;
  await page.route(file, route => route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"test unavailable"}' }));
  await phone.press('Сравнить фото');
  await phone.see('Не удалось загрузить фото');
  await page.unroute(file); await phone.press('Повторить загрузку фото');
  await expect(c.slot('До', '2026-10-20')).toHaveCount(1);
  await c.down(c.slot('До', '2026-10-20'), 180);
  await expect(phone.button('Повторить загрузку фото')).toHaveCount(0);
  await expect(page.getByText('Не удалось загрузить фото', { exact: true })).toHaveCount(0);
  expect(errors).toEqual([]);
});
