# Parallax v2.1 wire freeze — W15-02

Route `schemaVersion` remains 2; `RoutePerception.parallax` is optional.
Old v2.0 readers ignore that key and retain fingerprints and geometry.
`parallaxVersion = 1` versions only this object. Consumers must check
`compatibleWith(liveEstimatorId)` before using depth: unknown versions or
different estimator identities cannot support the cancellation claim.
Intrinsics provenance is recorded, never silently promoted to calibration.

## Encoding decisions

- `inverseDepth` and `trackability` are paired base64 byte blobs, one byte each
  per half-metre bucket in the creator's odometer coordinates. Base64 costs
  four characters per three bytes rather than JSON's per-number punctuation.
- Depth code 0 is unknown; interpret other codes as signed int8. Let q = code
  for negative codes, or code - 1 for positive codes; rho = 2^(q/16).
  The representable log2 range is -8 through 7.875. Quantisation uses the
  nearest representable nonzero code; out-of-range estimates stay unknown.
- Trackability is unsigned 0..255. A depth code has no validity without the
  consumer's trackability, estimator, version and usability gates.
- Each strip is exactly 96 decoded bytes: 64 normalised quantised column
  values followed by 32 row values, sampled every 2 m and at nodes.
  Distances are finite, nonnegative and strictly increasing (deduplicate
  a node that coincides with a regular waypoint).
- `legSigmaRad` is empty when unmeasured, otherwise one finite nonnegative
  heading sigma (at most pi) per route leg. The route-aware producer gate
  enforces that count. Unknown is not a fabricated zero uncertainty.
- The entire perception JSON, including base64 and metadata, must still fit
  65,536 bytes. The existing bundle producer gate enforces it; decoding stays
  tolerant. No container, crypto, expiry or network-envelope field changes.

## Privacy argument and limits

A strip is not an encoded image. Reducing a 120 x 90 plane (10,800 bytes)
to 96 int8 marginals discards the spatial arrangement, absolute illumination
and quantisation detail. There are at most 2^768 strips versus 2^86400
possible source planes, so no inverse can uniquely reconstruct every source.
Even before normalisation, row/column marginals are ambiguous: adding
`[+a,-a;-a,+a]` to a 2 x 2 patch preserves both marginal sums. The contract
tests demonstrate that ambiguity and assert the fixed wire size. The actual
gradient-band extractor and its collision tests belong to W17-01.

This is **not** a proof of anonymity or resistance to learned/auxiliary-data
inference. Place recognition is the feature, and a sequence can reveal a
route. Treat strips and depth as sensitive image-derived location data:
they inherit sealed-bundle encryption and route expiry; export preview
discloses them; plain-file export excludes all perception unless opted in.
No source frame, JPEG, or reversible image payload is added by this contract.
