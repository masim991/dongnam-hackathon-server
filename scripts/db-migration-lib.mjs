import { createHash } from 'node:crypto';
import { readFileSync, readdirSync, mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';

export function parseApplyOptions(args) {
  const allowed = new Set(['--local', '--remote', '--pending', '--dry-run', '--apply', '--baseline-legacy', '--max-files']);
  let maxFiles = Infinity;
  for (let i = 0; i < args.length; i++) {
    if (!allowed.has(args[i])) throw new Error('알 수 없는 옵션: ' + args[i]);
    if (args[i] === '--max-files') {
      maxFiles = Number(args[++i]);
      if (!Number.isSafeInteger(maxFiles) || maxFiles < 1) throw new Error('--max-files는 1 이상의 정수입니다.');
    }
  }
  if (args.includes('--local') === args.includes('--remote')) throw new Error('대상을 하나 지정하세요: --local 또는 --remote');
  if (args.includes('--apply') && args.includes('--dry-run')) throw new Error('--apply와 --dry-run은 함께 쓸 수 없습니다.');
  return { mode: args.includes('--local') ? '--local' : '--remote', dryRun: !args.includes('--apply'), pending: args.includes('--pending'), baselineLegacy: args.includes('--baseline-legacy'), maxFiles };
}

export function readMigrationFiles(root, pending) {
  const files = ['migrations', ...(pending ? ['pending'] : [])].flatMap((dir) =>
    readdirSync(path.join(root, 'db', dir)).filter((name) => name.endsWith('.sql')).sort().map((name) => {
      const file = `db/${dir}/${name}`;
      const bytes = readFileSync(path.join(root, file));
      if (bytes.includes(Buffer.from('\r\n'))) throw new Error(file + ': SQL 줄바꿈은 LF로 저장하세요(.gitattributes).');
      const sql = bytes.toString('utf8').replace(/^\uFEFF/, '');
      return { id: (dir === 'pending' ? 'pending:' : '') + name, file, bytes: bytes.length,
        snapshotGroup: dir === 'pending' ? /^-- dongnam-snapshot: ([a-zA-Z0-9_-]+)\n/.exec(sql)?.[1] : undefined,
        sha256: createHash('sha256').update(bytes).digest('hex'), sql };
    }));
  const groups = new Set(files.map((f) => f.snapshotGroup).filter(Boolean));
  // The original context snapshot predates the header. Associate it only when a new version declares its stem.
  for (const f of files) if (f.id.startsWith('pending:') && groups.has(path.basename(f.file, '.sql'))) f.snapshotGroup = path.basename(f.file, '.sql');
  return files;
}

// Full context snapshots keep their previous versions; unchanged content reuses its existing file.
export function writePendingSnapshot(root, stem, sql, now = new Date()) {
  if (!/^[a-zA-Z0-9_-]+$/.test(stem)) throw new Error('잘못된 SQL 버전 이름입니다.');
  if (sql.includes('\r\n')) throw new Error('새 SQL은 LF 줄바꿈으로 저장하세요.');
  const dir = path.join(root, 'db/pending');
  mkdirSync(dir, { recursive: true });
  const body = `-- dongnam-snapshot: ${stem}\n` + sql;
  const hash = createHash('sha256').update(body).digest('hex');
  const names = readdirSync(dir).filter((n) => n === stem + '.sql' || (n.startsWith(stem + '_') && n.endsWith('.sql'))).sort();
  const latest = names.at(-1);
  if (latest) {
    const previous = readFileSync(path.join(dir, latest), 'utf8');
    if (previous === body || (latest === stem + '.sql' && previous === sql)) return 'db/pending/' + latest;
  }
  const lastTime = Number(latest?.slice(stem.length + 1).match(/^(\d{16})_/)?.[1] ?? 0);
  const time = Math.max(now.getTime(), lastTime + 1);
  if (!Number.isSafeInteger(time)) throw new Error('SQL 버전 시각이 유효하지 않습니다.');
  const stamp = String(time).padStart(16, '0');
  const name = `${stem}_${stamp}_${hash.slice(0, 12)}.sql`;
  writeFileSync(path.join(dir, name), body, { flag: 'wx' });
  return 'db/pending/' + name;
}

export function planMigrations(files, records) {
  const applied = new Map(records.map((r) => [r.id, r.sha256]));
  const latest = new Map();
  for (const f of files) if (f.snapshotGroup && (!latest.has(f.snapshotGroup) || latest.get(f.snapshotGroup) < f.id)) latest.set(f.snapshotGroup, f.id);
  return files.map((file) => {
    if (applied.has(file.id) && applied.get(file.id) && applied.get(file.id) !== file.sha256) {
      throw new Error('적용한 SQL의 내용이 바뀌었습니다: ' + file.id + '. 기존 파일을 복구하고 새 버전 파일을 추가하세요.');
    }
    let status = !applied.has(file.id) ? 'pending' : applied.get(file.id) ? 'applied' : 'unverified';
    if (status === 'pending' && file.snapshotGroup && latest.get(file.snapshotGroup) !== file.id) status = 'superseded';
    return { ...file, status };
  });
}

const quote = (value) => "'" + value.replace(/'/g, "''") + "'";
export function migrationBatch(file) {
  // D1 execute --file batches these statements transactionally; never record success in a second call.
  return file.sql + '\n;\nINSERT INTO schema_migration (id, sha256) VALUES (' + quote(file.id) + ', ' + quote(file.sha256) + ');\n';
}

export async function executeMigrationPlan(plan, { applySql, baselineLegacy = false, maxFiles = Infinity }) {
  const legacy = plan.filter((f) => f.status === 'unverified');
  if (legacy.length && !baselineLegacy) throw new Error('해시 없는 기존 이력을 먼저 검토하고 --baseline-legacy --apply로 기준을 등록하세요: ' + legacy.map((f) => f.id).join(', '));
  if (baselineLegacy) {
    if (legacy.length) await applySql(legacy.map((f) => `UPDATE schema_migration SET sha256=${quote(f.sha256)} WHERE id=${quote(f.id)} AND sha256 IS NULL;`).join('\n'));
    return { applied: [], baselined: legacy.map((f) => f.id) };
  }
  const applied = [];
  for (const file of plan.filter((f) => f.status === 'pending').slice(0, maxFiles)) {
    await applySql(migrationBatch(file)); // A failed file stops the run; completed files are skipped on the next run.
    applied.push(file.id);
  }
  return { applied, baselined: [] };
}
