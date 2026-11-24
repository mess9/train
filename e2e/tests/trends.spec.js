import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { Phone, key, url } from '../lib.js';

test('замеры: период, последние 12, отдельные методы жира, раскрытие и точки графика', async ({ page, context, request }, info) => {
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.setViewportSize({ width: 375, height: 812 });
  const headers = { 'X-Api-Key': key() };
  const at = days => {
    const date = new Date();
    date.setHours(12, 0, 0, 0);
    date.setDate(date.getDate() + days);
    return date.toISOString();
  };
  const seed = async (type, value, days, unit, deleted = false) => {
    const id = randomUUID();
    const response = await request.put(`${url()}/api/measurements/${id}`, { headers, data: {
      data: { id, type, value, unit, measuredAt: at(days), deleted }, baseRev: 0, opId: randomUUID(),
    } });
    expect(response.ok(), await response.text()).toBe(true);
  };
  for (let i = 0; i < 14; i++) await seed('waist', 100 - i, -130 + i * 10, 'cm');
  await seed('chest', 101, 0, 'cm');
  await seed('biceps', 36, 0, 'cm', true);
  await seed('bodyfat', 18, -4, '%');
  await seed('bodyfat', 18, 0, '%');
  await seed('bodyfat_photo', 20, -20, '%');
  await seed('bodyfat_photo', 19.5, 0, '%');
  await seed('weight', 80, -20, 'kg');
  await seed('weight', 79, 0, 'kg');
  const phone = new Phone(page);
  const top = async () => {
    await page.mouse.move(180, 400);
    await page.mouse.wheel(0, -10000);
    await page.waitForTimeout(300);
  };
  // Compose обрезает a11y-рамку у края экрана: нужна вся высота canvas, а не видимая полоска.
  const revealDown = async (locator, minHeight) => {
    let previous;
    for (let i = 0; i < 50; i++) {
      const box = await locator.boundingBox();
      if (box && box.height >= minHeight && box.y >= 0 && box.y + box.height <= 748) {
        if (previous && Math.abs(previous.y - box.y) < 1) return box;
        previous = box;
      } else {
        previous = null;
        await page.mouse.move(180, 400);
        await page.mouse.wheel(0, box?.height > 0 && box.y < 100 ? -180 : 180);
      }
      await page.waitForTimeout(200);
    }
    throw new Error(`узел не виден целиком: ${locator}, ${await phone.dump()}`);
  };
  const revealChart = locator => revealDown(locator, 149);
  const revealRow = locator => revealDown(locator, 64);
  await phone.open();
  await phone.fill(page.getByRole('textbox').first(), key(), 0);
  await phone.press('Подключить');
  await phone.press('Замеры');
  const weight = await revealChart(page.getByLabel('График: Вес', { exact: true }));
  await phone.tapAt(weight.x + weight.width - 8, weight.y + 99);
  await expect(page.getByText(/^\d+ .+ \d{4} · 79 кг$/)).toBeVisible();
  const waist = page.getByRole('button', { name: /^Тренд: Талия,/ });
  await revealRow(waist);
  await expect(waist).toHaveAccessibleName('Тренд: Талия, 87 см, −2 см за месяц');
  await expect(page.getByRole('button', { name: /^Тренд: Бицепс,/ })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /^Тренд: Грудь,/ })).toHaveAccessibleName(/101 см, Один замер/);
  await phone.tap(waist);
  const chart = page.getByLabel('График: Талия', { exact: true });
  const rect = await revealChart(chart);
  // Последняя точка: нижняя линия (87) с отступом 0,5 при размахе 3 см.
  await phone.tapAt(rect.x + rect.width - 8, rect.y + 128 - 116 / 6);
  await expect(page.getByText(/^\d+ .+ \d{4} · 87 см$/)).toBeVisible();
  await page.screenshot({ path: info.outputPath('trends-month.png') });

  await phone.press('Главная');
  await phone.press('Замеры');
  await revealChart(chart);
  await expect(chart).toBeVisible();
  await top();
  await phone.press('Неделя');
  await revealRow(waist);
  await expect(waist).toHaveAccessibleName(/87 см, −11 см с /);
  await revealChart(chart);
  await expect(chart).toBeVisible();
  await top();
  await phone.press('Год');
  await revealRow(waist);
  await expect(waist).toHaveAccessibleName('Тренд: Талия, 87 см, −13 см за год');

  await phone.tap(waist);
  await expect(chart).toHaveCount(0);
  const fat = page.getByRole('button', { name: /^Тренд: Жир по складкам,/ });
  await revealRow(fat);
  await expect(fat).toHaveAccessibleName('Тренд: Жир по складкам, 18 %, 0 п.п. за год');
  await expect(page.getByRole('button', { name: /^Тренд: Жир по фото,/ })).toHaveAccessibleName('Тренд: Жир по фото, 19,5 %, −0,5 п.п. за год');
  await phone.tap(fat);
  const flat = page.getByLabel('График: Жир по складкам', { exact: true });
  const f = await revealChart(flat);
  await phone.tapAt(f.x + f.width - 8, f.y + 70);
  await expect(page.getByText(/^\d+ .+ \d{4} · 18 %$/)).toBeVisible();
  await page.screenshot({ path: info.outputPath('trends-bodyfat.png') });
  const chest = page.getByRole('button', { name: /^Тренд: Грудь,/ });
  await phone.tap(chest);
  const single = page.getByLabel('График: Грудь', { exact: true });
  const c = await revealChart(single);
  await phone.tapAt(c.x + (42 + c.width - 8) / 2, c.y + 70);
  await expect(page.getByText(/^\d+ .+ \d{4} · 101 см$/)).toBeVisible();

  // Сводка с сервера недоступна, но локальные замеры, период и раскрытие работают.
  await context.setOffline(true);
  await phone.press('Главная');
  await phone.press('Замеры');
  await revealChart(single);
  await expect(single).toBeVisible();
  await top();
  await revealRow(waist);
  await expect(waist).toHaveAccessibleName('Тренд: Талия, 87 см, −13 см за год');
  await phone.tap(waist);
  await revealChart(chart);
  await expect(chart).toBeVisible();
  await expect(page.getByLabel('График: Вес', { exact: true })).toHaveCount(1);
  expect(errors).toEqual([]);
  await info.attach('семантика трендов', { body: await phone.dump(), contentType: 'text/plain' });
});
