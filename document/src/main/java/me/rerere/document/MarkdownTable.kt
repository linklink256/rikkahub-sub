package me.rerere.document

// ponytail: shared GFM table renderer pulled out of DocxParser & PptxParser,
// which both hand-rolled the identical rows -> "| a | b |" routine. Output is
// byte-identical to the originals: each row line is "| cell | cell | " (note
// the trailing space before the newline) and a "| --- |" separator row follows
// the first (header) row. Each caller keeps its own trailing-newline policy.
internal fun List<List<String>>.toMarkdownTable(): String {
    val maxCols = maxOfOrNull { it.size } ?: 0
    val sb = StringBuilder()
    for ((index, row) in withIndex()) {
        sb.append("| ")
        for (colIndex in 0 until maxCols) {
            val cellContent = if (colIndex < row.size) row[colIndex] else ""
            sb.append("$cellContent | ")
        }
        sb.append("\n")

        // Add separator after first row (header)
        if (index == 0) {
            sb.append("| ")
            repeat(maxCols) {
                sb.append("--- | ")
            }
            sb.append("\n")
        }
    }
    return sb.toString()
}