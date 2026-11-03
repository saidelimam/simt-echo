package app.simt.echo.nav.ghost

/** Banded DTW; indices passed to the inline local cost are zero-based. */
object Dtw {
    /** Caller-owned rows. Reuse between calls, never concurrently. */
    class Scratch(
        val previous: DoubleArray,
        val current: DoubleArray,
        val previousLengths: IntArray,
        val currentLengths: IntArray,
    ) {
        constructor(maximumColumns: Int) : this(
            DoubleArray(maximumColumns + 1), DoubleArray(maximumColumns + 1),
            IntArray(maximumColumns + 1), IntArray(maximumColumns + 1),
        )
    }

    /**
     * Inclusive Sakoe–Chiba band |i-j| <= band. Unreachable endpoints cost
     * infinity. Ties prefer vertical, then horizontal, then diagonal — the
     * historical magnetic and turn-signature ordering. [mean] divides the
     * minimum-total-cost path by its length, not a minimum-mean-cost path.
     * Mean mode guards unreachable predecessors as magnetic matching did;
     * total mode keeps turn matching's unconditional non-finite propagation.
     * No per-call allocation, including captured primitive local costs.
     */
    inline fun distance(
        rows: Int,
        columns: Int,
        band: Int,
        scratch: Scratch,
        mean: Boolean = false,
        localCost: (Int, Int) -> Double,
    ): Double {
        require(rows >= 0 && columns >= 0 && band >= 0)
        var prev = scratch.previous
        var curr = scratch.current
        var prevLen = scratch.previousLengths
        var currLen = scratch.currentLengths
        require(prev.size > columns && curr.size > columns && prev !== curr)
        require(prevLen.size > columns && currLen.size > columns && prevLen !== currLen)
        val inf = Double.POSITIVE_INFINITY
        for (j in 0..columns) { prev[j] = inf; prevLen[j] = 0 }
        prev[0] = 0.0
        for (i in 1..rows) {
            curr[0] = inf
            currLen[0] = 0
            val lo = maxOf(1, i - band)
            val hi = minOf(columns.toLong(), i.toLong() + band).toInt()
            for (j in 1..columns) {
                if (j < lo || j > hi) {
                    curr[j] = inf
                    currLen[j] = 0
                    continue
                }
                val local = localCost(i - 1, j - 1)
                var best = prev[j]
                var length = prevLen[j]
                if (curr[j - 1] < best) { best = curr[j - 1]; length = currLen[j - 1] }
                if (prev[j - 1] < best) { best = prev[j - 1]; length = prevLen[j - 1] }
                curr[j] = if (mean) {
                    if (best.isInfinite()) inf else best + local
                } else {
                    local + best
                }
                currLen[j] = if (best.isInfinite()) 0 else length + 1
            }
            val old = prev; prev = curr; curr = old
            val oldLen = prevLen; prevLen = currLen; currLen = oldLen
        }
        if (!mean) return prev[columns]
        return if (prev[columns].isInfinite() || prevLen[columns] == 0) inf else prev[columns] / prevLen[columns]
    }
}
