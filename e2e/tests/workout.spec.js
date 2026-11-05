// Сквозной сценарий: агент пишет профиль и план через CLI, владелец проходит тренировку в PWA,
// агент видит в compare всё сделанное. Шаги идут цепочкой — каждый следующий опирается на прошлый
import { test, expect } from '@playwright/test';
import { Phone, gym, jsonFile, today } from '../lib.js';

test.describe.configure({ mode: 'serial' });

const ex = {};
let planId;
let workoutId;
let page;
let phone;
const photoPuts = [];

test.beforeAll(async ({ browser }) => {
  page = await browser.newPage();
  phone = new Phone(page);
  page.on('request', (r) => {
    if (r.method() === 'PUT' && r.url().includes('/api/photos/')) photoPuts.push(r.url());
  });
});

test.afterAll(async () => {
  await page?.close();
});

test.afterEach(async ({}, info) => {
  if (info.status !== info.expectedStatus && phone) {
    await info.attach('a11y', { body: await phone.dump(), contentType: 'text/plain' });
    await info.attach('screen', { body: await page.screenshot(), contentType: 'image/png' });
  }
});

test('агент: профиль с ростом, справочник и план с разминкой, кругом × 3 и заминкой', async () => {
  const profile = gym('profile', 'put', jsonFile({ name: 'E2E', equipment: 'гантели 2–24 кг, коврик', notes: 'спина — без осевой', heightCm: 181 }));
  expect(profile.heightCm).toBe(181);
  expect(gym('profile', 'get')).toMatchObject({ name: 'E2E', heightCm: 181, equipment: 'гантели 2–24 кг, коврик' });

  for (const [k, ru, type] of [
    ['rope', 'Скакалка', 'TIME'], ['press', 'Жим гантелей лёжа', 'WEIGHT_REPS'], ['squat', 'Гоблет-присед', 'WEIGHT_REPS'],
    ['pushup', 'Отжимания', 'REPS'], ['plank', 'Планка', 'TIME'], ['stretch', 'Растяжка', 'TIME'],
    ['row', 'Тяга гантели в наклоне', 'WEIGHT_REPS'], ['lunge', 'Выпады', 'WEIGHT_REPS'],
  ]) {
    ex[k] = gym('exercise', 'add', '--ru', ru, '--type', type).id;
  }
  const three = (s) => [s, s, s];
  const plan = gym('plan', 'put', jsonFile({
    date: today(),
    title: 'E2E круг',
    body: {
      note: 'проверка сквозная',
      blocks: [
        { role: 'WARMUP', title: 'Разминка', items: [{ exerciseId: ex.rope, recordType: 'TIME', sets: [{ seconds: 60 }] }] },
        {
          role: 'MAIN', title: 'Круг', restAfterPassSec: 60, items: [
            { exerciseId: ex.press, restSec: 30, sets: three({ weight: 20, repsMin: 10, repsMax: 12 }) },
            { exerciseId: ex.squat, restSec: 30, sets: three({ weight: 16, repsMin: 10, repsMax: 12 }) },
            { exerciseId: ex.pushup, restSec: 30, sets: three({ repsMin: 10, repsMax: 15 }) },
            { exerciseId: ex.plank, recordType: 'TIME', sets: three({ seconds: 45 }) },
          ],
        },
        { role: 'COOLDOWN', title: 'Заминка', items: [{ exerciseId: ex.stretch, recordType: 'TIME', sets: [{ seconds: 60 }] }] },
      ],
    },
  }));
  planId = plan.id;
  const list = gym('plan', 'list', '--from', today());
  expect(list.find((p) => p.id === planId)?.status).toBe('PLANNED');
});

test('владелец: подключение ключом и план на сегодня', async () => {
  await phone.open();
  await phone.fill(page.getByRole('textbox'), process.env.E2E_KEY, 0);
  await phone.press('Подключить');
  await phone.see('СЕГОДНЯ');
  await phone.tap(phone.button(/^E2E круг/));
  await phone.see('проверка сквозная');
  await phone.see('× 3 прохода · отдых 60 с');
  await phone.press('Начать тренировку');
  await expect(phone.button('Завершить')).toBeVisible();
  await expect(phone.button('Меню: Скакалка')).toBeVisible();
});

