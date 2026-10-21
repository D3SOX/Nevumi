package me.d3sox.nevumi.soundcloud

import java.text.Normalizer

private val marks = Regex("""\p{M}""")

internal actual fun withoutMarks(text: String): String = marks.replace(Normalizer.normalize(text, Normalizer.Form.NFKD), "")
