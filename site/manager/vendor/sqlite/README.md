# SQLite for the manager web page

SQLite 3.53.4 compiled to WebAssembly, unchanged from the npm package
`@sqlite.org/sqlite-wasm@3.53.4-build2` (published 2026-10-02, https://github.com/sqlite/sqlite-wasm):

| File here | File in the package | SHA-256 |
|---|---|---|
| `sqlite3.mjs` | `dist/index.mjs` | `a6d3fe46aa3f924e7686329f97b0564549ef1f787e0c19924179aef7f72bcc01` |
| `sqlite3.wasm` | `dist/sqlite3.wasm` | `2ee8f3dab694532afc8840e07703127287662d08b74e6ff50491ce63f00d5752` |

Package tarball SHA-256: `ddff7c28d464ffa07c35158bbab2b618b83c65d65b5e7cbfa06e225e0beff53f`.

Licences: the SQLite library and its JavaScript API are in the public domain; the Emscripten glue code is
MIT / University of Illinois-NCSA; the npm wrapper is Apache-2.0 (headers kept in `sqlite3.mjs`).

Why it is here and not on a CDN: see `docs/DECISIONS.md` D-082. To update it, replace both files from a newer
package (at least two weeks old), update the table above, and run `node --test scripts/manager/`.
This build has no FTS4: the web page never touches the app's `product_fts` table.
