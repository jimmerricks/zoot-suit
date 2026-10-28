package com.mickstarify.zooforzotero.ZoteroStorage.Database

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTest {
    @Test
    fun anAbstractLowerFoldsNeedsNoCopy() {
        for (text in listOf(null, "", "An Abstract", "Quotes “like” this — and ‘these’ … 50 %", "a\u0001b")) {
            assertTrue(text.toString(), SearchText.lowerFolds(text))
        }
    }

    @Test
    fun anAbstractLowerMightNotFoldNeedsACopy() {
        for (text in listOf(
            "Élan", "élan", "Sörlin", "東京", "Øresund", "øresund", "Straße", "ΣΩ", "naïve",
            "≠", // decomposes to = and a combining mark
            "Ⓐ", // a symbol with a lower case
            "é", // a combining mark folding drops
            "𐐀", // a letter beyond U+FFFF
        )) {
            assertFalse(text, SearchText.lowerFolds(text))
        }
    }
}
