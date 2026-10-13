-- Combine AFTER this package's migration, inside one BEGIN/ROLLBACK transaction.
-- Reads and links two existing owner imports only temporarily; no nutrient or import writes.
do $test$
declare
  item record; response jsonb; repeated jsonb; before_food jsonb; n integer := 0;
  source_id uuid := gen_random_uuid(); restaurant_id uuid := gen_random_uuid(); location_id uuid := gen_random_uuid(); menu_id uuid;
begin
  for item in select imported.* from public.nutrition_canonical_imports imported
    join public.nutrition_foods food on food.id = imported.nutrition_food_id and food.owner_id = imported.owner_id
    where imported.input_contract = 'food-estimate.v1' and imported.user_verified
      and imported.provenance ?& array['purchase_line_key','purchase_record_client_key','nutrition_client_key']
      and food.visibility = 'private' and food.deleted_at is null order by imported.created_at desc limit 2 loop
    n := n + 1; menu_id := gen_random_uuid();
    select to_jsonb(food) into before_food from public.nutrition_foods food where food.id = item.nutrition_food_id;
    perform set_config('request.jwt.claims',jsonb_build_object('sub',item.owner_id,'role','authenticated')::text,true);
    execute 'set local role authenticated';
    response := public.link_private_ocr_purchase_nutrition_v1(item.id,item.nutrition_food_id,
      item.provenance->>'nutrition_client_key',item.provenance->>'purchase_record_client_key',item.provenance->>'purchase_line_key',
      source_id,restaurant_id,location_id,menu_id);
    repeated := public.link_private_ocr_purchase_nutrition_v1(item.id,item.nutrition_food_id,
      item.provenance->>'nutrition_client_key',item.provenance->>'purchase_record_client_key',item.provenance->>'purchase_line_key',
      source_id,restaurant_id,location_id,menu_id);
    if response is distinct from repeated or response->>'visibility' <> 'private' or response->>'linked' <> 'true' then
      raise exception 'private existing import replay failed'; end if;
    execute 'reset role';
    if (select to_jsonb(food) from public.nutrition_foods food where food.id = item.nutrition_food_id) is distinct from before_food then
      raise exception 'existing food changed'; end if;
  end loop;
  if n <> 2 then raise exception 'two eligible existing imports required'; end if;
  perform set_config('request.jwt.claims',jsonb_build_object('sub',gen_random_uuid(),'role','authenticated')::text,true);
  execute 'set local role authenticated';
  if exists(select 1 from public.nutrition_private_purchase_links) then raise exception 'cross owner link leaked'; end if;
  execute 'reset role';
end;
$test$;
