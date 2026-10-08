# LekasPOS — project instructions

Offline-first native Android point-of-sale app for small grocery stores and mini markets
(Malaysia first: MYR, English + Bahasa Melayu). Published publicly on Google Play.
There is NO backend: the on-device SQLite database is the source of truth; Google Drive is
used only for background sync and backup.

## Start of every session

1. Load the project skill **`lekaspos`** (`.claude/skills/lekaspos/SKILL.md`) before doing any
   work, and follow it. Its `references/` folder holds the detailed architecture, schema, sync,
   money and performance rules.
2. Read `docs/PHASES.md` to find the current phase, what is done, and what is pending.
3. Continue from the current phase. Never start the next phase until the user has sent their
   real-device test feedback for the previous one.

## Current phase

See `docs/PHASES.md` (single source of truth for phase status).

## Non-negotiable rules (full list in the skill)

- minSdk 21, targetSdk 36. Kotlin + classic Views + RecyclerView. No Compose, AppCompat,
  Material Components, Fragments, Room (for app data), DI frameworks or JSON libraries.
- Money is `Long` minor units (sen). Quantities are `Long` milli-units (1 pc = 1000).
  Rates are basis points (6% = 600). Never use Float/Double for money, quantity or rates.
- No disk, network or Bluetooth work on the main thread. All DB writes run on the single
  DB writer thread inside one transaction.
- SQL must run on SQLite 3.8.4 (Android 5.0): no UPSERT, RETURNING, window functions, row
  values, expression indexes, json1, FTS5, `ALTER TABLE ... RENAME/DROP COLUMN`, NULLS LAST.
- A sale is committed in one database transaction before anything is printed.
- Every synced write appends its sync event to `outbox` in the same transaction.
- Every new dependency needs a size + need justification in `docs/DECISIONS.md` first.
- Release APK must stay under 8 MB. Record its size at the end of every phase.

## Keep the documentation true — every change, same commit

A change to the app is not done until every document it affects says the same as the code. Check
this list for **every** change (feature, fix, setting, label, permission, release) and update what applies:

| The change… | Update |
|---|---|
| alters anything an owner, manager or cashier sees or does (screen, button text, step, default, permission, message) | the user guide in **both** languages: `site/guide.html` and `site/panduan.html` (labels exactly as in `values/` and `values-ms/`); refresh its screenshot in `site/img/` if a pictured screen changed (`references/recipes.md` §0, §0a) |
| alters what a feature does (rules, limits, settings, activity log) | `.claude/skills/lekaspos/references/features.md` |
| adds/moves/renames a file, screen, worker, test, stable code | `references/codemap.md` |
| adds or renames a word users see | `references/glossary.md` |
| changes a decision or a reference rule | that reference **and** a new `docs/DECISIONS.md` entry |
| changes what the app stores, sends or asks permission for | `site/privacy.html` + `site/privasi.html` (same date), `docs/PLAY.md` |
| is released | the guide's "For LekasPOS x.y.z" line (both languages), `docs/PHASES.md` |

Before committing anything under `site/`: `node scripts/check-site.mjs`. When reporting finished work,
say which documents were updated (or that none needed it).

## Documentation

`AGENTS.md` (same rules for other AI tools) · the skill's `references/` (`codemap.md` where things are,
`features.md` what the app does, `recipes.md` how to change it, `glossary.md` words) · `docs/` · the user
guide in `site/`. The README has the full map.

## Commands (Windows, run from the repo root)

- JVM unit tests: `.\gradlew.bat :core:test :app:testDebugUnitTest`
- Release APK: `.\gradlew.bat :app:assembleRelease` → `app\build\outputs\apk\release\`
- Emulator tests and perf runs: **GitHub Actions** (`ci.yml` on every push; `perf.yml` on
  demand). The laptop has ~7 GB RAM and little disk — run at most one emulator locally, never
  alongside Gradle.
- Performance suite details: `.claude/skills/lekaspos/references/performance.md`

Toolchain paths, CI and versions are recorded in `docs/BUILD.md`.
