// Small R2 helpers shared by the route modules.
//
// `listPages` is the one place that walks a cursor: eight call sites repeated
// the same `for (;;)` around `list`, and one of them forgetting the
// `truncated` check silently drops objects. `readJson` is the one place that
// tolerates a missing or unreadable object, so a half-written record reads as
// null instead of throwing into a handler.

import type { Env } from "./index";

/** Every page of a prefix, in order. Stops when R2 reports no next page; a
 *  `truncated` page without a cursor cannot be followed, so it ends there
 *  rather than repeating the first page forever. */
export async function* listPages(
  env: Pick<Env, "MEDIA">,
  opts: R2ListOptions,
): AsyncGenerator<R2Objects> {
  let cursor = opts.cursor;
  for (;;) {
    const page = await env.MEDIA.list({ ...opts, cursor });
    yield page;
    if (!page.truncated || !page.cursor) return;
    cursor = page.cursor;
  }
}

/** A stored JSON record, or null when the object is absent or unparseable. */
export async function readJson<T>(
  env: Pick<Env, "MEDIA">,
  key: string,
): Promise<T | null> {
  const obj = await env.MEDIA.get(key);
  if (!obj) return null;
  try {
    return (await obj.json()) as T;
  } catch {
    return null;
  }
}

/** Deletes every object under `prefix`, one page at a time. */
export async function deletePrefix(env: Pick<Env, "MEDIA">, prefix: string): Promise<void> {
  for await (const page of listPages(env, { prefix })) {
    if (page.objects.length > 0) {
      await env.MEDIA.delete(page.objects.map((o) => o.key));
    }
  }
}
