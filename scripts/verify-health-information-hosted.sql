-- Role/RLS integration smoke against the actual hosted schema. All test writes roll back.
begin;
do $$
declare uid uuid; payload json;
begin
  select id into uid from auth.users order by created_at limit 1;
  if uid is null then raise exception 'A real owner is required for the rollback fixture'; end if;
  perform set_config('test.information_owner', uid::text, true);
  perform set_config('test.information_id', gen_random_uuid()::text, true);
  perform set_config('test.catalog_count', (select count(*)::text from public.finance_ledger_entries), true);
  perform set_config('test.price_count', (select count(*)::text from public.finance_accounts), true);
  payload := json_build_object('schema_version','information-record.v1',
    'record_id',current_setting('test.information_id'),'owner_app','healthtrace',
    'record_type','healthtrace.observation','title','Rollback-only integration fixture',
    'captured_at','2026-11-06T00:00:00Z',
    'sources',json_build_array(json_build_object('source_id','test-only','kind','manual')),
    'fields',json_build_array(json_build_object('key','price','value_type','decimal','value','12345678901234567890.0010','unit','USD')),
    'references',json_build_array());
  perform set_config('test.information_payload', payload::text, true);
  perform set_config('request.jwt.claim.sub',uid::text,true);
end $$;
set local role authenticated;
do $$
declare result jsonb; count_rows integer; raw_duplicate text;
begin
  foreach raw_duplicate in array array['{"a":1,"a":2}', '{"a":1,"\u0061":2}', '[{"reading":1,"reading":2}]'] loop
    begin
      perform public.health_information_canonical(raw_duplicate::json);
      raise exception 'Duplicate object member was accepted';
    exception when invalid_parameter_value then null;
    end;
  end loop;
  begin
    perform public.health_ingest_information_record_v1('{"title":"shadow",' || substr(current_setting('test.information_payload'),2),current_setting('test.information_owner')::uuid);
    raise exception 'Duplicate payload member was accepted by RPC';
  exception when invalid_parameter_value then null;
  end;
  result := public.health_ingest_information_record_v1(current_setting('test.information_payload'),current_setting('test.information_owner')::uuid);
  if result->>'duplicate' <> 'false' then raise exception 'First import was not new'; end if;
  result := public.health_ingest_information_record_v1(current_setting('test.information_payload'),current_setting('test.information_owner')::uuid);
  if result->>'duplicate' <> 'true' then raise exception 'Retry was not a duplicate'; end if;
  begin
    perform public.health_ingest_information_record_v1(replace(current_setting('test.information_payload'),'12345678901234567890.0010','12345678901234567890.0020'),current_setting('test.information_owner')::uuid);
    raise exception 'Changed same-ID payload was accepted';
  exception when unique_violation then null;
  end;
  begin
    update public.health_information_inbox set payload_text = current_setting('test.information_payload') where record_id=current_setting('test.information_id')::uuid;
    raise exception 'Immutable payload update was allowed';
  exception when insufficient_privilege then null;
  end;
  update public.health_information_inbox set state='reviewed' where record_id=current_setting('test.information_id')::uuid;
  get diagnostics count_rows = row_count;
  if count_rows <> 1 then raise exception 'Owner state update failed'; end if;
  if (select count(*) from public.health_information_inbox where record_id=current_setting('test.information_id')::uuid) <> 1 then raise exception 'Retry created duplicates'; end if;
  if not exists(select 1 from public.health_information_inbox where record_id=current_setting('test.information_id')::uuid and payload_text like '%12345678901234567890.0010%') then raise exception 'Decimal precision changed'; end if;
end $$;
reset role;
select set_config('request.jwt.claim.sub',gen_random_uuid()::text,true);
set local role authenticated;
do $$
declare count_rows integer;
begin
  if exists(select 1 from public.health_information_inbox where record_id=current_setting('test.information_id')::uuid) then raise exception 'Foreign owner could read'; end if;
  update public.health_information_inbox set state='archived' where record_id=current_setting('test.information_id')::uuid;
  get diagnostics count_rows = row_count;
  if count_rows <> 0 then raise exception 'Foreign owner could update'; end if;
  begin
    perform public.health_ingest_information_record_v1(current_setting('test.information_payload'),current_setting('test.information_owner')::uuid);
    raise exception 'Account-switch request was accepted';
  exception when insufficient_privilege then null;
  end;
end $$;
reset role;
set local role anon;
do $$
begin
  begin
    perform 1 from public.health_information_inbox limit 1;
    raise exception 'Anonymous read was allowed';
  exception when insufficient_privilege then null;
  end;
  begin
    perform public.health_ingest_information_record_v1(current_setting('test.information_payload'),current_setting('test.information_owner')::uuid);
    raise exception 'Anonymous RPC was allowed';
  exception when insufficient_privilege then null;
  end;
end $$;
reset role;
do $$
begin
  if (select count(*)::text from public.finance_ledger_entries) <> current_setting('test.catalog_count') or
     (select count(*)::text from public.finance_accounts) <> current_setting('test.price_count') then
    raise exception 'Native catalog or price count changed';
  end if;
end $$;
rollback;
select 'passed: owner/retry/conflict/immutability/account-switch/foreign-owner/anonymous/native-counts; test writes rolled back' as verification;
