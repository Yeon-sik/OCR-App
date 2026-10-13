-- Apply to the Fitness Nutrition project, never the PriceTrace/Evidence database.
-- A private external reference is NOT a global verified menu or publication grant.
create table public.nutrition_private_purchase_links (
  id uuid primary key default gen_random_uuid(),
  owner_id text not null,
  canonical_import_id uuid not null unique references public.nutrition_canonical_imports(id),
  nutrition_food_id text not null references public.nutrition_foods(id),
  nutrition_client_key text not null,
  purchase_record_client_key text not null,
  purchase_line_key text not null,
  purchase_source_id uuid not null,
  restaurant_id uuid not null,
  restaurant_location_id uuid not null,
  restaurant_menu_id uuid not null,
  created_at timestamptz not null default now()
);
create index nutrition_private_purchase_links_owner_idx on public.nutrition_private_purchase_links(owner_id, nutrition_food_id);
alter table public.nutrition_private_purchase_links enable row level security;
revoke all on public.nutrition_private_purchase_links from public, anon, authenticated;
grant select on public.nutrition_private_purchase_links to authenticated;
create policy owner_read on public.nutrition_private_purchase_links for select to authenticated
  using (owner_id = (select auth.uid())::text);

create function public.link_private_ocr_purchase_nutrition_v1(
  p_canonical_import_id uuid, p_nutrition_food_id text, p_nutrition_client_key text,
  p_purchase_record_client_key text, p_purchase_line_key text, p_purchase_source_id uuid,
  p_restaurant_id uuid, p_restaurant_location_id uuid, p_restaurant_menu_id uuid
)
returns jsonb language plpgsql security definer set search_path = '' as $function$
declare
  v_owner text := (select auth.uid())::text; v_import public.nutrition_canonical_imports%rowtype;
  v_saved public.nutrition_private_purchase_links%rowtype; v_request jsonb;
begin
  if v_owner is null then raise exception 'authenticated nutrition owner required' using errcode = '42501'; end if;
  if p_purchase_source_id is null or p_restaurant_id is null or p_restaurant_location_id is null or p_restaurant_menu_id is null
    or nullif(btrim(p_nutrition_client_key), '') is null or nullif(btrim(p_purchase_record_client_key), '') is null
    or nullif(btrim(p_purchase_line_key), '') is null then
    raise exception 'complete private purchase reference required' using errcode = '22023'; end if;
  select imported.* into v_import from public.nutrition_canonical_imports imported
    where imported.id = p_canonical_import_id and imported.owner_id = v_owner
      and imported.nutrition_food_id = p_nutrition_food_id and imported.user_verified
      and imported.input_contract = 'food-estimate.v1' and imported.projection_source_type = 'food_image_estimate' for update;
  if not found then raise exception 'verified owner nutrition import not available' using errcode = '42501'; end if;
  if v_import.provenance ->> 'purchase_record_client_key' is distinct from p_purchase_record_client_key
    or v_import.provenance ->> 'purchase_line_key' is distinct from p_purchase_line_key
    or v_import.provenance ->> 'nutrition_client_key' is distinct from p_nutrition_client_key then
    raise exception 'purchase link does not match saved canonical import' using errcode = '22023'; end if;
  if not exists(select 1 from public.nutrition_foods food where food.id = p_nutrition_food_id and food.owner_id = v_owner
      and food.visibility = 'private' and food.kind = 'external_menu' and food.source_type = 'food_image_estimate'
      and food.deleted_at is null) then
    raise exception 'private owner food required' using errcode = '42501'; end if;
  v_request := jsonb_build_object('canonical_import_id', p_canonical_import_id, 'nutrition_food_id', p_nutrition_food_id,
    'nutrition_client_key', p_nutrition_client_key, 'purchase_record_client_key', p_purchase_record_client_key,
    'purchase_line_key', p_purchase_line_key, 'purchase_source_id', p_purchase_source_id,
    'restaurant_id', p_restaurant_id, 'restaurant_location_id', p_restaurant_location_id, 'restaurant_menu_id', p_restaurant_menu_id);
  select * into v_saved from public.nutrition_private_purchase_links where canonical_import_id = v_import.id;
  if found then
    if to_jsonb(v_saved) - array['id', 'owner_id', 'created_at'] is distinct from v_request then
      raise exception 'private purchase link conflicts with existing reference' using errcode = '23505'; end if;
  else
    insert into public.nutrition_private_purchase_links(owner_id, canonical_import_id, nutrition_food_id, nutrition_client_key,
      purchase_record_client_key, purchase_line_key, purchase_source_id, restaurant_id, restaurant_location_id, restaurant_menu_id)
      values(v_owner, v_import.id, p_nutrition_food_id, p_nutrition_client_key, p_purchase_record_client_key,
        p_purchase_line_key, p_purchase_source_id, p_restaurant_id, p_restaurant_location_id, p_restaurant_menu_id)
      returning * into v_saved;
  end if;
  return v_request || jsonb_build_object('link_id', v_saved.id, 'visibility', 'private', 'authority_status', 'owner_confirmed', 'linked', true);
end;
$function$;
revoke all on function public.link_private_ocr_purchase_nutrition_v1(uuid,text,text,text,text,uuid,uuid,uuid,uuid) from public, anon;
grant execute on function public.link_private_ocr_purchase_nutrition_v1(uuid,text,text,text,text,uuid,uuid,uuid,uuid) to authenticated;
comment on table public.nutrition_private_purchase_links is
  'Owner-only OCR purchase references. External PT UUIDs are opaque private references, not authenticated global authority; never read by public Nutrition publication/catalogue APIs.';
notify pgrst, 'reload schema';
