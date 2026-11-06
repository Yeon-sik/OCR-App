import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readInformationServiceConfig } from './information-runtime-config.mjs';

// Read/denial-only PostgREST smoke. Passwords/tokens/response bodies are never printed.
const [envFile, owner = 'pricetrace'] = process.argv.slice(2);
assert.ok(['pricetrace', 'cashos'].includes(owner), 'Unsupported receiver');
const { url, key, email, password } = readInformationServiceConfig(envFile, owner);
const table = owner === 'pricetrace' ? 'pricetrace_information_inbox' : 'finance_information_inbox';
const rpc = owner === 'pricetrace' ? 'ingest_pricetrace_information_record_v1' : 'finance_ingest_information_record_v1';
const anon = await fetch(`${url}/rest/v1/${table}?select=record_id&limit=1`, { headers: { apikey: key }, signal: AbortSignal.timeout(15000) });
assert.ok([401, 403].includes(anon.status), `Anonymous read unexpectedly returned ${anon.status}`);
const login = await fetch(`${url}/auth/v1/token?grant_type=password`, {
  method: 'POST', headers: { apikey: key, 'Content-Type': 'application/json' },
  body: JSON.stringify({ email, password }), signal: AbortSignal.timeout(15000),
});
assert.equal(login.status, 200, `Sign-in failed (${login.status})`);
const session = await login.json();
assert.ok(session.access_token && session.user?.id, 'Sign-in returned no session');
const headers = { apikey: key, Authorization: `Bearer ${session.access_token}`, 'Content-Type': 'application/json' };
const ownRead = await fetch(`${url}/rest/v1/${table}?select=record_id&limit=1`, { headers, signal: AbortSignal.timeout(15000) });
assert.equal(ownRead.status, 200, `Owner read failed (${ownRead.status})`);
const denied = await fetch(`${url}/rest/v1/rpc/${rpc}`, {
  method: 'POST', headers, body: JSON.stringify({ p_payload_text: '{}', p_expected_user_id: randomUUID() }), signal: AbortSignal.timeout(15000),
});
assert.equal(denied.status, 403, `Account-switch denial returned ${denied.status}`);
const malformed = await fetch(`${url}/rest/v1/rpc/${rpc}`, {
  method: 'POST', headers, body: JSON.stringify({ p_payload_text: '{}', p_expected_user_id: session.user.id }), signal: AbortSignal.timeout(15000),
});
assert.equal(malformed.status, 400, `Malformed payload denial returned ${malformed.status}`);
console.log(JSON.stringify({ receiver: owner, anonymous: anon.status, authenticated_read: ownRead.status, account_switch: denied.status, malformed: malformed.status, records_written: 0 }));
