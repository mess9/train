import { randomUUID } from 'node:crypto';
import { Buffer } from 'node:buffer';
import { deflateSync } from 'node:zlib';

/** Одинаковые тестовые данные для браузера и APK; отдельный аккаунт, без чужих рекордов. */
export async function backFixture(base, adminKey) {
  let apiKey = adminKey;
  const api = async (method, path, data, binary = false) => {
    const response = await fetch(base + '/api' + path, {
      method, headers: { 'X-Api-Key': apiKey, 'Content-Type': binary ? 'image/png' : 'application/json' },
      body: data == null ? undefined : binary ? data : JSON.stringify(data),
    });
    if (!response.ok) throw new Error(`${method} ${path}: ${response.status} ${await response.text()}`);
    return response.status === 204 ? null : response.json();
  };
  const user = await api('POST', '/admin/users', { email: `back-${randomUUID()}@e2e.local` });
  apiKey = user.key.key;
  const bench = await api('POST', '/exercises', { nameRu: 'Жим для назад', recordType: 'WEIGHT_REPS' });
  const row = await api('POST', '/exercises', { nameRu: 'Тяга для назад', recordType: 'WEIGHT_REPS' });
  const date = new Date().toLocaleDateString('en-CA');
  const plan = await api('PUT', `/plans/${randomUUID()}`, { date, title: 'E2E системная назад', body: { blocks: [{ items: [
    { exerciseId: bench.id, sets: [{ weight: 10, repsMax: 10 }, { weight: 10, repsMax: 10 }] },
    { exerciseId: row.id, sets: [{ weight: 12, repsMax: 10 }, { weight: 12, repsMax: 10 }] },
  ] }] } });
  const png = testPng();
  const photos = [];
  for (const day of ['2026-10-20', '2026-11-20']) {
    photos.push(await api('PUT', `/photos/${randomUUID()}?takenAt=${day}T12:00:00Z&pose=front`, png, true));
  }
  return { key: apiKey, bench: bench.id, row: row.id, plan: plan.id, photos, png: png.toString('base64') };
}

function testPng() {
  const width = 160, height = 240;
  const raw = Buffer.alloc((width * 4 + 1) * height);
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
    const p = y * (width * 4 + 1) + 1 + x * 4;
    raw[p] = x < 80 ? 40 : 70; raw[p + 1] = 130; raw[p + 2] = 100; raw[p + 3] = 255;
  }
  const chunk = (type, bytes) => {
    const data = Buffer.concat([Buffer.from(type), bytes]); let crc = 0xffffffff;
    for (const byte of data) { crc ^= byte; for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0); }
    const prefix = Buffer.alloc(4), suffix = Buffer.alloc(4); prefix.writeUInt32BE(bytes.length); suffix.writeUInt32BE((crc ^ 0xffffffff) >>> 0);
    return Buffer.concat([prefix, data, suffix]);
  };
  const header = Buffer.alloc(13); header.writeUInt32BE(width); header.writeUInt32BE(height, 4); header[8] = 8; header[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}