test('владелец: разминка, круг с правкой значений, временем, отдыхом и тремя оценками', async () => {
  // Разминка: время по плану, оценка «Норм»
  expect(await phone.fieldText('Скакалка', 0)).toBe('1:00');
  await phone.rate('Скакалка', 'Норм');

  // Круг, проход 1. Жим: повторы 12 → 11, «Легко» — пошёл отдых между упражнениями круга
  await phone.see('× 3 · проход 1 из 3');
  await phone.set('Жим гантелей лёжа', 1, 11);
  expect(await phone.fieldText('Жим гантелей лёжа', 1)).toBe('11');
  await phone.rate('Жим гантелей лёжа', 'Легко');
  await expect(phone.button('Скрыть')).toBeVisible();
  await expect(phone.button('+15')).toBeVisible();

  // Гоблет: вес 16 → 18, «Тяжело»
  await phone.set('Гоблет-присед', 0, 18);
  await phone.rate('Гоблет-присед', 'Тяжело');

  // Отжимания: повторы 12
  await phone.set('Отжимания', 0, 12);
  await phone.rate('Отжимания', 'Норм');

  // Планка: набор «микроволновкой» 130 → 1:30
  await phone.set('Планка', 0, 130);
  expect(await phone.fieldText('Планка', 0)).toBe('1:30');
  await phone.rate('Планка', 'Норм');
  await phone.see('× 3 · проход 2 из 3');
});

test('владелец: ровно один добавленный подход и удаление выполненного', async () => {
  // «Добавить подход» раскрывает карточку: 2 плановых невыполненных + 1 добавленный, подпись «доп» одна
  await phone.menu('Жим гантелей лёжа', 'Добавить подход');
  expect((await phone.rows('Жим гантелей лёжа')).length).toBe(3);
  await expect(phone.text('доп')).toHaveCount(1);
  // Добавленный подход сразу сделан — иначе пустой, на сервер не уходит
  await phone.rate('Жим гантелей лёжа', 'Тяжело', 2);
  await phone.menu('Жим гантелей лёжа', 'Свернуть подходы');

  // Гоблет: выполненный подход 1 (18×12, тяжело) удалить — в разборе он станет пропущенным
  await phone.menu('Гоблет-присед', 'Удалить выполненный подход…');
  await phone.see('Удалить подход');
  await phone.tap(phone.button(/^Подход 1 · 18×12/));
  await phone.gone('в разборе плановый подход станет');
});

test('владелец: замена упражнения, сердечко и заметка', async () => {
  await phone.menu('Отжимания', 'Заменить упражнение');
  await phone.see('Заменить на…');
  await phone.fill(page.getByRole('textbox').last(), 'Тяга', 0);
  await phone.tap(phone.button(/^Тяга гантели в наклоне/));
  await expect(phone.button('Меню: Тяга гантели в наклоне')).toBeVisible();
  await expect(phone.button('Меню: Отжимания')).toHaveCount(0);

  // Сердечко — в шапке меню, лист остаётся открытым; оттуда же заметка
  await phone.tap(phone.button('Меню: Жим гантелей лёжа'));
  await phone.press('Поставить сердечко');
  await expect(phone.button('Убрать сердечко').first()).toBeVisible();
  await phone.press('Заметка к упражнению…');
  await phone.see('Заметка к упражнению');
  await phone.fill(page.getByRole('textbox').last(), 'Локти ближе к корпусу', 0);
  await phone.press('Сохранить');
  await phone.see('Локти ближе к корпусу');
});

test('владелец: второй проход, незапланированный круг и заминка', async () => {
  await phone.rate('Жим гантелей лёжа', 'Норм');
  await expect(phone.button('Скрыть')).toBeVisible();
  // Замена: вес и повторы пустые — набрать
  await phone.set('Тяга гантели в наклоне', 0, 14);
  await phone.set('Тяга гантели в наклоне', 1, 10);
  await phone.rate('Тяга гантели в наклоне', 'Норм');
  await phone.rate('Планка', 'Легко');

  // Ещё круг, которого нет в плане: первое упражнение — из справочника
  await phone.press('+ круг');
  await phone.see('Новый круг: первое упражнение');
  await phone.fill(page.getByRole('textbox').last(), 'Выпады', 0);
  await phone.tap(phone.button(/^Выпады/));
  await phone.set('Выпады', 0, 10);
  await phone.set('Выпады', 1, 12);
  await phone.rate('Выпады', 'Норм');

  await phone.rate('Растяжка', 'Легко');
});

