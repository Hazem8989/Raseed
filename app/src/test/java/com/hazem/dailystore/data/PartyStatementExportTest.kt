package com.hazem.dailystore.data

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PartyStatementExportTest {
    private fun statement() = PartyStatement(
        partyId = "party-1",
        partyName = "عميل اختبار",
        partyType = "CUSTOMER",
        entries = listOf(
            PartyStatementEntry(
                id = "entry-1", date = "2026-11-02", type = "SALE_INVOICE",
                reference = "S-100", description = "فاتورة بيع آجل",
                debitUsdCents = 1250L, debitSypMinor = 187500L,
                balanceUsdCents = 1250L, balanceSypMinor = 187500L
            )
        ),
        closingBalanceUsdCents = 1250L,
        closingBalanceSypMinor = 187500L,
        unpricedEntryCount = 0
    )

    @Test fun csvContainsUtf8ArabicHeadersAndStatementValues() {
        val csv = String(PartyStatementExport.csv(statement()), Charsets.UTF_8)
        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("التاريخ"))
        assertTrue(csv.contains("عميل اختبار").not()) // party identity is shown in PDF title, not tabular rows
        assertTrue(csv.contains("فاتورة بيع آجل"))
        assertTrue(csv.contains("1250"))
    }

    @Test fun xlsxIsAValidOpenXmlZipWithRightToLeftSheet() {
        val bytes = PartyStatementExport.xlsx(statement())
        assertEquals('P'.code.toByte(), bytes[0])
        assertEquals('K'.code.toByte(), bytes[1])
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertTrue(entries.containsKey("[Content_Types].xml"))
        assertTrue(entries.containsKey("xl/workbook.xml"))
        val sheet = entries["xl/worksheets/sheet1.xml"] ?: error("worksheet missing")
        assertTrue(sheet.contains("rightToLeft=\"1\""))
        assertTrue(sheet.contains("فاتورة بيع آجل"))
        assertTrue(sheet.contains("1250"))
    }

    @Test fun pdfLinesKeepSyrianPoundsAsWholePounds() {
        val supplier = PartyStatement(
            partyId = "supplier-1", partyName = "مورد اختبار", partyType = "SUPPLIER",
            entries = listOf(PartyStatementEntry(
                id = "purchase-1", date = "2026-11-02", type = "PURCHASE_INVOICE",
                reference = "P-100", description = "فاتورة شراء",
                debitSypMinor = 187500L, balanceSypMinor = 187500L
            )),
            closingBalanceUsdCents = 0L, closingBalanceSypMinor = 187500L, unpricedEntryCount = 0
        )
        val lines = PartyStatementExport.pdfLines(supplier)
        assertTrue(lines[1].contains("187,500 ل.س"))
        assertTrue(lines.last().contains("187,500 ل.س"))
    }

    @Test fun pdfContentContainsArabicIdentityAndCorrectCurrencyUnits() {
        val lines = PartyStatementExport.pdfLines(statement())
        assertTrue(lines.first().contains("عميل اختبار"))
        assertTrue(lines[1].contains("12.50 USD"))
        assertTrue(lines.last().contains("فاتورة بيع آجل"))
        assertTrue(lines.last().contains("12.50 USD"))
    }
}
