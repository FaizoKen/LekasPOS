package com.lekaspos.data.customer

import kotlin.test.assertEquals
import org.junit.Test

/** 2026-10 review: "+60 12-345 6789" and "012-345 6789" were different numbers to the search. */
class PhoneFormsTest {

    @Test
    fun aMalaysianNumberIsSearchedInAllItsForms() {
        val all = listOf("0123456789", "60123456789", "+60123456789")
        assertEquals(all, CustomerDao.phoneForms("012-345 6789"))
        assertEquals(setOf(*all.toTypedArray()), CustomerDao.phoneForms("+60 12-345 6789").toSet())
        assertEquals(setOf(*all.toTypedArray()), CustomerDao.phoneForms("6012 3456789").toSet())
        assertEquals(listOf("0"), CustomerDao.phoneForms("0")) // too short to mean a number
        assertEquals(listOf("+6512345678"), CustomerDao.phoneForms("+65 1234 5678")) // another country
        assertEquals(emptyList(), CustomerDao.phoneForms("abc"))
    }
}
