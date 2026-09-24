package lt.kantrybes.tvremote.data.adb

/**
 * ADB `input text` appends. It cannot replace a field the way IME batch edits can,
 * so the fallback only sends the added ASCII tail, or delete keys for a shorter tail.
 */
internal object TextSync {
    data class Plan(
        val deletes: Int = 0,
        val append: String? = null,
        val needsIme: Boolean = false,
    )

    fun plan(previous: String, next: String): Plan {
        if (next == previous) return Plan()
        if (next.startsWith(previous)) {
            val extra = next.substring(previous.length)
            return if (isAscii(extra)) Plan(append = extra) else Plan(needsIme = true)
        }
        if (previous.startsWith(next)) {
            return Plan(deletes = previous.length - next.length)
        }
        if (!isAscii(next)) return Plan(needsIme = true)
        return Plan(deletes = previous.length, append = next)
    }

    fun isAscii(text: String): Boolean = text.all { it.code in 0x20..0x7E }

    /** Spaces become `%s`. The argument is single-quoted for the TV shell. */
    fun inputCommand(chunk: String): String? {
        if (chunk.isEmpty() || !isAscii(chunk)) return null
        val encoded = buildString(chunk.length) {
            for (character in chunk) {
                if (character == ' ') append("%s") else append(character)
            }
        }
        val quoted = encoded.replace("'", "'\\''")
        return "input text '$quoted'"
    }
}
