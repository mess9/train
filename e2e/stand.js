// Стенд e2e: Postgres в Docker и собранный сервер (jar) на своём порту. Запускается один раз
// на прогон (globalSetup), адрес и ключ — в process.env для тестов. E2E_URL задан — стенд не
// поднимается, тесты идут в готовый сервер (ключ — E2E_KEY).
import { execFileSync, spawn } from 'node:child_process';
import { mkdirSync, openSync, rmSync, writeFileSync, readFileSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
export const root = resolve(here, '..');
const work = join(here, '.stand');
const state = join(work, 'state.json');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const docker = (...args) => execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();

async function until(what, check, ms) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (await check().catch(() => false)) return;
    await sleep(300);
  }
  throw new Error(`не дождался: ${what}`);
}

export default async function setup() {
  if (process.env.E2E_URL) {
    process.env.E2E_KEY ??= 'dev-key';
    return;
  }
  const jar = join(root, 'server/build/quarkus-app/quarkus-run.jar');
  if (!existsSync(jar)) throw new Error(`нет ${jar}: сначала ./gradlew :server:quarkusBuild :cli:installDist (или ./gradlew e2e)`);
  const port = process.env.E2E_PORT ?? '7413';
  const key = 'e2e-key';

  rmSync(work, { recursive: true, force: true, maxRetries: 5, retryDelay: 500 });
  mkdirSync(join(work, 'photos'), { recursive: true });

  // База каждый раз новая: прогон не зависит от прошлого
  const pg = `train-e2e-pg-${process.pid}`;
  docker('run', '-d', '--rm', '--name', pg, '-e', 'POSTGRES_USER=train', '-e', 'POSTGRES_PASSWORD=train',
    '-e', 'POSTGRES_DB=train', '-p', '127.0.0.1::5432', 'postgres:17-alpine');
  writeFileSync(state, JSON.stringify({ pg }));
  const pgPort = docker('port', pg, '5432/tcp').split('\n')[0].split(':').pop();
  // -h 127.0.0.1: временный сервер initdb слушает только сокет — по TCP готов уже настоящий
  await until('Postgres', async () => { docker('exec', pg, 'pg_isready', '-h', '127.0.0.1', '-U', 'train'); return true; }, 60_000);

  const log = openSync(join(work, 'server.log'), 'w');
  const server = spawn('java', ['-jar', jar], {
    cwd: root,
    stdio: ['ignore', log, log],
    env: {
      ...process.env,
      QUARKUS_HTTP_PORT: port,
      QUARKUS_DATASOURCE_JDBC_URL: `jdbc:postgresql://127.0.0.1:${pgPort}/train`,
      QUARKUS_DATASOURCE_USERNAME: 'train',
      QUARKUS_DATASOURCE_PASSWORD: 'train',
      TRAIN_BOOTSTRAP_EMAIL: 'e2e@local',
      TRAIN_BOOTSTRAP_KEY: key,
      TRAIN_PHOTOS_PATH: join(work, 'photos'),
    },
  });
  writeFileSync(state, JSON.stringify({ pg, server: server.pid }));
  let exited = null;
  server.on('exit', (code) => { exited = code; });
  const url = `http://127.0.0.1:${port}`;
  await until('сервер', async () => {
    if (exited !== null) throw new Error(`сервер вышел с кодом ${exited}, см. e2e/.stand/server.log`);
    return (await fetch(`${url}/q/health/ready`)).ok;
  }, 60_000).catch((e) => { throw new Error(`${e.message}; порт ${port} занят? см. e2e/.stand/server.log`); });
  process.env.E2E_URL = url;
  process.env.E2E_KEY = key;
}

export function teardown() {
  if (!existsSync(state)) return;
  const { pg, server } = JSON.parse(readFileSync(state, 'utf8'));
  // Дерево целиком: `java` из PATH на Windows — лаунчер Oracle, сервер — его дочерний процесс
  if (server) try {
    if (process.platform === 'win32') execFileSync('taskkill', ['/PID', String(server), '/T', '/F'], { stdio: 'ignore' });
    else process.kill(server);
  } catch { /* уже вышел */ }
  if (pg) try { docker('stop', pg); } catch { /* уже остановлен */ }
  rmSync(state, { force: true });
}
