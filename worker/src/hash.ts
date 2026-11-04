// SHA-256 hex digests, one implementation for the whole worker.
//
// Four paths used to turn a WebCrypto digest into a hex string by hand —
// the device id and verifier, the upload checksum and idempotency job id,
// and the SAM cache key — and each copy could drift on padding or encoding.
// Keeping the encoding here means a digest is compared only against another
// digest from this function.

export async function sha256Hex(input: Uint8Array | string): Promise<string> {
  const bytes = typeof input === "string" ? new TextEncoder().encode(input) : input;
  const digest = await crypto.subtle.digest("SHA-256", bytes as unknown as ArrayBuffer);
  return [...new Uint8Array(digest)]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}
