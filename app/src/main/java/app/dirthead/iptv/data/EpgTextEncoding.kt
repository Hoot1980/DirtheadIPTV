package app.dirthead.iptv.data

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Many Xtream-style panels return programme [title] and [description] as standard Base64 UTF-8.
 * If the string looks like Base64 and decodes to plausible UTF-8 text, returns the decoded text;
 * otherwise returns the original string.
 */
internal fun decodeEpgTextIfBase64(text: String): String {
    val t = text.trim()
    if (t.length < 4) return text
    if (!looksLikeBase64(t)) return text
    for (flags in intArrayOf(Base64.DEFAULT, Base64.URL_SAFE)) {
        val decoded = tryDecodeToUtf8(t, flags) ?: continue
        if (isPlausibleDecodedEpgText(decoded, t)) return decoded
    }
    return text
}

private fun looksLikeBase64(s: String): Boolean {
    if (s.length % 4 != 0) return false
    return s.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '-' || it == '_' || it == '=' }
}

private fun tryDecodeToUtf8(base64: String, flags: Int): String? {
    val bytes = try {
        Base64.decode(base64, flags)
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (bytes.isEmpty()) return null
    return try {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        null
    }
}

private fun isPlausibleDecodedEpgText(decoded: String, originalBase64: String): Boolean {
    if (decoded.isBlank()) return false
    if (decoded == originalBase64) return false
    val printable = decoded.count { c ->
        c.isLetterOrDigit() || c.isWhitespace() || c in ".,!?-'\":;()[]/&+%#@\$*~`|\\^"
    }
    return printable * 2 >= decoded.length
}
