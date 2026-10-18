# Pengram visual editor: scope and safety checklist

This is an implementation inventory, **not a claim that the entire Telegram UI is reproduced**. The editor opens directly from Pengram settings and starts on a sample chat list. Its synthetic names, texts, calls, story, cover, and search results are resource strings and local view state; it must never load dialogs, contacts, media or call history from an account.

## Interactive preview

| Surface | Interaction | Persisted changes |
| --- | --- | --- |
| Chat list | Open one of three sample conversations; editable sample search (filters only the three synthetic chats); compose and story screens; long-press a row/avatar | MD3 dialogs, unread tone, dividers, avatar shape; title and font; story/compose visibility |
| Chat | Tap sample message, avatar, deleted mark or Pengram bar; drag avatar/bar only to marked slots; open sample chat menu; type into a local-only sample composer (never sends to Telegram; drafts stay in memory and survive view recreation while the editor fragment lives) | Bubble radius/text size, group avatar placement, deleted mark, bar position/visibility, menu items |
| Menus | Long-press and drop an item onto another item or a section header; ghosted hidden items live in a separate editor-only section | Shared menu/chat-action order, hide/restore, permitted main/island placements; inspector arrows offer a non-drag reorder alternative |
| Settings | Simulated navigation to Pengram appearance or representative, static-only lists for the nine native settings categories; long-press/drop rows | Shared settings row order and hide/restore (Pengram entry may only be restored, not newly hidden here); native settings list labels are not editable |
| Tabs | Tap to switch between sample chat list, contacts, settings/calls slot, profile; long-press to edit | Global visibility of optional tabs; Chats stays present. Calls and Settings remain alternative views of the third slot depending on the active account. |
| Appearance | Inspect MD3 dialogs/search/FAB/settings/sheet/dialog samples; browse the generated list of Pengram sections and settings inside the same preview; select a row to see its actual breadcrumb and an explicit button to open its real control | Existing Pengram MD3/Monet/font preferences, shared across accounts; list-only rows do not silently change preferences |
| Player | See a synthetic track/cover and switch style/background/cover shape | Existing player appearance preferences; no audio playback, no change to penguin options |
| Search/contacts/calls/profile | Navigate with synthetic results only | Only settings with an actual matching global appearance control are editable; no calls, messages or account changes |

Hidden items are intentionally shown faded **only in the editor**, in a separate section, so they can be restored. The chat list and dialogs are not reorderable: their real order is not an appearance preference. Required Chats navigation and the Pengram/Settings access points cannot be newly hidden in this editor. On Android 21–23 the item drag gesture uses `startDrag`; Android 24+ uses `startDragAndDrop`.

## Still not exact / verification pending

- The navigable settings inventory has 329 indexed rows (including dedicated chat-avatar and profile-history controls). It covers browsable **lists**, not 329 pixel-accurate, directly editable mock controls. Only implemented appearance controls can change inside the preview; the explicit “Open setting” button leaves the editor and navigates to the real setting. Hidden penguin controls are intentionally excluded: they remain accessible only through the secret 10 taps on the animated penguin.
- Native Telegram account screens, call screens, media viewers, and some other Pengram secondary screens are **not** pixel-accurate editable replicas. They should not be presented as such. Existing settings outside the visual editor remain available.
- A sample-data preview cannot demonstrate live audio/video, actual stories, real chat search or real account-specific state without breaking its isolation; these are intentionally inert.
- This change has had `python3 scripts/test-pengram-visual-editor.py`, `python3 scripts/test-pengram-search-index.py`, Java source parsing, XML/resource and whitespace checks only. Per user instruction, do **not** compile, start CI, build APK, push (push starts builds), or claim device validation until authorized. When authorized: run Java preflight, arm64 build, and manual Android 21+/small-screen/large-font/dark-theme/drag-cancel/accessibility checks.
- If the full literal scope means editing every Telegram screen and every Pengram preference in this single surface, that work remains open; prioritize real preference-backed controls and truthful synthetic previews rather than nonfunctional lookalikes.
