package com.hunter.btc.recovery

/**
 * Parsea una seed phrase ingresada por el usuario.
 * Las palabras desconocidas se marcan con "???" (o "?" o "____").
 *
 * Ejemplo de input:
 *   "abandon ??? letter ??? advice cage absurd amount doctor acoustic avoid ???"
 *
 * Resultado:
 *   slots = [0:"abandon", 1:null, 2:"letter", 3:null, 4:"advice", ...]
 *   missingCount = 3
 *   missingIndices = [1, 3, 11]
 */
data class ParsedPhrase(
    val slots: List<String?>,
    val wordCount: Int,
    val missingCount: Int,
    val missingIndices: List<Int>,
    val knownWords: List<String>
)

sealed class ParseResult {
    data class Success(val parsed: ParsedPhrase) : ParseResult()
    data class Error(val message: String) : ParseResult()
}

object RecoveryParser {

    private val MISSING_TOKENS = setOf("???", "?", "____", "_", "***", "xx", "XX")
    private val VALID_LENGTHS = setOf(12, 15, 18, 21, 24)
    const val MAX_MISSING = 4

    fun parse(input: String, wordlist: Set<String>): ParseResult {

        val cleaned = input.trim()
            .lowercase()
            .replace(Regex("[,;.|]+"), " ")
            .replace(Regex("\\s+"), " ")

        if (cleaned.isEmpty()) {
            return ParseResult.Error("Type your seed phrase.")
        }

        val tokens = cleaned.split(" ").filter { it.isNotEmpty() }

        if (tokens.size !in VALID_LENGTHS) {
            return ParseResult.Error(
                "A seed phrase has 12, 15, 18, 21 or 24 words. " +
                "Found: ${tokens.size}"
            )
        }

        val slots = mutableListOf<String?>()
        val missingIndices = mutableListOf<Int>()
        val knownWords = mutableListOf<String>()
        val invalidWords = mutableListOf<Pair<Int, String>>()

        tokens.forEachIndexed { index, token ->
            if (isMissingToken(token)) {
                slots.add(null)
                missingIndices.add(index)
            } else {
                if (token !in wordlist) {
                    invalidWords.add(Pair(index + 1, token))
                }
                slots.add(token)
                knownWords.add(token)
            }
        }

        if (invalidWords.isNotEmpty()) {
            val details = invalidWords.joinToString(", ") { (pos, word) ->
                "position $pos: \"$word\""
            }
            return ParseResult.Error(
                "Words that are not in the BIP39 list: $details\n" +
                "Check the spelling, or mark them with ???"
            )
        }

        val missingCount = missingIndices.size

        if (missingCount == 0) {
            return ParseResult.Error(
                "No missing words were found. " +
                "Use ??? for the words you do not remember."
            )
        }

        if (missingCount > MAX_MISSING) {
            val combinations = estimateCombinations(missingCount)
            return ParseResult.Error(
                "There are $missingCount missing words (~$combinations combinations). " +
                "The recommended maximum is $MAX_MISSING."
            )
        }

        return ParseResult.Success(
            ParsedPhrase(
                slots = slots,
                wordCount = tokens.size,
                missingCount = missingCount,
                missingIndices = missingIndices,
                knownWords = knownWords
            )
        )
    }

    fun estimateCombinations(missingCount: Int): String {
        val total = Math.pow(2048.0, missingCount.toDouble()).toLong()
        return when {
            total < 1_000_000L -> "${total / 1000}K"
            total < 1_000_000_000L -> "${total / 1_000_000}M"
            else -> "${total / 1_000_000_000}B"
        }
    }

    fun estimateTimeSeconds(missingCount: Int): Long {
        val total = Math.pow(2048.0, missingCount.toDouble()).toLong()
        return total / 50_000L
    }

    fun formatEstimatedTime(seconds: Long): String {
        return when {
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> "${seconds / 60} min"
            seconds < 86400 -> "${seconds / 3600} hours"
            else -> "${seconds / 86400} days"
        }
    }

    private fun isMissingToken(token: String): Boolean {
        return token in MISSING_TOKENS || token.all { it == '?' || it == '_' || it == '*' }
    }
}
