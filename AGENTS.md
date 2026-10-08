# AGENTS.md — instructions for AI coding agents

This file is for any AI agent working in this repository (Claude Code, Codex, Cursor, Gemini CLI,
Copilot, …). Claude Code also reads [`CLAUDE.md`](CLAUDE.md); both say the same thing. The full
rule book is the project skill [`.claude/skills/lekaspos/SKILL.md`](.claude/skills/lekaspos/SKILL.md)
— read it before changing anything, whatever tool you are.

## What this is

LekasPOS: an offline-first native Android point-of-sale app for small grocery stores and mini
markets (Malaysia first: MYR, English + Bahasa Melayu). Public, GPL-3.0, released as APKs on
GitHub (not on Google Play yet); the app updates itself from the latest GitHub release.
There is **no backend**: the on-device SQLite database is the source of truth; the shop's own
Google Drive is used only for background sync between tills and backup. Real shops use it every
day — a bug can lose a shop's sales, so correctness and data safety come before features.

## Read first, in this order

1. [`.claude/skills/lekaspos/SKILL.md`](.claude/skills/lekaspos/SKILL.md) — rules, conventions,
   performance budget, definition of done, how work is delivered.
2. [`docs/PHASES.md`](docs/PHASES.md) — what is done, what is being tested, open questions.
3. The reference for the area you touch (all in `.claude/skills/lekaspos/references/`):

| You are working on… | Read |
|---|---|
| Where a feature's code lives, what a class does, which test covers it | `codemap.md` |
| What the app does today (every feature, rule, permission, setting) | `features.md` |
| How to add a screen, setting, permission, table, sync event, string, report, release | `recipes.md` |
| Words used in the app and the code, English ↔ Bahasa Melayu | `glossary.md` |
| Modules, layers, threading, startup, UI rules | `architecture.md` |
| Tables, columns, indexes, migrations, SQLite 3.8.4 limits | `database.md` |
| Money, quantities, tax, discounts, rounding | `money.md` |
| Google Drive sync, IDs, HLC, merge rules, backups | `sync.md` |
| Performance budget, perf suite | `performance.md` |

4. [`docs/DECISIONS.md`](docs/DECISIONS.md) — why things are the way they are (D-001 … ). Search
   it before "simplifying" something: most odd-looking code is a fix for a real shop's problem.

## Non-negotiable rules (summary — the skill has the full list)

- minSdk 21, targetSdk 36. Kotlin + classic Views + RecyclerView. No Compose, AppCompat, Material
  Components, Fragments, Room (for app data), DI frameworks or JSON libraries.
- Money is `Long` minor units (sen); quantities are `Long` milli-units (1 pc = 1000); rates are
  basis points (6% = 600). Never Float/Double for money, quantity or rates. Business maths lives
  only in `:core`.
- No disk, network or Bluetooth work on the main thread. All DB writes run on the single DB writer
  thread inside one transaction (`Db.write { tx -> … }`).
- SQL must run on SQLite 3.8.4 (Android 5.0): no UPSERT, RETURNING, window functions, row values,
  expression indexes, json1, FTS5, `ALTER TABLE … RENAME/DROP COLUMN`, NULLS LAST.
- A sale is committed in one database transaction before anything is printed.
- Every synced write appends its sync event to `outbox` in the same transaction.
- Schema changes are real migrations (bump `DB_VERSION`, migration, schema snapshot, test).
- Every user-visible string exists in English (`values/`) and Bahasa Melayu (`values-ms/`).
- Every new dependency needs a size + need justification in `docs/DECISIONS.md` first.
- Release APK under 8 MB (it is about 1.6 MB). Record its size at the end of every phase/release.

## Commands (Windows, PowerShell, repo root)

```powershell
$env:JAVA_HOME = 'D:\dev\jdk-21'; $env:ANDROID_HOME = 'D:\Android\Sdk'   # see docs/BUILD.md
.\gradlew.bat :core:test :app:testDebugUnitTest      # JVM unit tests
.\gradlew.bat :app:assembleRelease :app:lintRelease  # R8 release APK + lint
```

Emulator tests and performance runs happen on **GitHub Actions** (`ci.yml` on every push,
`perf.yml` on demand). The maintainer's laptop has ~7 GB RAM: at most one emulator, never alongside
Gradle. Details: [`docs/BUILD.md`](docs/BUILD.md).

## Keep the documentation true

A change is not done until the documents that describe it are right:

| If you change… | Also update |
|---|---|
| Anything a shop owner, manager or cashier sees or does (labels, steps, defaults, permissions, messages) | the user guide in **both** languages: [`site/guide.html`](site/guide.html) and [`site/panduan.html`](site/panduan.html); refresh a screenshot in `site/img/` if the screen changed (`recipes.md` → "Refresh the guide's screenshots") |
| A feature's rules, a setting, a permission, a screen | `references/features.md`; `references/codemap.md` if files/classes moved |
| A decision (architecture, data, dependency, behaviour the owner chose) | the matching reference **and** a new entry in `docs/DECISIONS.md` |
| What the app stores, sends, asks permission for or connects to | `site/privacy.html` **and** `site/privasi.html` (same date), `docs/PLAY.md` |
| Phase/release status | `docs/PHASES.md` |
| Build, CI, signing, release steps | `docs/BUILD.md` |

## Working agreements

- The owner (the shop owner / maintainer) decides business rules, legal/tax facts, accounts and
  money. Ask about those; decide technical matters yourself and record them.
- Work is delivered in phases/releases: build, test (CI), report the APK size and what needs a
  real-device check, then wait for the owner's feedback before starting the next one.
- Commit, push, publish a GitHub release or change Google Cloud / Cloudflare settings only when the
  owner asks. Publishing a release marked *latest* updates every shop within a day.
- Never change the release signing key, the application ID `com.lekaspos.app`, or a stable integer
  code (enum values stored in the database or in sync files).
