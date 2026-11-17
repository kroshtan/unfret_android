package net.unfret.android

/** Unfret API keys: `ufk_<10-letter id>_<52-letter secret>`, lower-case base32 (the extension's `API_KEY_RE`). */
object ApiKeys {
    private val KEY = Regex("^ufk_([a-z2-7]{10})_[a-z2-7]{52}$")

    /**
     * The key in what the user pasted, or null if it isn't one. Spaces and line breaks a copy may add are
     * dropped; the key is lower case, so a shouted copy still works.
     */
    fun normalize(input: String): String? {
        val key = input.filterNot { it.isWhitespace() }.lowercase()
        return if (KEY.matches(key)) key else null
    }

    /** The public part of a key (it names the account), for showing which key is in use. */
    fun idOf(key: String): String? = KEY.matchEntire(key)?.groupValues?.get(1)

    /**
     * A key's public ID as the website shows an Unfret pass: in capitals, in two groups ("ABCDE-FGHIJ"), so it can be
     * read out and compared. Only the words change: the key itself stays as it is.
     */
    fun shownId(key: String): String? = idOf(key)?.uppercase()?.let { "${it.take(5)}-${it.drop(5)}" }
}
