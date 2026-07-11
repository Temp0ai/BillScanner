package com.billscanner.domain.parser

import android.util.Log
import com.google.mlkit.vision.text.Text

data class ExtractedCustomer(
    val name: String,
    val phone: String,
    val totalAmount: String,
    val date: String,
    val confidence: Float
)

class OcrParser {

    companion object {
        private const val TAG = "OcrParser"
    }

    private val phoneRegex = Regex(
        """(?:\+?91[-\s]?)?(\d{10})"""
    )

    private val amountRegex = Regex(
        """(?:total|tot|grand\s*total|amount)[:\s]*(\d[\d,]*\.?\d*)""",
        RegexOption.IGNORE_CASE
    )

    private val dateRegex = Regex(
        """\b(\d{1,2}[/\-]\d{1,2}[/\-]\d{2,4})\b"""
    )

    fun parse(text: Text): List<ExtractedCustomer> {
        val fullText = text.text
        Log.d(TAG, "=== OCR raw text ===\n$fullText\n=== end ===")

        val allLines = mutableListOf<String>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                allLines.add(line.text.trim())
            }
        }
        Log.d(TAG, "Lines: $allLines")

        val results = mutableListOf<ExtractedCustomer>()
        val customers = mutableListOf<Triple<String, String, String>>()

        var foundName: String? = null
        var foundPhone: String? = null

        for (line in allLines) {
            val nameMatch = extractName(line)
            if (nameMatch != null) foundName = nameMatch

            val phoneMatch = findPhone(line)
            if (phoneMatch != null) foundPhone = phoneMatch

            if (foundName != null && foundPhone != null) {
                val total = extractTotal(fullText, allLines)
                val date = extractDate(fullText, allLines)
                customers.add(Triple(foundName!!, foundPhone!!, "$total|$date"))
                Log.d(TAG, "Matched pair: $foundName / $foundPhone / $total / $date")
                foundName = null
                foundPhone = null
            }
        }

        if (foundName != null && foundPhone != null) {
            val total = extractTotal(fullText, allLines)
            val date = extractDate(fullText, allLines)
            customers.add(Triple(foundName!!, foundPhone!!, "$total|$date"))
        }

        if (customers.isEmpty()) {
            for (line in allLines) {
                val phoneMatch = findPhone(line)
                if (phoneMatch != null) {
                    val nearbyName = findNearbyName(allLines, line)
                    if (nearbyName != null) {
                        val total = extractTotal(fullText, allLines)
                        val date = extractDate(fullText, allLines)
                        customers.add(Triple(nearbyName, phoneMatch, "$total|$date"))
                        Log.d(TAG, "Fallback match: $nearbyName / $phoneMatch / $total / $date")
                    }
                }
            }
        }

        if (customers.isEmpty()) {
            val singlePhone = findPhone(fullText)
            if (singlePhone != null) {
                val singleName = findNameFromFullText(fullText)
                val total = extractTotal(fullText, allLines)
                val date = extractDate(fullText, allLines)
                if (singleName != null) {
                    customers.add(Triple(singleName, singlePhone, "$total|$date"))
                    Log.d(TAG, "FullText match: $singleName / $singlePhone / $total / $date")
                }
            }
        }

        for ((name, phone, totalAndDate) in customers) {
            val (total, date) = totalAndDate.split("|")
            val cleanPhone = phone.takeLast(10)
            if (cleanPhone.length == 10) {
                results.add(
                    ExtractedCustomer(
                        name = name,
                        phone = cleanPhone,
                        totalAmount = total,
                        date = date,
                        confidence = 0.85f
                    )
                )
            }
        }

        Log.d(TAG, "Parsed ${results.size} customers")
        return results
    }

    private fun findPhone(text: String): String? {
        val match = phoneRegex.find(text) ?: return null
        val digits = match.groupValues[1].replace("\\s".toRegex(), "")
        return if (digits.length == 10) digits else null
    }

    private fun extractName(line: String): String? {
        val toPatterns = listOf(
            Regex("""(?:to\.?|customer|name)[:\s]*(.+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:to\.?|customer|name)\s+(.+)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in toPatterns) {
            val match = pattern.find(line)
            if (match != null) {
                val name = match.groupValues[1].trim()
                    .replace(Regex("""\d{10,}"""), "")
                    .replace(Regex("""mob[:\s]*\d+""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""no\.?[:\s]*\d+""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""date[:\s]*\S+""", RegexOption.IGNORE_CASE), "")
                    .trim()
                if (name.length >= 2 && !name.matches(Regex("""^[\d\s\-+().]+$"""))) {
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
        val fullMatch = amountRegex.find(fullText)
        if (fullMatch != null) return fullMatch.groupValues[1].replace(",", "")
        return ""
    }

    private fun extractDate(fullText: String, lines: List<String>): String {
        for (line in lines) {
            val match = dateRegex.find(line)
            if (match != null) return match.groupValues[1]
        }
        val fullMatch = dateRegex.find(fullText)
        if (fullMatch != null) return fullMatch.groupValues[1]
        return ""
    }

    private fun findNearbyName(lines: List<String>, phoneLine: String): String? {
        val idx = lines.indexOf(phoneLine)
        for (i in maxOf(0, idx - 3)..minOf(lines.size - 1, idx + 3)) {
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

    private fun findNameFromFullText(fullText: String): String? {
        val namePatterns = listOf(
            Regex("""(?:to\.?|customer|name)[:\s]+([A-Za-z][A-Za-z\s]{1,40})""", RegexOption.IGNORE_CASE),
            Regex("""(?:to\.?|customer|name)\s+([A-Za-z][A-Za-z\s]{1,40})""", RegexOption.IGNORE_CASE)
        )
        for (pattern in namePatterns) {
            val match = pattern.find(fullText)
            if (match != null) {
                val name = match.groupValues[1].trim()
                    .replace(Regex("""\d+"""), "")
                    .trim()
                if (name.length >= 2) return name
            }
        }
        return null
    }
}
