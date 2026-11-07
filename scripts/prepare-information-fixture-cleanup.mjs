import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';

const [fixturePath, outputDirectory] = process.argv.slice(2);
assert.ok(fixturePath && outputDirectory, 'Provide the generated fixture manifest and output directory.');
const rows = JSON.parse(await readFile(fixturePath, 'utf8'));
const tables = { fitness: 'fitness_information_inbox', cashos: 'finance_information_inbox', pricetrace: 'pricetrace_information_inbox', healthtrace: 'health_information_inbox' };
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const literal = value => `'${value.replaceAll("'", "''")}'`;
await mkdir(outputDirectory, { recursive: true });
for (const row of rows) {
  assert.ok(tables[row.owner] && uuid.test(row.record_id) && uuid.test(row.user_id));
  const payload = JSON.parse(row.payload_text);
  assert.equal(payload.title, '합성 통합 시험 — 자동 정리 대상');
  assert.equal(payload.record_id, row.record_id);
  assert.equal(payload.owner_app, row.owner);
  assert.equal(createHash('sha256').update(row.payload_text).digest('hex'), row.fingerprint);
  const sql = `-- Only the exact synthetic payload emitted by the opt-in gateway test is removable.\nwith removed as (delete from public.${tables[row.owner]} where record_id=${literal(row.record_id)}::uuid and user_id=${literal(row.user_id)} and payload_text=${literal(row.payload_text)} returning record_id) select count(*) as synthetic_rows_removed from removed;\n`;
  await writeFile(resolve(outputDirectory, `cleanup-${row.owner}.sql`), sql, 'utf8');
}
console.log(`Prepared exact-payload cleanup for ${rows.length} private inbox fixtures; no database command was executed.`);
