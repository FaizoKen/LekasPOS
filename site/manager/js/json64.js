// JSON for the tills' sync files without losing digits. Ids and clock values are 64-bit integers (a
// till's number in the top bits, references/sync.md §2); JSON.parse would round them to 53 bits. Every
// integer comes back as a BigInt, so ids, clocks and amounts compare and bind to SQLite exactly; other
// numbers are Numbers. Objects have no prototype (a key "__proto__" in a crafted file is just a key).

const INT64_MIN = -(1n << 63n);
const INT64_MAX = (1n << 63n) - 1n;

export class JsonError extends Error {}

export function parse(text) {
  let i = 0;
  const n = text.length;

  function fail(what) {
    throw new JsonError(`${what} at ${i}`);
  }

  function space() {
    while (i < n) {
      const c = text.charCodeAt(i);
      if (c === 32 || c === 9 || c === 10 || c === 13) i++;
      else break;
    }
  }

  function value() {
    space();
    if (i >= n) fail('unexpected end');
    const c = text.charCodeAt(i);
    if (c === 123) return object();
    if (c === 91) return array();
    if (c === 34) return string();
    if (c === 45 || (c >= 48 && c <= 57)) return number();
    if (text.startsWith('true', i)) { i += 4; return true; }
    if (text.startsWith('false', i)) { i += 5; return false; }
    if (text.startsWith('null', i)) { i += 4; return null; }
    return fail('unexpected character');
  }

  function object() {
    i++;
    const o = Object.create(null);
    space();
    if (text.charCodeAt(i) === 125) { i++; return o; }
    for (;;) {
      space();
      if (text.charCodeAt(i) !== 34) fail('expected a key');
      const k = string();
      space();
      if (text.charCodeAt(i) !== 58) fail('expected :');
      i++;
      o[k] = value();
      space();
      const c = text.charCodeAt(i++);
      if (c === 44) continue;
      if (c === 125) return o;
      fail('expected , or }');
    }
  }

  function array() {
    i++;
    const a = [];
    space();
    if (text.charCodeAt(i) === 93) { i++; return a; }
    for (;;) {
      a.push(value());
      space();
      const c = text.charCodeAt(i++);
      if (c === 44) continue;
      if (c === 93) return a;
      fail('expected , or ]');
    }
  }

  function string() {
    i++;
    let start = i;
    let out = '';
    for (;;) {
      if (i >= n) fail('string not closed');
      const c = text.charCodeAt(i);
      if (c === 34) {
        out += text.slice(start, i);
        i++;
        return out;
      }
      if (c === 92) {
        out += text.slice(start, i);
        const e = text.charCodeAt(i + 1);
        i += 2;
        switch (e) {
          case 34: out += '"'; break;
          case 92: out += '\\'; break;
          case 47: out += '/'; break;
          case 98: out += '\b'; break;
          case 102: out += '\f'; break;
          case 110: out += '\n'; break;
          case 114: out += '\r'; break;
          case 116: out += '\t'; break;
          case 117: {
            const hex = text.slice(i, i + 4);
            if (!/^[0-9a-fA-F]{4}$/.test(hex)) fail('bad \\u escape');
            out += String.fromCharCode(parseInt(hex, 16));
            i += 4;
            break;
          }
          default: fail('bad escape');
        }
        start = i;
      } else {
        if (c < 32) fail('control character in a string');
        i++;
      }
    }
  }

  function number() {
    const start = i;
    if (text.charCodeAt(i) === 45) i++;
    let integer = true;
    while (i < n) {
      const c = text.charCodeAt(i);
      if (c >= 48 && c <= 57) i++;
      else if (c === 46 || c === 101 || c === 69 || c === 43 || c === 45) { integer = false; i++; }
      else break;
    }
    const s = text.slice(start, i);
    if (!/^-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?$/.test(s)) fail('bad number');
    if (integer) {
      const b = BigInt(s);
      // Like the till's reader (a Long when it fits, else a Double).
      return b >= INT64_MIN && b <= INT64_MAX ? b : Number(s);
    }
    return Number(s);
  }

  const v = value();
  space();
  if (i !== n) fail('text after the value');
  return v;
}

/** JSON text of [v] (as [parse] returns it); BigInts are written as plain integers. */
export function stringify(v) {
  return JSON.stringify(v, (_, x) => (typeof x === 'bigint' ? `\u0000${x}\u0000` : x))
    .replace(/"\\u0000(-?\d+)\\u0000"/g, '$1');
}
