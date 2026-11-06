import { readFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const receivers = process.argv.slice(2);
if (receivers.length !== 4) throw new Error('Pass the four receiver repository paths: Fitness CashOS PriceTrace HealthTrace.');
const names = ['information-record.v1.md', 'information-record.v1.cases.json', 'information-record.v1.raw-cases.json'];
const normalize = text => text.replace(/^\uFEFF/, '').replace(/\r\n/g, '\n').trimEnd() + '\n';
for (const name of names) {
  const source = normalize(await readFile(resolve(root, 'contracts', name), 'utf8'));
  for (const repo of receivers) {
    const actual = normalize(await readFile(resolve(repo, 'contracts', name), 'utf8'));
    if (actual !== source) throw new Error(`Contract drift: ${resolve(repo, 'contracts', name)}`);
  }
  console.log(`${name}: all four mirrors match; SHA-256 ${createHash('sha256').update(source).digest('hex')}`);
}
