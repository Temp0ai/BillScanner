package com.billscanner.domain.parser

import com.google.mlkit.vision.text.Text

data class ExtractedCustomer(
    val name: String,
    val phone: String,
    val confidence: Float
)

class OcrParser {

    // Matches: 10-digit Indian mobile, optional +91/91 prefix, spaces/dashes allowed
    private val phoneRegex = Regex(
        """(?:\+?91[-\s]?)?\b([6-9]\d{9})\b"""
    )

    // Lines that are purely numeric or very short — likely not names
    private val excludePattern = Regex("""^[\d\s\-+()]{3,}$""")

    fun parse(text: Text): List<ExtractedCustomer> {
        val results = mutableListOf<ExtractedCustomer>()

        for (block in text.textBlocks) {
            for (line in block.lines) {
                val lineText = line.text.trim()
                val phoneMatch = phoneRegex.find(lineText)

                if (phoneMatch != null) {
                    val phone = phoneMatch.groupValues[1]

                    // Try to find name on same line (after removing phone)
                    val name = findNameForPhone(block.lines, lineText, phone)

                    if (name != null && name.length >= 2) {
                        results.add(
                            ExtractedCustomer(
                                name = name,
                                phone = phone,
                                confidence = line.confidence ?: 0.7f
                            )
                        )
                    }
                }
            }
        }
        return results
    }

    private fun findNameForPhone(
        siblingLines: List<Text.Line>,
        phoneLine: String,
        phone: String
    ): String? {
        // Remove the phone portion from the line
        val withoutPhone = phoneLine
            .replace(Regex("""\+?91[-\s]?\d{10}"""), "")
            .replace(Regex("""\d{10}"""), "")
            .trim()

        if (withoutPhone.length >= 2 && !excludePattern.matches(withoutPhone)) {
            return withoutPhone
        }

        // Fallback: look at adjacent lines for a name-like string
        for (sibling in siblingLines) {
            val text = sibling.text.trim()
            if (text == phoneLine) continue
            if (text.length >= 2 && !excludePattern.matches(text) && !phoneRegex.containsMatchIn(text)) {
                return text
            }
        }
        return null
    }
}
