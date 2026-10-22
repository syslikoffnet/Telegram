# Pengram: text, typing, penguin and player

2026-10-22. Base `0666b87`, working branch `arena/42ea70d0-telegram`; draft PR5 **not merged**.

## Status and evidence policy

**Source-level hardening, NOT full Android acceptance.** The user explicitly waived providing
runtime logs/screenshots and authorised code-only work after the initial diagnostic checkpoint.
There is no attached Android device/SDK here. R1–R12, P1–P8 and all 28 device scenarios are
**NOT RUN**. No screenshots, Web/Desktop comparison, frame times, fps, memory, battery or
100%-animation-success figures are invented. Debug metadata is not a substitute for a device trace.

## Six symptoms

| Symptom | Confirmed code defect | Change | Check / device |
|---|---|---|---|
| Text disappears after send | Cell drawing suppressed by enter flag; cleanup only on animation end; empty old blocks can own all fade alpha | Generation ownership, end/cancel/removal/detach/bind cleanup and 500ms watchdog; safe fade source checks | New source + extracted Java lifecycle runner; device NOT RUN |
| Long text + NFT URL | Soft wrapping increases Layout length; old code slices original shorter text with Layout range, throwing before blocks are added | Slice actual Layout text; normal display messageText adopts wrapped layout, restore original before applying entities again; plain fallback | Old range failure and repeat-generation host model; NFT branch not proven on user's message |
| Typing intermittent | Pending slot consumed before Layout validation; broad replacement/composition clearing; heuristic suppresses multiword input | Bounded pre-draw queue/retry, expected-range comparison, edit-delta invalidation and replacement-tail snapshot; composing no longer globally clears | Typing source suite + 766 Unicode reference vectors; real Gboard/glide NOT RUN |
| Penguin small/missing | `afa55a8` .97→.82 plus depth allowance compounded unconditional jump reserve | BODY_FILL .84, separate safe-envelope .98, conditional jump reserve; preserve proper dp ownership and clipping | Geometry source/envelope tests; **all-skin dp minima not certified** |
| No-cover player loses penguin | Receiver set hid placeholder before usable bitmap check; Covers silently discarded failure; multiple visibility owners | Unified four-state renderer; key+generation guard; failure callback; immediate fallback; GL logo fallback | Ownership contracts + stale callback reference model; 20 device switches NOT RUN |
| Bright player highlight | Accent54 fill/stroke70 statically confirmed under penguin; screenshot culprit cannot be identified from code | Neutral themed alpha20 without stroke; optional clipped halo; soft default; preserve active tab/queue state | Source contracts; before/after screenshots NOT AVAILABLE |

## Regression history and geometry

