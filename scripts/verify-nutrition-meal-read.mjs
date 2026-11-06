import assert from 'node:assert/strict';
import { readInformationServiceConfig } from './information-runtime-config.mjs';

// Validate the Android receiver's actual embedded relationship shape, without writing or printing meals.
const { url, key, email, password } = readInformationServiceConfig(process.argv[2], 'nutrition');
const login = await fetch(`${url}/auth/v1/token?grant_type=password`, {
  method: 'POST', headers: { apikey: key, 'Content-Type': 'application/json' },
  body: JSON.stringify({ email, password }), signal: AbortSignal.timeout(15000),
});
assert.equal(login.status, 200, `Nutrition sign-in failed (${login.status})`);
const session = await login.json();
const query = new URLSearchParams({
  select: '*,meal_record_items(*,meal_record_item_nutrients(*)),meal_verified_imports(*)',
  owner_id: `eq.${session.user.id}`, order: 'id.desc', limit: '10',
});
const response = await fetch(`${url}/rest/v1/meal_records?${query}`, {
  headers: { apikey: key, Authorization: `Bearer ${session.access_token}` }, signal: AbortSignal.timeout(15000),
});
assert.equal(response.status, 200, `Embedded Meal API unavailable (${response.status})`);
const records = await response.json();
assert.ok(Array.isArray(records));
let items = 0, nutrients = 0, receipts = 0, nullValues = 0;
for (const meal of records) {
  assert.equal(meal.owner_id, session.user.id, 'Foreign meal owner');
  assert.equal(meal.scope, 'fitness', 'Unexpected meal scope');
  assert.ok(Array.isArray(meal.meal_record_items), 'Embedded items are not an array');
  nullValues += Object.values(meal).filter(value => value === null).length;
  for (const item of meal.meal_record_items) {
    items++;
    assert.equal(item.owner_id, session.user.id, 'Foreign item owner');
    assert.equal(item.meal_record_id, meal.id, 'Item parent mismatch');
    assert.ok(Array.isArray(item.meal_record_item_nutrients), 'Embedded nutrients are not an array');
    for (const nutrient of item.meal_record_item_nutrients) {
      nutrients++;
      assert.equal(nutrient.owner_id, session.user.id, 'Foreign nutrient owner');
      assert.equal(nutrient.meal_record_item_id, item.id, 'Nutrient parent mismatch');
    }
  }
  if (meal.meal_verified_imports !== null) {
    assert.ok(typeof meal.meal_verified_imports === 'object' && !Array.isArray(meal.meal_verified_imports), 'Import receipt is not an object/null');
    assert.equal(meal.meal_verified_imports.owner_id, session.user.id, 'Foreign receipt owner');
    assert.equal(meal.meal_verified_imports.meal_record_id, meal.id, 'Receipt parent mismatch');
    receipts++;
  }
}
console.log(JSON.stringify({ status: response.status, meals: records.length, items, nutrients, receipts, null_values_preserved_by_api: nullValues, writes: 0 }));
