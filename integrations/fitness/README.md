# Private OCR purchase Nutrition linkage

This package targets the **Fitness Nutrition Supabase project**. Do not apply it to OCR Evidence or PriceTrace. It accompanies the OCR-App and PriceTrace `feat/user-confirmed-purchase-restaurants` changes without altering the separate Fitness checkout.

The verified target is `eytwkufdesbarxusifzt` (OCR runtime `NUTRITION_SUPABASE_URL`). OCR's linked `haxbzkwvybiebpogaouv` is **Evidence**, not Nutrition. On 2026-10-13, both this migration's DDL and history row were absent. Automatic approval review blocked permanent shared-DB application; live PostgREST still returns `404 / PGRST202`. This is not a deployed linkage or a merge-ready release.

Apply `supabase/migrations/20261013100505_private_ocr_purchase_nutrition_link.sql` through the Fitness project's reviewed forward-migration process. The target must already have `nutrition_canonical_imports` and `nutrition_foods` (including V5 purchase provenance). The existing public dining-out publication RPC remains unchanged.

`link_private_ocr_purchase_nutrition_v1` accepts only an existing owner verified `food-estimate.v1` import/food, its three saved canonical correlation keys, and the PT runtime purchase/restaurant/location/menu references. It creates one owner-only row in `nutrition_private_purchase_links`, returns `visibility=private`, `authority_status=owner_confirmed`, `linked=true`, and echoes the validated selectors. A different reference for an existing canonical import conflicts. There is no nutrient input, re-import, public link, visibility change or global identity approval.

PT and Fitness are separate databases. PT UUIDs here are opaque **private external references**, not signed global authority. RLS protects the owner artifact; a reference in this table cannot make any food/menu public. OCR obtains these references only from owner-authenticated PriceTrace recovery metadata. The canonical JSON remains source-only.

Local verification:

```powershell
node integrations/fitness/test-private-purchase-link.mjs C:/Github/창팡맨/node_modules/@electric-sql/pglite
```

The harness uses minimal existing Fitness table contracts and real PostgreSQL roles/RLS. It checks four independent artifacts from two purchases, four links to one reused private restaurant and two reused menus, replay, conflict, foreign-owner denial, anonymous denial, unchanged nutrients and unchanged private visibility. Nutrition artifacts are never merged merely because restaurant/menu identity is reused.

`verify-existing-imports-rollback.sql` is an optional live SQL fixture. Execute it **after the new migration inside the same BEGIN/ROLLBACK transaction**, never as a persistent data update. It selects two existing eligible private imports and verifies linkage/replay/owner isolation without creating or changing nutrition artifacts. On 2026-10-13 this test passed against the linked Fitness project and the new table was confirmed absent after rollback. This does not prove PostgREST/JWT, GUI/device behavior or permanent deployment.

Deployment order: reconcile the existing PriceTrace menu-candidate prerequisite, apply PriceTrace's new owner-confirmed purchase migration, apply this migration to Fitness Nutrition, then update OCR-App. The migrations were tested but have **not been permanently deployed**. Existing data and successful CashOS checkpoints were not changed.

The reviewed dry-run deployment wrapper fetches the real target migration SQL/history to a fresh temporary project and copies only the missing private-link migration. It never fabricates history rows, repairs unrelated versions, changes vault secrets, or applies seeds/roles:

```powershell
./integrations/fitness/deploy-private-link.ps1 -SupabaseCli ./node_modules/.bin/supabase.cmd
# After explicit deployment approval, add -Apply to the same command.
```

The PriceTrace wrapper is `scripts/deploy-owner-purchase.ps1` in that repository; its dry run selects exactly the menu-candidate prerequisite and owner purchase migration. Once both targets are deployed, run `node C:/Github/창팡맨/scripts/smoke-owner-purchase-http.mjs C:/Github/PriceTrace_OCR_APP/.env`. That smoke logs only host/RPC/status/code and checks anonymous execution denial, without writing user artifacts or logging secrets. Signed-owner HTTP success remains a separate verification requirement.

When the RPC is missing, OCR retains existing canonical import/food selectors in pending metadata (`fitness_private_purchase_link_not_deployed`). Retrying after deployment reuses those artifacts and CashOS checkpoints, then creates the missing private links. Core tests cover this transition and do not invoke public publication.
