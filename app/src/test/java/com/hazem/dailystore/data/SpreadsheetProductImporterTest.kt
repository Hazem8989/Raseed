package com.hazem.dailystore.data

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpreadsheetProductImporterTest {
    @Test
    fun parsesPreparedArabicCatalogCsvWithoutInventingStockOrBarcode() {
        val csv = """اسم المنتج,التصنيف,وحدة القياس,تكلفة الشراء (ل.س),سعر البيع (ل.س),SKU,الباركود
مياه 1.5 لتر,مشروبات,طرد,12000,15000,SKU-001,
"متة، ممتازة",غير مصنف,قطعة,8000,10000,SKU-002,
"""
        val rows = SpreadsheetProductImporter.parse(
            "catalog.csv",
            ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8))
        )
        assertEquals(2, rows.size)
        assertEquals("مياه 1.5 لتر", rows[0].name)
        assertEquals(12000L, rows[0].purchaseCostSyp)
        assertEquals(15000L, rows[0].salePriceSyp)
        assertEquals("طرد", rows[0].unit)
        assertEquals("SKU-001", rows[0].sku)
        assertNull(rows[0].barcode)
        assertEquals("متة، ممتازة", rows[1].name)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedFileType() {
        SpreadsheetProductImporter.parse(
            "catalog.xls",
            ByteArrayInputStream(byteArrayOf())
        )
    }
}
