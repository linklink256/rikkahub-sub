package me.rerere.rikkahub.utils

/**
 * 将 [from] 位置的元素移动到 [to] 位置，返回新列表（不改变原列表）。
 */
fun <T> List<T>.move(from: Int, to: Int): List<T> = toMutableList().apply {
    add(to, removeAt(from))
}

fun <E> Collection<E>.checkDifferent(
    other: Collection<E>,
    eq: (E, E) -> Boolean,
): Pair<List<E>, List<E>> {
    val added = other.filter { e ->
        this.none { eq(it, e) }
    }
    val removed = this.filter { e ->
        other.none { eq(it, e) }
    }
    return added to removed
}
