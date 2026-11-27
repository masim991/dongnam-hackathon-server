import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { parseApplyOptions, readMigrationFiles, planMigrations, executeMigrationPlan, writePendingSnapshot } from '../scripts/db-migration-lib.mjs';

const file = (id, sql = 'SELECT 1;') => ({ id, sql, sha256: createHash('sha256').update(sql).digest('hex') });

test('database writes require an explicit target and --apply; remote plans default to read-only', () => {
  for (const args of [[], ['--local', '--remote'], ['--remote', '--apply', '--dry-run'], ['--local', '--unknown'], ['--local', '--max-files', '0']]) assert.throws(() => parseApplyOptions(args));
  assert.equal(parseApplyOptions(['--remote']).dryRun, true);
  assert.equal(parseApplyOptions(['--local']).dryRun, true);
  assert.equal(parseApplyOptions(['--remote', '--apply']).dryRun, false);
  assert.equal(parseApplyOptions(['--local', '--pending', '--max-files', '2']).maxFiles, 2);
});

test('checksums use exact UTF-8 bytes and a changed applied file stops planning', (t) => {
  const root = mkdtempSync(path.join(tmpdir(), 'dongnam-migration-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  mkdirSync(path.join(root, 'db/migrations'), { recursive: true });
  const original = Buffer.from("SELECT '해운대';\n");
  writeFileSync(path.join(root, 'db/migrations/0001.sql'), original);
  const [first] = readMigrationFiles(root, false);
  assert.equal(first.sha256, createHash('sha256').update(original).digest('hex'));
  assert.equal(planMigrations([first], [{ id: first.id, sha256: first.sha256 }])[0].status, 'applied');
  writeFileSync(path.join(root, 'db/migrations/0001.sql'), "SELECT '해운대 수정';\n");
  assert.throws(() => planMigrations(readMigrationFiles(root, false), [{ id: first.id, sha256: first.sha256 }]), /내용이 바뀌/);
  writeFileSync(path.join(root, 'db/migrations/0001.sql'), "SELECT '해운대';\r\n");
  assert.throws(() => readMigrationFiles(root, false), /LF/);
});

test('legacy records block writes until explicitly baselined; baseline never runs data SQL', async () => {
  const legacy = file('old.sql', 'DELETE FROM course;');
  const plan = planMigrations([legacy, file('new.sql')], [{ id: legacy.id, sha256: null }]);
  const calls = [];
  await assert.rejects(executeMigrationPlan(plan, { applySql: (sql) => calls.push(sql) }), /baseline-legacy/);
  assert.equal(calls.length, 0);
  const result = await executeMigrationPlan(plan, { baselineLegacy: true, applySql: (sql) => calls.push(sql) });
  assert.deepEqual(result.baselined, ['old.sql']);
  assert.match(calls[0], /UPDATE schema_migration/);
  assert.doesNotMatch(calls[0], /DELETE FROM|SELECT 1/);
});

test('new context snapshots preserve the previous SQL and identical content does not generate another version', (t) => {
  const root = mkdtempSync(path.join(tmpdir(), 'dongnam-snapshot-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const oldFile = writePendingSnapshot(root, 'context', 'SELECT 1;', new Date('2026-11-27T01:00:00Z'));
  const newFile = writePendingSnapshot(root, 'context', 'SELECT 2;', new Date('2026-11-27T02:00:00Z'));
  assert.notEqual(oldFile, newFile);
  assert.ok(oldFile < newFile);
  assert.equal(readFileSync(path.join(root, oldFile), 'utf8'), '-- dongnam-snapshot: context\nSELECT 1;');
  assert.equal(writePendingSnapshot(root, 'context', 'SELECT 2;'), newFile);
  assert.equal(readdirSync(path.join(root, 'db/pending')).length, 2);
});

test('full snapshots supersede older pending versions, including A→B→A and a backwards clock', (t) => {
  const root = mkdtempSync(path.join(tmpdir(), 'dongnam-snapshot-order-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  mkdirSync(path.join(root, 'db/migrations'), { recursive: true });
  const oldA = writePendingSnapshot(root, 'context', 'SELECT 1;', new Date('2026-11-27T01:00:00Z'));
  const b = writePendingSnapshot(root, 'context', 'SELECT 2;', new Date('2026-11-27T02:00:00Z'));
  const newA = writePendingSnapshot(root, 'context', 'SELECT 1;', new Date('2026-11-26T01:00:00Z'));
  assert.ok(newA > b);
  const files = readMigrationFiles(root, true);
  const first = files.find((f) => f.file === oldA);
  const plan = planMigrations(files, [{ id: first.id, sha256: first.sha256 }]);
  assert.equal(plan.find((f) => f.file === b).status, 'superseded');
  assert.deepEqual(plan.filter((f) => f.status === 'pending').map((f) => f.file), [newA]);
  assert.deepEqual(planMigrations(files, []).filter((f) => f.status === 'pending').map((f) => f.file), [newA]);
  assert.equal(writePendingSnapshot(root, 'context', 'SELECT 1;'), newA);
});

test('a failed file stops the run; successful files are skipped and remaining files resume', async () => {
  const files = ['one.sql', 'two.sql', 'three.sql'].map((id) => file(id));
  const records = [];
  let calls = 0;
  await assert.rejects(executeMigrationPlan(planMigrations(files, records), { applySql() {
    if (++calls === 2) throw new Error('quota');
    records.push({ id: files[0].id, sha256: files[0].sha256 });
  } }), /quota/);
  assert.equal(calls, 2);
  const resumed = await executeMigrationPlan(planMigrations(files, records), { maxFiles: 1, applySql() {} });
  assert.deepEqual(resumed.applied, ['two.sql']);
});

test('local D1: 100,000 rows apply once, and failed data or ledger writes roll back together', async (t) => {
  const mf = new Miniflare(convertV4MiniflareOptions({ modules: true, script: 'export default {fetch(){return new Response("test")}}', d1Databases: ['DB'] }));
  t.after(() => mf.dispose());
  const db = await mf.getD1Database('DB');
  await db.batch([
    db.prepare('CREATE TABLE schema_migration(id TEXT PRIMARY KEY, sha256 TEXT)'),
    db.prepare('CREATE TABLE sample(id INTEGER PRIMARY KEY, label TEXT)'),
  ]);
  // These fixtures have no semicolons inside literals; production uses Wrangler's SQL splitter.
  const applySql = (sql) => db.batch(sql.split(';').map((s) => s.trim()).filter(Boolean).map((s) => db.prepare(s)));
  const large = file('100k.sql', "WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<100000) INSERT INTO sample SELECT x, '해운대' FROM n;");
  const initial = await executeMigrationPlan(planMigrations([large], []), { applySql });
  assert.deepEqual(initial.applied, ['100k.sql']);
  assert.equal(await db.prepare('SELECT COUNT(*) FROM sample').first('COUNT(*)'), 100000);
  const records = (await db.prepare('SELECT * FROM schema_migration').all()).results;
  const repeat = await executeMigrationPlan(planMigrations([large], records), { applySql });
  assert.deepEqual(repeat.applied, []);
  const broken = file('broken.sql', "DELETE FROM sample; INSERT INTO missing_table VALUES(1);");
  await assert.rejects(executeMigrationPlan(planMigrations([broken], records), { applySql }));
  assert.equal(await db.prepare('SELECT COUNT(*) FROM sample').first('COUNT(*)'), 100000);
  assert.equal(await db.prepare("SELECT COUNT(*) FROM schema_migration WHERE id='broken.sql'").first('COUNT(*)'), 0);
  // A concurrent runner's duplicate ledger insert must also undo that runner's preceding data changes.
  const duplicateLedger = { ...large, sql: 'DELETE FROM sample;' };
  await assert.rejects(executeMigrationPlan([{ ...duplicateLedger, status: 'pending' }], { applySql }));
  assert.equal(await db.prepare('SELECT COUNT(*) FROM sample').first('COUNT(*)'), 100000);
});
