# Private OCR purchase Nutrition linkage

This package targets the **Fitness Nutrition Supabase project**. Do not apply it to OCR Evidence or PriceTrace. It accompanies the OCR-App and PriceTrace `feat/user-confirmed-purchase-restaurants` changes without altering the separate Fitness checkout.

Apply `supabase/migrations/20261013100505_private_ocr_purchase_nutrition_link.sql` through the Fitness project's reviewed forward-migration process. The target must already have `nutrition_canonical_imports` and `nutrition_foods` (including V5 purchase provenance). The existing public dining-out publication RPC remains unchanged.

`link_private_ocr_purchase_nutrition_v1` accepts only an existing owner verified `food-estimate.v1` import/food, its three saved canonical correlation keys, and the PT runtime purchase/restaurant/location/menu references. It creates one owner-only row in `nutrition_private_purchase_links`, returns `visibility=private`, `authority_status=owner_confirmed`, `linked=true`, and echoes the validated selectors. A different reference for an existing canonical import conflicts. There is no nutrient input, re-import, public link, visibility change or global identity approval.

PT and Fitness are separate databases. PT UUIDs here are opaque **private external references**, not signed global authority. RLS protects the owner artifact; a reference in this table cannot make any food/menu public. OCR obtains these references only from owner-authenticated PriceTrace recovery metadata. The canonical JSON remains source-only.

Local verification:

```powershell
node integrations/fitness/test-private-purchase-link.mjs C:/Github/창팡맨/node_modules/@electric-sql/pglite
```

The harness uses minimal existing Fitness table contracts and real PostgreSQL roles/RLS. It checks two reused artifacts, two links, replay, conflict, foreign-owner denial, anonymous denial, unchanged nutrients and unchanged private visibility.

`verify-existing-imports-rollback.sql` is an optional live SQL fixture. Execute it **after the new migration inside the same BEGIN/ROLLBACK transaction**, never as a persistent data update. It selects two existing eligible private imports and verifies linkage/replay/owner isolation without creating or changing nutrition artifacts. On 2026-10-13 this test passed against the linked Fitness project and the new table was confirmed absent after rollback. This does not prove PostgREST/JWT, GUI/device behavior or permanent deployment.

Deployment order: reconcile the existing PriceTrace menu-candidate prerequisite, apply PriceTrace's new owner-confirmed purchase migration, apply this migration to Fitness Nutrition, then update OCR-App. The migrations were tested but have **not been permanently deployed**. Existing data and successful CashOS checkpoints were not changed.
