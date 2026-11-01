// SAM 3.1 response parsing (plan §7.3 `samParse.ts`, slice W3-05).
//
// The wire shape is verbatim plan: one `<<frame>f>` line per frame, each
// carrying `<id><|box;...|>` records with an optional `<|mask;...|>` payload
// (base85 never contains `|`, so `|` splits records safely). The raw line is
// kept on every observation so the hero frame's mask can be decoded later
// without re-reading R2.

/** One SAM observation: a single tracked object on a single frame. */
export interface SamObs {
  frame: number;
  id: number;
  box: [number, number, number, number];
  w: number;
  h: number;
  /** Mean confidence when the record carries `c=`; absent otherwise. */
  c?: number;
  /** Verbatim source line (mask payload included) for hero-mask decoding. */
  line: string;
}

const LINE = /^<(\d+)f>(.*)$/;
const REC = /(\d+)<\|box;([^|]*)\|>(?:<\|mask;([^|]*)\|>)?/g;

/** Parse raw SAM text into observations. Never throws on junk: a line that
 * does not match is skipped, a record with non-numeric fields is skipped. */
export function parseSam(text: string): SamObs[] {
  const out: SamObs[] = [];
  for (const raw of text.split("\n")) {
    const line = raw.trim();
    if (line === "") continue;
    const m = LINE.exec(line);
    if (!m) continue;
    const frame = Number(m[1]);
    if (!Number.isInteger(frame)) continue;
    for (const r of m[2].matchAll(REC)) {
      const kv = Object.fromEntries(
        r[2].split(";").map((p) => {
          const eq = p.indexOf("=");
          return eq < 0 ? [p, ""] : [p.slice(0, eq), p.slice(eq + 1)];
        }),
      );
      const box: [number, number, number, number] = [
        Number(kv.x1),
        Number(kv.y1),
        Number(kv.x2),
        Number(kv.y2),
      ];
      const w = Number(kv.w);
      const h = Number(kv.h);
      // Sanitise here, at the wire boundary: CandidateSchema demands int
      // boxes, positive int frame dims and confidence in 0–1, and a record
      // that violates that must be dropped — never carried downstream to
      // crash JobResultSchema.parse and error the whole job (review 0002).
      if (box.some((v) => !Number.isInteger(v)) || !Number.isInteger(w) || !Number.isInteger(h)) {
        continue;
      }
      if (w <= 0 || h <= 0 || box[2] <= box[0] || box[3] <= box[1]) {
        continue;
      }
      const obs: SamObs = { frame, id: Number(r[1]), box, w, h, line };
      if (kv.c !== undefined && kv.c !== "") {
        const c = Number(kv.c);
        // Out-of-range confidence is ignored (the parser's own stance),
        // not fatal and never clamped into a lie.
        if (Number.isFinite(c) && c >= 0 && c <= 1) obs.c = c;
      }
      out.push(obs);
    }
  }
  return out;
}
