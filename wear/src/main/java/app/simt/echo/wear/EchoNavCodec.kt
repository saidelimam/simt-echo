package app.simt.echo.wear

/**
 * Watch-side nav snapshot (plan W13-12): arrow, distance, next cue and
 * phase, mirrored from the phone over `/echo/nav`.
 *
 * Wire mirror of shared `nav/WearNavSync.kt` — same five-line v1 format
 * (`v1\n<arrowDeg>\n<distanceM>\n<phase>\n<escaped cue>`), same rules:
 * strict on structure (anything else → null, never throws), lenient on
 * content (an unknown phase name is kept verbatim). The two codecs are
 * tested round-trip on both sides and must change together.
 */
data class EchoNavState(
    val arrowDeg: Int,
    val distanceM: Int,
    val cue: String,
    val phase: String,
)

object EchoNavCodec {

    /** Cue cap, mirroring shared `WearNavSync.MAX_CUE_CHARS`. */
    private const val MAX_CUE_CHARS = 140

    fun encode(state: EchoNavState): ByteArray {
        val body = listOf(
            "v1",
            state.arrowDeg.toString(),
            state.distanceM.toString(),
            state.phase,
            escape(state.cue.take(MAX_CUE_CHARS)),
        ).joinToString("\n")
        return body.toByteArray(Charsets.UTF_8)
    }

    /** Decodes a snapshot, or null when the payload is not v1 nav. Never throws. */
    fun decode(bytes: ByteArray): EchoNavState? = try {
        decodeText(bytes.toString(Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }

    private fun decodeText(text: String): EchoNavState? {
        val lines = splitLines(text)
        if (lines.size != 5 || lines[0] != "v1") return null
        val arrow = lines[1].toIntOrNull() ?: return null
        if (arrow < -180 || arrow > 180) return null
        val distance = lines[2].toIntOrNull() ?: return null
        if (distance < 0 || distance > 999_999) return null
        val phase = lines[3]
        if (phase.isEmpty() || phase.length > 64) return null
        val cue = unescape(lines[4]) ?: return null
        if (cue.length > 512) return null
        return EchoNavState(arrow, distance, cue, phase)
    }

    private fun escape(raw: String): String = buildString(raw.length) {
        for (c in raw) {
            when (c) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> append(c)
            }
        }
    }

    private fun unescape(raw: String): String? {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\') {
                if (i + 1 >= raw.length) return null
                when (raw[i + 1]) {
                    '\\' -> out.append('\\')
                    'n' -> out.append('\n')
                    else -> return null
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun splitLines(text: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') {
                out += current.toString()
                current.clear()
            } else if (c == '\\' && i + 1 < text.length && (text[i + 1] == 'n' || text[i + 1] == '\\')) {
                // An escape belongs to the field, not the framing — re-emit
                // verbatim and let the field unescaper sort it out. Only the
                // cue (last line) ever carries escapes.
                current.append(c).append(text[i + 1])
                i++
            } else {
                current.append(c)
            }
            i++
        }
        out += current.toString()
        return out
    }
}
