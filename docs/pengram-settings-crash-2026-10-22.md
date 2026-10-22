# Cold-open crash entering Pengram settings — 2026-10-22

Reported on Pengram 13.0.2 / 71789, Android 14, arm64: main-thread NPE calling
`ViewGroup.addView(View, LayoutParams)` from an obfuscated `createView`.
No R8 mapping was available locally, so no exact retrace of `org.telegram.ui.e` is claimed.

## Confirmed matching defect

`PengramSettingsActivity.createView` attached the custom search view through
`searchItem.getSearchContainer().addView(...)` before the native lazy container was created.
`ActionBarMenuItem.setIsSearchField(true)` only sets flags. `setSearchFieldHint` also does
not create the field/container. `getSearchContainer()` returns the raw nullable field.
In contrast, `getSearchField()` calls the native `checkCreateSearchField()` initializer.
The first `pengramStyleSearch()` call, which would have initialized that field, was too late.
This happens on cold creation irrespective of the MD3 preference, because attachment is unconditional.

## Fix

Call `searchItem.getSearchField()` immediately before attaching the custom child. This
initializes native controls without expanding search or requesting the keyboard. No null-check
silently drops MD3 search. The shared ActionBar getter API and other screens are unchanged.
Search listeners, keyboard behaviour, MD3 on/off styling, locale strings and search indexing
remain in place. No archived messages, sending, API ID, signing or obfuscation settings changed.

## Verification

- `scripts/test-pengram-settings-create.py` asserts the production initialization order and
  accessor semantics. When a JDK is present it compiles a minimal JVM model using the actual
  production getter bodies and attachment statements: the old order must throw NPE, the fixed
  order must initialize once and attach once across new/recreated menu instances.
- That model is **not** Android instrumentation or full activity creation. Locally javac is
  unavailable, so its JVM portion is explicitly SKIP; the preflight workflow runs it with JDK 17.
- 51/51 local Pengram source suites PASS. Search generation remains 401 rows; the lexical audit
  inventory was regenerated for the shifted source lines.
- Real javac/preflight and JVM-model CI status are reported in PR #5 after pushing this patch.
- Device verification remains required: cold launch → Settings → Pengram, root and sub-sections,
  MD3 search on/off, enter/clear/back, RU/EN/RTL and return/recreation. No device PASS claimed.
