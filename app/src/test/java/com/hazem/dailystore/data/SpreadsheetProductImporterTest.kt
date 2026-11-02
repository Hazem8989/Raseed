package com.hazem.dailystore.data

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpreadsheetProductImporterTest {
    @Test
    fun parsesPreparedArabicCatalogCsvWithoutInventingStockOrBarcode() {
        val csv = """اسم المنتج,التصنيف,وحدة القياس,تكلفة الشراء (ل.س),سعر البيع (ل.س),SKU,الباركود,تتبع المخزون
مياه 1.5 لتر,مشروبات,طرد,12000,15000,SKU-001,,نعم
"متة، ممتازة",غير مصنف,قطعة,8000,10000,SKU-002,,لا
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
        assertEquals(false, rows[1].tracksInventory)
        assertEquals(true, rows[0].tracksInventory)
    }

    @Test
    fun parsesActualPreparedCatalogHeadersWithSemicolonDelimiter() {
        val csv = """index;SKU;اسم الصنف;التصنيف;الوحدة;تكلفة الشراء (ل.س);سعر البيع (ل.س);الباركود;الكمية الافتتاحية;تتبع المخزون;متاح للبيع;ملاحظات
1;SKU-001;مياه 1.5 لتر;مشروبات;طرد;12000;15000;;0;نعم;نعم;
2;SKU-002;متة ممتازة;مشروبات;قطعة;8000;10000;;0;لا;نعم;
"""
        val rows = SpreadsheetProductImporter.parse(
            "The_Daily_Shop_Clean_Products.csv",
            ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8))
        )
        assertEquals(2, rows.size)
        assertEquals("مياه 1.5 لتر", rows[0].name)
        assertEquals("SKU-001", rows[0].sku)
        assertEquals(12000L, rows[0].purchaseCostSyp)
        assertEquals(15000L, rows[0].salePriceSyp)
        assertEquals("طرد", rows[0].unit)
        assertEquals(false, rows[1].tracksInventory)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedFileType() {
        SpreadsheetProductImporter.parse(
            "catalog.xls",
            ByteArrayInputStream(byteArrayOf())
        )
    }
}
