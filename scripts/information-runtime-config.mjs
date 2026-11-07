import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

export function readInformationServiceConfig(envFile, service) {
  assert.ok(envFile, 'Provide a local env file path');
  const env = Object.fromEntries(readFileSync(envFile, 'utf8').split(/\r?\n/).flatMap(line => {
    const match = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
    if (!match) return [];
    let value = match[2];
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) value = value.slice(1, -1);
    return [[match[1], value]];
  }));
  const prefix = service.toUpperCase();
  const config = {
    url: env[`${prefix}_SUPABASE_URL`]?.replace(/\/$/, ''),
    key: env[`${prefix}_SUPABASE_PUBLISHABLE_KEY`] || env[`${prefix}_SUPABASE_ANON_KEY`],
    email: env[`${prefix}_EMAIL`], password: env[`${prefix}_PASSWORD`],
  };
  assert.ok(config.url && config.key && config.email && config.password, 'Required local configuration is missing');
  return config;
}
