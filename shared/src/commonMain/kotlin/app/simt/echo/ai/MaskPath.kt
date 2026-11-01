package app.simt.echo.ai

/** One SVG subpath as polyline points in mask-raster pixels. */
data class MaskSubpath(
    val points: List<SvgPoint>,
    val closed: Boolean,
)

data class SvgPoint(val x: Float, val y: Float)

/**
 * Minimal SVG path parser for SAM mask overlays (plan W3-07). The worker
 * emits simple polygon paths (`M … L … Z` in mask-raster coordinates);
 * this parses the `M/m L/l H/h V/v Z/z` subset with implicit linetos after
 * a moveto. Anything else (curves, arcs, garbage) yields null and the card
 * falls back to the box-only overlay — a mask that cannot be read is never
 * drawn wrong.
 */
object MaskPath {
    fun parseSvgPath(svg: String): List<MaskSubpath>? {
        val tokens = tokenize(svg) ?: return null
        if (tokens.isEmpty()) return null
        val subpaths = mutableListOf<MaskSubpath>()
        var points = mutableListOf<SvgPoint>()
        var closed = false
        var current = SvgPoint(0f, 0f)
        var start = current
        var command: Char? = null
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token is Token.Command) {
                if (token.command == 'Z' || token.command == 'z') {
                    if (points.isNotEmpty()) {
                        closed = true
                        current = start
                    }
                    i++
                    continue
                }
                if (token.command !in "MmLlHhVv") return null
                command = token.command
                i++
                continue
            }
            val cmd = command ?: return null
            fun number(): Float {
                // Bounds-checked: an EOF-truncated path ("M 10 20 L 30")
                // degrades to null like any other unreadable mask, never
                // throws out of composition (review 0004 R3).
                if (i >= tokens.size) return Float.NaN
                val value = (tokens[i] as? Token.Number ?: return Float.NaN)
                i++
                return value.value
            }
            when (cmd) {
                'M', 'm' -> {
                    val x = number()
                    val y = number()
                    if (x.isNaN() || y.isNaN()) return null
                    if (points.isNotEmpty()) {
                        subpaths += MaskSubpath(points.toList(), closed)
                        points = mutableListOf()
                        closed = false
                    }
                    current = if (cmd == 'M') SvgPoint(x, y) else SvgPoint(current.x + x, current.y + y)
                    start = current
                    points += current
                    // Further pairs without a command letter are linetos.
                    command = if (cmd == 'M') 'L' else 'l'
                }
                'L', 'l' -> {
                    val x = number()
                    val y = number()
                    if (x.isNaN() || y.isNaN()) return null
                    current = if (cmd == 'L') SvgPoint(x, y) else SvgPoint(current.x + x, current.y + y)
                    points += current
                }
                'H', 'h' -> {
                    val x = number()
                    if (x.isNaN()) return null
                    current = if (cmd == 'H') SvgPoint(x, current.y) else SvgPoint(current.x + x, current.y)
                    points += current
                }
                'V', 'v' -> {
                    val y = number()
                    if (y.isNaN()) return null
                    current = if (cmd == 'V') SvgPoint(current.x, y) else SvgPoint(current.x, current.y + y)
                    points += current
                }
                else -> return null
            }
        }
        // A path ending on a dangling command letter ("… L") is truncated
        // too: its arguments never arrived, so it is unreadable, not short.
        val last = tokens.lastOrNull()
        if (last is Token.Command && last.command != 'Z' && last.command != 'z') return null
        if (points.isNotEmpty()) subpaths += MaskSubpath(points.toList(), closed)
        return subpaths.takeIf { it.isNotEmpty() }
    }

    private sealed interface Token {
        data class Command(val command: Char) : Token
        data class Number(val value: Float) : Token
    }

    private fun tokenize(svg: String): List<Token>? {
        val out = mutableListOf<Token>()
        var i = 0
        while (i < svg.length) {
            val c = svg[i]
            when {
                c.isWhitespace() || c == ',' -> i++
                c.isLetter() -> {
                    out += Token.Command(c)
                    i++
                }
                c == '-' || c == '+' || c == '.' || c.isDigit() -> {
                    val start = i
                    if (c == '-' || c == '+') i++
                    while (i < svg.length && (svg[i].isDigit() || svg[i] == '.')) i++
                    if (i < svg.length && (svg[i] == 'e' || svg[i] == 'E')) {
                        i++
                        if (i < svg.length && (svg[i] == '-' || svg[i] == '+')) i++
                        while (i < svg.length && svg[i].isDigit()) i++
                    }
                    val value = svg.substring(start, i).toFloatOrNull() ?: return null
                    out += Token.Number(value)
                }
                else -> return null
            }
        }
        return out
    }
}