[afa55a8](https://github.com/syslikoffnet/Telegram/commit/afa55a855965ff9462d8bc428b63534ce52f5eac)
introduced the additional shrinkage. Its original intent was fixing density² sizing and avoiding
clipped accessories, not turning the character into an icon. The correct density fix and EGL/
SurfaceTexture ownership remain. Do not restore the old accidental oversized pixel→dp conversion.
The player box/callback paths predate this change. Blame reaches shallow boundary `^587d2c6dc`:
that is **not proof** that commit first introduced those paths.

| Parameter | Before afa55a8 | Before this patch | After |
|---|---|---|---|
| Fit fill | .97 | .82 | .98 accessory-safe envelope |
| Explicit body target | absent | absent | BODY_FILL=.84 |
| Minimum depth allowance | absent | .75 | .75 retained for perspective safety |
| Jump reservation | unconditional .26 | unconditional .26 | .26 only while jump/dance active |
| Margin | .035 | .035 | .035 |
| BASE_SIZE_DP / compact | 224 / 72 | 224 / 72 | unchanged; no density² |
| MINI / LYRICS slot | 132 / 160 | 132 / 160 | unchanged |
| Player child margins | 10 | 10 | 4 dp |
| Placeholder fill/stroke | accent54 / accent70 | same | themed20 / none |
| Halo default | normal | normal | soft for unset preference; explicit selections preserved |

**Not measurements:** camera tests check analytic static envelopes, not transformed GL vertices
or pixels. Tall hats can limit body occupancy even with BODY_FILL=.84. The requested ≥52/92/112dp
across every skin and the 70%-slider ≥.62×224dp bound are NOT established. Height/width clamping on
short screens is retained rather than overflowing the UI. No body dp/% table is labelled measured.

## Verified implementation observations (40)

Paths below start at `TMessagesProj/src/main/java/org/telegram/`.

| # | File:line | Observation |
|---|---|---|
| 1 | `ui/Cells/ChatMessageCell.java:293` | Enter transition ownership is generation-scoped; stale callbacks cannot clear a newer owner. |
| 2 | `ui/Cells/ChatMessageCell.java:302` | A cached 500ms watchdog clears the cell flag without allocating a Runnable per frame. |
| 3 | `ui/Cells/ChatMessageCell.java:294` | Crossfade watchdog restores normal text drawing, not global media-animation progress. |
| 4 | `ui/Cells/ChatMessageCell.java:6454` | Detach clears enter flag and removes crossfade watchdog. |
| 5 | `ui/Cells/ChatMessageCell.java:13460` | Binding clears enter ownership; native sender/network code is untouched. |
| 6 | `ui/Cells/ChatMessageCell.java:28900` | Empty old blocks cannot initiate message-text crossfade. |
| 7 | `ui/Cells/ChatMessageCell.java:16854` | Drawing also guards against an empty outgoing source. |
| 8 | `ui/Cells/ChatMessageCell.java:16930` | Draw uses already-cached fallback blocks, never constructs a layout in onDraw. |
| 9 | `ui/Cells/ChatMessageCell.java:16840` | Both preview-above text offsets use the same dp formula, including invoice/giveaway exclusions. |
| 10 | `ui/TextMessageEnterTransition.java:413` | End/cancel/removal share idempotent cleanup of locker, alpha and emoji resources. |
| 11 | `ui/TextMessageEnterTransition.java:395` | Cancel explicitly finishes rather than relying on Animator end semantics. |
| 12 | `ui/TextMessageEnterTransition.java:125` | Start is idempotent; constructor no longer owns the cell flag or hides EditText. |
| 13 | `ui/TextMessageEnterTransition.java:467` | An expired/rebound overlay removes itself before drawing stale text. |
| 14 | `ui/MessageEnterTransitionContainer.java:29` | Removed transitions get a cleanup callback; other transition implementations retain default compatibility. |
| 15 | `ui/MessageEnterTransitionContainer.java:45` | Container detach removes its transitions; reverse draw iteration tolerates self-removal. |
| 16 | `messenger/MessageObject.java:8845` | Fallback removes spans, builds plain text outside drawing, then tries existing EN/RU anti-crash notice. |
| 17 | `messenger/MessageObject.java:8868` | Fallback stores block height and character range so measure does not collapse to zero. |
| 18 | `messenger/MessageObject.java:8898` | Generation exceptions trigger full fallback even when some blocks were already created. |
| 19 | `messenger/MessageObject.java:8918` | layoutCreated is set after generation; the first failed layout remains eligible for one retry. |
| 20 | `messenger/MessageObject.java:8891` | Generation invalidates cached height to prevent a stale zero. |
| 21 | `messenger/MessageObject.java:8894` | Before regenerating, original source text is restored for server-indexed entities. |
| 22 | `messenger/MessageObject.java:9020` | Normal message layout adopts wrapped display text; messageOwner text/entities remain unchanged. |
| 23 | `messenger/MessageObject.java:9079` | Layout ranges slice the actual layout text, eliminating the proven NFT/long-link bounds mismatch. |
| 24 | `messenger/MessageObject.java:9244` | A failed sub-block cannot silently continue and hide part of the message. |
| 25 | `ui/Components/PengramTypingEffects.java:254` | Up to 16 pending batches survive until pre-draw instead of a single overwritable posted slot. |
| 26 | `ui/Components/PengramTypingEffects.java:385` | Invalid Layout retries are bounded by 3 attempts / 400ms; stale content is rejected. |
| 27 | `ui/Components/PengramTypingEffects.java:384` | Applying requires matching actual range content as well as usable layout geometry. |
| 28 | `ui/Components/PengramTypingEffects.java:323` | Only overlapping glyphs/batches are removed; following indices move by the edit delta. |
| 29 | `ui/Components/PengramTypingEffects.java:128` | Replacement snapshot comparisons remove unchanged prefix/suffix instead of replaying the whole word. |
| 30 | `ui/Components/ChatActivityEnterView.java:5495` | IME composing no longer clears every active effect; InputConnection still delegates to native methods. |
| 31 | `ui/Components/ChatActivityEnterView.java:5392` | Paste is additionally reset after watchers and on focus; existing try/finally reset already covered no-op context-menu paste. |
| 32 | `ui/Components/PengramTypingEffects.java:35` | Real paste flag is primary; glide no longer fails the multiword/12-char heuristic. Resource guard remains 4096 units. |
| 33 | `ui/Components/PengramTypingEffects.java:36` | Existing Unicode17 grapheme snapshots, hasGlyph raster protection and delete count=0 were already present; retained. |
| 34 | `ui/Components/PengramMusicPlayerSheet.java:1619` | One function owns penguinHolder/coverView visibility for visible/loading/missing/error states. |
| 35 | `ui/Components/PengramMusicPlayerSheet.java:1431` | Callbacks validate playing key, displayed key and request generation, including A→B→A. |
| 36 | `ui/Components/PengramMusicPlayerSheet.java:1437` | Null/recycled/invalid-size bitmap cannot hide the placeholder. |
| 37 | `messenger/PengramCovers.java:102` | No-file and null-decoder/rejection paths now notify failure instead of silently abandoning the sheet. |
| 38 | `ui/Components/PengramMusicPlayerSheet.java:1589` | Player gets a logo behind GL, restored on unavailability; child margins shrink 10→4dp. |
| 39 | `ui/Components/PengramPenguinView.java:138` | Body target .84 is separate from accessory-safe .98; jump reserve is conditional; density² remains fixed. |
| 40 | `ui/Components/PengramBeatHalo.java:78` | Shader allocation moved out of draw; off draws nothing, soft/normal cap glow40 without rings/sparks, juicy retains clipped effects. |

## Deliberately retained / open limits

- No sender/network/encryption/JNI public names/signing/API IDs/proguard/build protection changes.
- Anti-crash sanitization stays enabled by its setting; ordinary failed layouts now degrade instead
  of becoming blank, even with protection disabled. Irrecoverable memory exhaustion can still
  prevent both plain layout and notice allocation: an absolute every-frame guarantee is impossible.
- Retained Unicode17 `PengramGraphemes`, not Android23's older BreakIterator. Existing cluster/raster
  deletion already prevented surrogate halves; replacing this would regress tested ZWJ/Indic rules.
- Paste context-menu already used try/finally, including no-op paste. Added after-watcher/focus resets;
  no permanent 500ms timer or new paste network/clipboard behaviour.
- Kept active tabs and selected queue track colours: no screenshot evidence identifies them as the
  unwanted highlight. The passive accent box is removed; no claim of visual identification.
- No animation effect can promise 100% glyph spawn: intentional cluster filters, MAX_ACTIVE=6,
  MAX_PER_BATCH=3, 16-batch budget, expiry, unloaded/custom fonts and raster memory limits remain.
  Debug messages report application readiness, not a claim that every glyph was spawned.
- Canonical display wrapping fixes the demonstrated normal-message range failure. Repost previews,
  per-code/quote-block additional wrapping, selection/export side effects and rich-message recovery
  still require Android integration review. Server messageOwner text/entities are never rewritten.
- On rich-layout failure with no previous rich layout, cell degrades to TEXT; empty rich source uses
  existing anti-crash notice. Reconstruction of all rich document text is not implemented.
- Existing renderer still has animated pose/flip/hat geometry risks. Full static/animated bounds and
  minimum dp across all skins require more than the conservative camera correction delivered here.
- Player empty cover/beat/skin/dance/shape updates listen to preferences while open, unregister on
  dismiss. Changing structural player style still requires rebuilding the sheet; not fixed here.
- New diagnostic logs are guarded by LOGS_ENABLED and contain IDs/types/lengths/counts/flags only;
  new layout stack logging omits exception message/toString. Full requested draw/frame recorder and
  camera occupancy logger have NOT been implemented. Existing unrelated logging was not audited.
- No new UI strings/dependencies; reused anti-crash notice exists in EN/RU; search regenerated401.

## Verification / metrics

- Local **53/53 Pengram suites PASS**; `git diff --check` clean; changed Java parsed with tree-sitter.
- Local extracted-Java runners SKIP: no javac. A tool-only JRE download did not provide a compiler;
  it is outside Git and adds no app dependency. CI JDK17 must run the extracted lifecycle model.
- New suites: `test-pengram-text-visibility.py`, `test-pengram-penguin-visibility.py`; updated geometry,
  profile and typing assertions because they previously hard-coded the regression constants.
- Java/resource preflight and APK for this patch: **PENDING** (do not reuse 0666b87 success).
- Device time/frame, fps, memory, 30s/10min heat, applied/lost batches before/after: **NOT MEASURED**.
- All 28 manual matrix rows: **NOT RUN**; P1–P8 and Web/Desktop artifacts absent by user decision.

## Acceptance conclusion

This is a tested source-hardening stage, not completion of all eleven acceptance criteria. Missing
Android visual/performance proof, tall-skin/body minimum guarantees, complete rich/wrapped-block
integration and universal glyph-success guarantees remain explicit. PR remains open, never merged.