test('владелец: завершение — итог, комментарий, вес и фото', async () => {
  await phone.tap(phone.button('Завершить').first());
  await phone.see('Завершить тренировку?');
  await phone.press('Завершить');
  await phone.see('Тренировка завершена');
  await phone.see('1 заменено');
  await phone.see('1 добавлено');

  await phone.fill(page.getByRole('textbox').first(), 'Круг зашёл, гоблет тяжело', 0);
  // Вес после тренировки — замер, привязанный к ней; запятая — как на русской клавиатуре
  await phone.fillBelow('ВЕС ПОСЛЕ ТРЕНИРОВКИ', '81,5', 0);
  await phone.press('Записать');
  await phone.see('Записан');

  // Фото без системного диалога: файл готов заранее, pickPhoto подменён на usePhoto (index.html)
  await page.evaluate(async () => {
    const c = document.createElement('canvas');
    c.width = 60;
    c.height = 80;
    const g = c.getContext('2d');
    g.fillStyle = '#5acf7e';
    g.fillRect(0, 0, 60, 80);
    const blob = await new Promise((r) => c.toBlob(r, 'image/jpeg', 0.9));
    const f = new File([blob], 'e2e.jpg', { type: 'image/jpeg', lastModified: Date.now() });
    window.bridge.pickPhoto = (camera) => window.bridge.usePhoto(f, camera);
  });
  await phone.press('Камера');
  await phone.see('Фото к тренировке');
  await phone.press('Сохранить');
  await expect.poll(() => photoPuts.length, { timeout: 45_000 }).toBe(1);
  expect(photoPuts[0]).toContain('workoutId=');
  await phone.gone('Фото к тренировке');

  await phone.press('Готово');
  await phone.see('СЕГОДНЯ');
});

test('агент: compare и workout get видят всё сделанное', async () => {
  // Очередь телефона уходит сама — ждём, пока сервер увидит законченную тренировку с комментарием
  await expect.poll(() => {
    const w = gym('workout', 'list', '--from', today()).find((x) => x.planId === planId);
    workoutId = w?.id;
    return Boolean(w?.finishedAt && w?.comment);
  }, { timeout: 60_000, intervals: [1000] }).toBe(true);

  const c = gym('compare', workoutId);
  expect(c.comment).toBe('Круг зашёл, гоблет тяжело');
  expect(c.date).toBe(today());
  expect(c.heightCm).toBe(181);
  expect(c.bodyweightKg).toBe(81.5);
  expect(c.measurements).toEqual([expect.objectContaining({ type: 'weight', value: 81.5, workoutId })]);

  const items = c.blocks.flatMap((b) => b.items.map((i) => ({ ...i, role: b.role })));
  const item = (id) => items.find((i) => i.exerciseId === id);
  const facts = (i) => i.sets.filter((s) => s.fact).map((s) => s.fact);

  expect(facts(item(ex.rope))).toEqual([expect.objectContaining({ seconds: 60, rating: 'NORMAL' })]);

  const press = item(ex.press);
  expect(press.liked).toBe(true);
  expect(press.comment).toBe('Локти ближе к корпусу');
  // Добавлен ровно один подход сверх плана, третий плановый не сделан
  expect(press.sets.map((s) => s.status)).toEqual(['DONE', 'DONE', 'SKIPPED', 'ADDED']);
  expect(facts(press)).toEqual([
    expect.objectContaining({ weight: 20, reps: 11, rating: 'EASY' }),
    expect.objectContaining({ weight: 20, reps: 12, rating: 'NORMAL' }),
    expect.objectContaining({ rating: 'HARD' }),
  ]);

  // Удалённый подход гоблета — пропущен, фактов нет
  expect(item(ex.squat).sets.map((s) => s.status)).toEqual(['SKIPPED', 'SKIPPED', 'SKIPPED']);

  const row = item(ex.row);
  expect(row.replacedFrom).toBe(ex.pushup);
  expect(facts(row)).toEqual(expect.arrayContaining([expect.objectContaining({ weight: 14, reps: 10, rating: 'NORMAL' })]));

  const plank = item(ex.plank);
  expect(facts(plank)).toEqual([
    expect.objectContaining({ seconds: 90, rating: 'NORMAL' }),
    expect.objectContaining({ seconds: 45, rating: 'EASY' }),
  ]);

  const lunge = item(ex.lunge);
  expect(lunge.added).toBe(true);
  expect(lunge.role).toBe('MAIN');
  expect(facts(lunge)).toEqual([expect.objectContaining({ weight: 10, reps: 12, rating: 'NORMAL' })]);

  expect(facts(item(ex.stretch))).toEqual([expect.objectContaining({ seconds: 60, rating: 'EASY' })]);

  // workout get: подходы с сервера без дублей; отжимание до замены осталось за старым упражнением
  const w = gym('workout', 'get', workoutId);
  const live = w.sets.filter((s) => !s.deleted && s.done);
  const keys = live.map((s) => `${s.itemId}-${s.setNo}`);
  expect(new Set(keys).size).toBe(keys.length);
  expect(live.filter((s) => s.exerciseId === ex.pushup)).toEqual([expect.objectContaining({ reps: 12, rating: 'NORMAL' })]);

  expect(gym('measure', 'list', '--workout', workoutId)).toEqual([expect.objectContaining({ type: 'weight', value: 81.5 })]);
  expect(gym('photo', 'list', '--workout', workoutId)).toHaveLength(1);
});
