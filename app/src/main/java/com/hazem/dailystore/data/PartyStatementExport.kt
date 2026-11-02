package com.hazem.dailystore.data

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exports a read-only party statement to UTF-8 CSV, real XLSX, or a paginated Arabic PDF. */
object PartyStatementExport {
    private val headers = listOf(
        "التاريخ", "النوع", "المرجع", "البيان",
        "مدين USD سنت", "دائن USD سنت", "مدين ليرة", "دائن ليرة",
        "استرداد USD سنت", "استرداد ليرة", "الرصيد USD سنت", "الرصيد ليرة"
    )

    fun csv(statement: PartyStatement): ByteArray {
        val out = StringBuilder("\uFEFF")
        out.append(headers.joinToString(",") { csvCell(it) }).append("\r\n")
        statement.entries.forEach { row ->
            val cells = listOf(
                row.date, row.type, row.reference, row.description,
                row.debitUsdCents.toString(), row.creditUsdCents.toString(),
                row.debitSypMinor.toString(), row.creditSypMinor.toString(),
                row.cashRefundUsdCents.toString(), row.cashRefundSypMinor.toString(),
                row.balanceUsdCents.toString(), row.balanceSypMinor.toString()
            )
            out.append(cells.joinToString(",") { csvCell(it) }).append("\r\n")
        }
        return out.toString().toByteArray(StandardCharsets.UTF_8)
    }

    /** Minimal Office Open XML workbook; no third-party library or macro content. */
    fun xlsx(statement: PartyStatement): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes, StandardCharsets.UTF_8).use { zip ->
            zipEntry(zip, "[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>""")
            zipEntry(zip, "_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""")
            zipEntry(zip, "xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="كشف الحساب" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            zipEntry(zip, "xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>""")
            val xml = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0" rightToLeft="1"/></sheetViews><sheetData>""")
            xml.append(xlsxRow(1, headers))
            statement.entries.forEachIndexed { index, row ->
                xml.append(xlsxRow(index + 2, listOf(
                    row.date, row.type, row.reference, row.description,
                    row.debitUsdCents, row.creditUsdCents, row.debitSypMinor, row.creditSypMinor,
                    row.cashRefundUsdCents, row.cashRefundSypMinor, row.balanceUsdCents, row.balanceSypMinor
                )))
            }
            xml.append("</sheetData></worksheet>")
            zipEntry(zip, "xl/worksheets/sheet1.xml", xml.toString())
        }
        return bytes.toByteArray()
    }

    fun pdf(statement: PartyStatement): ByteArray {
        val document = PdfDocument()
        val pageWidth = 842
        val pageHeight = 595
        val left = 36f
        val right = pageWidth - 36f
        val lineHeight = 17f
        val rows = mutableListOf<String>()
        rows.add("الاسم: ${statement.partyName}")
        rows.add(if (statement.partyType == "CUSTOMER")
            "الرصيد المرجعي: ${money(statement.closingBalanceUsdCents)} USD"
        else "الرصيد المستحق: ${money(statement.closingBalanceSypMinor)} ل.س")
        rows.add("التاريخ | النوع | المرجع | البيان | مدين | دائن | الرصيد")
        statement.entries.forEach { row ->
            val debit = if (statement.partyType == "CUSTOMER") money(row.debitUsdCents) + " USD"
                else syp(row.debitSypMinor) + " ل.س"
            val credit = if (statement.partyType == "CUSTOMER") money(row.creditUsdCents) + " USD"
                else syp(row.creditSypMinor) + " ل.س"
            val balance = if (statement.partyType == "CUSTOMER") money(row.balanceUsdCents) + " USD"
                else syp(row.balanceSypMinor) + " ل.س"
            rows.add("${row.date} | ${row.type} | ${row.reference} | ${row.description} | $debit | $credit | $balance")
        }
        if (statement.unpricedEntryCount > 0) rows.add("تنبيه: ${statement.unpricedEntryCount} حركات بلا سعر صرف موثوق لم تدخل في الرصيد.")
        val perPage = 29
        val pages = maxOf(1, (rows.size + perPage - 1) / perPage)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 10f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 18f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        repeat(pages) { pageIndex ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create())
            val canvas = page.canvas
            canvas.drawTextRun("كشف حساب ${if (statement.partyType == "CUSTOMER") "عميل" else "مورد"}", 0,
                "كشف حساب ${if (statement.partyType == "CUSTOMER") "عميل" else "مورد"}".length,
                0, "كشف حساب ${if (statement.partyType == "CUSTOMER") "عميل" else "مورد"}".length,
                right, 34f, true, titlePaint)
            val start = pageIndex * perPage
            val end = minOf(rows.size, start + perPage)
            rows.subList(start, end).forEachIndexed { index, line ->
                val y = 65f + index * lineHeight
                canvas.drawTextRun(line, 0, line.length, 0, line.length, right, y, true, paint)
            }
            canvas.drawTextRun("صفحة ${pageIndex + 1} من $pages", 0,
                "صفحة ${pageIndex + 1} من $pages".length, 0,
                "صفحة ${pageIndex + 1} من $pages".length, right, pageHeight - 20f, true, paint)
            document.finishPage(page)
        }
        val out = ByteArrayOutputStream()
        document.writeTo(out)
        document.close()
        return out.toByteArray()
    }

    private fun xlsxRow(number: Int, values: List<Any>): String {
        val cells = values.mapIndexed { index, value ->
            val ref = column(index) + number
            if (value is Number) "<c r=\"$ref\"><v>$value</v></c>"
            else "<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xmlEscape(value.toString())}</t></is></c>"
        }.joinToString("")
        return "<row r=\"$number\">$cells</row>"
    }

    private fun column(index: Int): String {
        var n = index + 1
        var result = ""
        while (n > 0) {
            val rem = (n - 1) % 26
            result = ('A'.code + rem).toChar() + result
            n = (n - 1) / 26
        }
        return result
    }

    private fun zipEntry(zip: ZipOutputStream, name: String, value: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(value.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }

    private fun csvCell(value: String): String {
        val safe = if (value.trimStart().startsWith("=") || value.trimStart().startsWith("+") ||
            value.trimStart().startsWith("-") || value.trimStart().startsWith("@")) "'$value" else value
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }
    private fun xmlEscape(value: String): String = value.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun money(value: Long): String = String.format(java.util.Locale.US, "%.2f", value / 100.0)
    private fun syp(value: Long): String = String.format(java.util.Locale.US, "%,d", value)
}
