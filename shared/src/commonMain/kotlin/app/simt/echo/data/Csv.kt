package app.simt.echo.data

/**
 * Generic CSV transcription (slice W4-01).
 *
 * One encoder for every export in the app (trials first, measurements
 * later): callers supply a header and rows of already-stringified cells,
 * and this module owns quoting. Generic over cell type — any value renders
 * via `toString`, null renders empty — so no exporter hand-rolls escapes.
 */
object Csv {
    /** Quote a cell when it contains a comma, quote, or line break. */
    fun escape(cell: String): String {
        if (cell.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return cell
        return "\"" + cell.replace("\"", "\"\"") + "\""
    }

    /** Render one row, coercing every cell with `toString` (null → ""). */
    fun row(cells: List<Any?>): String =
        cells.joinToString(",") { escape(it?.toString() ?: "") }

    /** Render a header plus rows, LF-joined with a trailing newline. */
    fun table(headers: List<String>, rows: List<List<Any?>>): String =
        (listOf(row(headers)) + rows.map(::row)).joinToString("\n", postfix = "\n")

    /** Generic projection: one CSV row per item, one cell per field. */
    fun <T> rowsOf(items: List<T>, vararg fields: (T) -> Any?): List<List<Any?>> =
        items.map { item -> fields.map { field -> field(item) } }
}
