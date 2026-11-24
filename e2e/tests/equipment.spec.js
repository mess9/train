// Рекомендация оборудования: API → кэш телефона → план, только до старта тренировки.
import { test, expect } from '@playwright/test';
import { Phone, gym, jsonFile, today, key } from '../lib.js';

test('оборудование видно в плане и офлайн, в тренировке его нет; старый план работает', async ({ page, context }, info) => {
  await page.setViewportSize({ width: 375, height: 812 });
  const press = gym('exercise', 'add', '--ru', 'Жим для оборудования', '--type', 'WEIGHT_REPS').id;
  const row = gym('exercise', 'add', '--ru', 'Тяга для оборудования', '--type', 'WEIGHT_REPS').id;
  const body = {
    note: 'Начни спокойно, затем выставь рабочий вес',
    equipment: [
      { at: 'Начало', setup: 'гантели 2×10 кг, коврик' },
      { exerciseId: row, setup: 'гантель 1×15 кг' },
      { exerciseId: press, at: 'Круг 2', setup: 'гантели 2×12,5 кг' },
    ],
    blocks: [{ items: [
      { exerciseId: press, sets: [{ weight: 10, repsMax: 10 }] },
      { exerciseId: row, sets: [{ weight: 15, repsMax: 10 }] },
    ] }],
  };
  const plan = gym('plan', 'put', jsonFile({ date: today(), title: 'E2E оборудование', body }));
  gym('plan', 'put', jsonFile({ date: today(), title: 'E2E без оборудования', body: { blocks: body.blocks } }));
  const phone = new Phone(page);
  await phone.open();
  await phone.fill(page.getByRole('textbox').first(), key(), 0);
  await phone.press('Подключить');
  await phone.see('СЕГОДНЯ');
  await phone.tap(phone.button(/^E2E без оборудования/));
  await expect(page.getByText('Оборудование ·', { exact: false })).toHaveCount(0);
  await phone.press('Назад');
  await phone.tap(phone.button(/^E2E оборудование/));
  await phone.see('Оборудование · 2 смены');
  await phone.see('Тяга для оборудования');
  const labels = ['Начало', 'гантели 2×10 кг, коврик', 'Тяга для оборудования', 'гантель 1×15 кг', 'Круг 2', 'гантели 2×12,5 кг'];
  for (const label of labels) await phone.see(label);
  const positions = [];
  for (let i = 0; i < labels.length; i += 2) {
    const left = await page.getByText(labels[i], { exact: true }).first().boundingBox();
    const right = await page.getByText(labels[i + 1], { exact: true }).first().boundingBox();
    expect(right.x).toBeGreaterThanOrEqual(left.x + left.width);
    expect(right.x + right.width).toBeLessThanOrEqual(375);
    expect(right.y).toBeCloseTo(left.y, 0);
    positions.push(left.y);
  }
  expect(positions[0]).toBeLessThan(positions[1]);
  expect(positions[1]).toBeLessThan(positions[2]);
  const screenshot = info.outputPath('equipment-plan.png');
  await page.screenshot({ path: screenshot });
  await info.attach('план с оборудованием', { path: screenshot, contentType: 'image/png' });

  // После перезапуска без сети шаги читаются из сохранённого кэша.
  await page.evaluate(() => navigator.serviceWorker.ready);
  await context.setOffline(true);
  await page.reload();
  await phone.see('СЕГОДНЯ');
  await phone.tap(phone.button(/^E2E оборудование/));
  await phone.see('Оборудование · 2 смены');
  for (const label of labels) await phone.see(label);
  await context.setOffline(false);
  await phone.press('Начать тренировку');
  await expect(phone.button('Меню: Жим для оборудования')).toBeVisible();
  await expect(page.getByText('Оборудование ·', { exact: false })).toHaveCount(0);
  expect(gym('plan', 'get', plan.id).body.equipment).toEqual(body.equipment);

  // Стенд общий для всех файлов: следующий сценарий начинает без активной тренировки.
  await phone.tap(phone.button('Завершить').first());
  await phone.see('Завершить тренировку?');
  await phone.press('Завершить');
  await phone.see('Тренировка завершена');
  await phone.press('Готово');
  await phone.see('СЕГОДНЯ');
});
