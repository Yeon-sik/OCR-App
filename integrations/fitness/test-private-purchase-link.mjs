import { readFileSync, readdirSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';

const runtime = process.argv[2];
if (!runtime) throw new Error('Pass an installed @electric-sql/pglite directory');
const { PGlite } = await import(pathToFileURL(join(resolve(runtime), 'dist/index.js')).href);
const db = new PGlite();
try {
  // Minimal existing Fitness schema contract. These are NOT deployment migrations.
  await db.exec(`create role anon; create role authenticated; create schema auth;
    create function auth.uid() returns uuid language sql stable as $$select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;
    grant usage on schema auth to authenticated,anon;
    create table public.nutrition_foods(id text primary key,owner_id text,visibility text,kind text,source_type text,deleted_at timestamptz,calories_kcal int);
    create table public.nutrition_canonical_imports(id uuid primary key,owner_id text,nutrition_food_id text,input_contract text,projection_source_type text,user_verified boolean,provenance jsonb);
    insert into public.nutrition_foods values
      ('food-1','00000000-0000-4000-8000-000000000011','private','external_menu','food_image_estimate',null,600),
      ('food-2','00000000-0000-4000-8000-000000000011','private','external_menu','food_image_estimate',null,400),
      ('food-3','00000000-0000-4000-8000-000000000011','private','external_menu','food_image_estimate',null,650),
      ('food-4','00000000-0000-4000-8000-000000000011','private','external_menu','food_image_estimate',null,420);
    insert into public.nutrition_canonical_imports
      select ('00000000-0000-4000-8000-00000000000'||n)::uuid,'00000000-0000-4000-8000-000000000011','food-'||n,
        'food-estimate.v1','food_image_estimate',true,jsonb_build_object('purchase_record_client_key','purchase-'||case when n<=2 then 1 else 2 end,'purchase_line_key','line-'||(1+(n-1)%2),'nutrition_client_key','food-'||n)
      from generate_series(1,4) n;`);
  const before = await db.query('select * from nutrition_foods order by id');
  for (const file of readdirSync(join(import.meta.dirname,'supabase/migrations')).sort()) {
    await db.exec(readFileSync(join(import.meta.dirname,'supabase/migrations',file),'utf8'));
  }
  const uid = n => `00000000-0000-4000-8000-${String(n).padStart(12,'0')}`;
  const link = (n, line = `line-${1+(n-1)%2}`, menu = uid(31+(n-1)%2)) => db.query(`select public.link_private_ocr_purchase_nutrition_v1($1,$2,$3,$4,$5,$6,$7,$8,$9) as result`,
    [uid(n),`food-${n}`,`food-${n}`,n<=2?'purchase-1':'purchase-2',line,uid(n<=2?20:25),uid(21),uid(22),menu]);
  await db.exec(`set role authenticated; select set_config('request.jwt.claim.sub','${uid(11)}',false)`);
  const first = await link(1); const second = await link(2);
  assert.deepEqual(await link(1), first); assert.deepEqual(await link(2), second);
  assert.deepEqual(await link(3), await link(3)); assert.deepEqual(await link(4), await link(4));
  assert.equal(first.rows[0].result.visibility, 'private');
  assert.equal(first.rows[0].result.authority_status, 'owner_confirmed');
  assert.equal((await db.query('select count(*)::int n from nutrition_private_purchase_links')).rows[0].n, 4);
  const counts = (await db.query('select count(distinct restaurant_id)::int restaurants, count(distinct restaurant_menu_id)::int menus, count(distinct purchase_source_id)::int sources, count(distinct nutrition_food_id)::int foods from nutrition_private_purchase_links')).rows[0];
  assert.deepEqual(counts,{restaurants:1,menus:2,sources:2,foods:4});
  await assert.rejects(link(1,'line-2'), e=>e.code==='22023');
  await assert.rejects(link(1,'line-1',uid(99)), e=>e.code==='23505');
  await assert.rejects(db.exec('delete from nutrition_private_purchase_links'),e=>e.code==='42501');
  await db.exec(`select set_config('request.jwt.claim.sub','${uid(12)}',false)`);
  assert.deepEqual((await db.query('select * from nutrition_private_purchase_links')).rows, []);
  await assert.rejects(link(1),e=>e.code==='42501');
  await db.exec('set role anon');
  await assert.rejects(link(1),e=>e.code==='42501');
  await db.exec('reset role');
  assert.deepEqual(await db.query('select * from nutrition_foods order by id'), before);
  assert.equal((await db.query('select count(*)::int n from nutrition_canonical_imports')).rows[0].n,4);
  console.log('FITNESS_PRIVATE_PURCHASE_LINK_PASS: four independent artifacts, reused restaurant/two menus, replay, owner/anon ACL, unchanged nutrition and visibility');
} finally { await db.close(); }
