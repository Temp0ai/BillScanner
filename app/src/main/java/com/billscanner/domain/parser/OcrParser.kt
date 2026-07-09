package com.billscanner.domain.parser

import com.google.mlkit.vision.text.Text

data class ExtractedCustomer(
    val name: String,
    val phone: String,
    val totalAmount: String,
    val confidence: Float
)

class OcrParser {

    private val phoneRegex = Regex(
        """(?:\+?91[-\s]?)?\b([6-9]\d{9})\b"""
    )

    private val amountRegex = Regex(
        """(?:total|tot|grand\s*total|amount)[:\s]*(\d[\d,]*\.?\d*)""",
        RegexOption.IGNORE_CASE
    )

    fun parse(text: Text): List<ExtractedCustomer> {
        val fullText = text.text
        val allLines = mutableListOf<String>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                allLines.add(line.text.trim())
            }
        }

        val results = mutableListOf<ExtractedCustomer>()
        val customers = mutableListOf<Triple<String, String, String>>()

        var foundName: String? = null
        var foundPhone: String? = null

        for (line in allLines) {
            val nameMatch = extractName(line)
            if (nameMatch != null) foundName = nameMatch

            val phoneMatch = phoneRegex.find(line)
            if (phoneMatch != null) foundPhone = phoneMatch.groupValues[1]

            if (foundName != null && foundPhone != null) {
                val total = extractTotal(fullText, allLines)
                customers.add(Triple(foundName!!, foundPhone!!, total))
                foundName = null
                foundPhone = null
            }
        }

        if (foundName != null && foundPhone != null) {
            val total = extractTotal(fullText, allLines)
            customers.add(Triple(foundName!!, foundPhone!!, total))
        }

        if (customers.isEmpty()) {
            for (line in allLines) {
                val phoneMatch = phoneRegex.find(line)
                if (phoneMatch != null) {
                    val phone = phoneMatch.groupValues[1]
                    val nearbyName = findNearbyName(allLines, line)
                    if (nearbyName != null) {
                        val total = extractTotal(fullText, allLines)
                        customers.add(Triple(nearbyName, phone, total))
                    }
                }
            }
        }

        for ((name, phone, total) in customers) {
            if (phone.length == 10) {
                results.add(
                    ExtractedCustomer(
                        name = name,
                        phone = phone,
                        totalAmount = total,
                        confidence = 0.85f
                    )
                )
            }
        }

        return results
    }

    private fun extractName(line: String): String? {
        val toPatterns = listOf(
            Regex("""(?:to|customer|name)[:\s]*(.+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:to|customer|name)\s+(.+)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in toPatterns) {
            val match = pattern.find(line)
            if (match != null) {
                val name = match.groupValues[1].trim()
                    .replace(Regex("""\d{10}"""), "")
                    .replace(Regex("""mob[:\s]*\d+""", RegexOption.IGNORE_CASE), "")
                    .trim()
                if (name.length >= 2 && !name.matches(Regex("""^[\d\s\-+()]+$"""))) {
                    return name
                }
            }
        }
        return null
    }

    private fun extractTotal(fullText: String, lines: List<String>): String {
        for (line in lines.reversed()) {
            val match = amountRegex.find(line)
            if (match != null) return match.groupValues[1].replace(",", "")
        }
        return ""
    }

    private fun findNearbyName(lines: List<String>, phoneLine: String): String? {
        val idx = lines.indexOf(phoneLine)
        for (i in maxOf(0, idx - 2)..minOf(lines.size - 1, idx + 2)) {
            val line = lines[i]
            if (line == phoneLine) continue
            if (phoneRegex.containsMatchIn(line)) continue
            val cleaned = line.trim()
            if (cleaned.length >= 2 && !cleaned.matches(Regex("""^[\d\s\-+().]+$"""))) {
                return cleaned
            }
        }
        return null
    }
}
