package com.hazem.dailystore.data

import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader

data class ProductImportRow(
    val rowNumber: Int,
    val name: String,
    val category: String?,
    val unit: String?,
    val purchaseCostSyp: Long?,
    val salePriceSyp: Long?,
    val purchaseCostUsdCents: Long?,
    val salePriceUsdCents: Long?,
    val sku: String?,
    val barcode: String?,
    val tracksInventory: Boolean = true
) {
    val usesSypPrices: Boolean get() = purchaseCostSyp != null || salePriceSyp != null
}

/**
 * Reads the first worksheet of .xlsx files and UTF-8 CSV files.
 * Import is deliberately read-only here; persistence and validation happen
 * together in StoreRepository inside one SQLite transaction.
 */
object SpreadsheetProductImporter {
    fun parse(fileName: String, input: InputStream): List<ProductImportRow> {
        val rows = if (fileName.lowercase(Locale.ROOT).endsWith(".xlsx")) {
            parseXlsx(input)
        } else {
            require(fileName.lowercase(Locale.ROOT).endsWith(".csv")) {
                "الصيغة المدعومة هي CSV أو Excel بصيغة XLSX"
            }
            parseCsv(input.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF"))
        }
        require(rows.size >= 2) { "الملف لا يحتوي على صفوف أصناف" }
        return mapRows(rows)
    }

    private fun detectDelimiter(source: String): Char {
        val candidates = listOf(',', ';', '\t')
        val firstRecord = source.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        var quoted = false
        val counts = candidates.associateWith { 0 }.toMutableMap()
        var i = 0
        while (i < firstRecord.length) {
            val ch = firstRecord[i]
            when {
                ch == '"' && quoted && i + 1 < firstRecord.length && firstRecord[i + 1] == '"' -> i++
                ch == '"' -> quoted = !quoted
                !quoted && ch in candidates -> counts[ch] = (counts[ch] ?: 0) + 1
            }
            i++
        }
        return candidates.maxByOrNull { counts[it] ?: 0 } ?: ','
    }

    private fun parseCsv(source: String): List<List<String>> {
        // CSV files exported by spreadsheet apps may use comma, semicolon, or tab.
        // Detect the separator from the first non-empty record while respecting quotes.
        val delimiter = detectDelimiter(source)

        val result = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < source.length) {
            val ch = source[i]
            when {
                ch == '"' && quoted && i + 1 < source.length && source[i + 1] == '"' -> {
                    cell.append('"'); i++
                }
                ch == '"' -> quoted = !quoted
                ch == delimiter && !quoted -> {
                    row.add(cell.toString()); cell.setLength(0)
                }
                (ch == '\n' || ch == '\r') && !quoted -> {
                    if (ch == '\r' && i + 1 < source.length && source[i + 1] == '\n') i++
                    row.add(cell.toString()); cell.setLength(0)
                    if (row.any { it.isNotBlank() }) result.add(row)
                    row = mutableListOf()
                }
                else -> cell.append(ch)
            }
            i++
        }
        require(!quoted) { "يوجد اقتباس غير مغلق في ملف CSV" }
        row.add(cell.toString())
        if (row.any { it.isNotBlank() }) result.add(row)
        return result
    }

    private fun parseXlsx(input: InputStream): List<List<String>> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && (entry.name == "xl/sharedStrings.xml" ||
                        entry.name == "xl/worksheets/sheet1.xml")) {
                    entries[entry.name] = zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }
        val sheet = entries["xl/worksheets/sheet1.xml"]
            ?: error("لم أجد ورقة العمل الأولى داخل ملف Excel")
        val shared = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        val document = parseXml(sheet.toString(Charsets.UTF_8))
        val rowNodes = document.getElementsByTagName("row")
        val result = mutableListOf<List<String>>()
        for (r in 0 until rowNodes.length) {
            val row = rowNodes.item(r) as Element
            val cells = row.getElementsByTagName("c")
            val values = sortedMapOf<Int, String>()
            for (c in 0 until cells.length) {
                val cell = cells.item(c) as Element
                val ref = cell.getAttribute("r")
                val column = ref.takeWhile { it.isLetter() }.fold(0) { acc, char ->
                    acc * 26 + (char.uppercaseChar() - 'A' + 1)
                } - 1
                if (column < 0) continue
                val type = cell.getAttribute("t")
                val value = when (type) {
                    "s" -> cell.getElementsByTagName("v").item(0)?.textContent?.toIntOrNull()
                        ?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> cell.getElementsByTagName("t").item(0)?.textContent.orEmpty()
                    else -> cell.getElementsByTagName("v").item(0)?.textContent.orEmpty()
                }
                values[column] = value
            }
            if (values.isNotEmpty()) {
                val last = values.lastKey()
                result.add((0..last).map { values[it].orEmpty() })
            }
        }
        return result
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val doc = parseXml(bytes.toString(Charsets.UTF_8))
        val nodes = doc.getElementsByTagName("si")
        return (0 until nodes.length).map { index ->
            val si = nodes.item(index) as Element
            val textNodes = si.getElementsByTagName("t")
            (0 until textNodes.length).joinToString("") { textNodes.item(it).textContent.orEmpty() }
        }
    }

    private fun parseXml(xml: String) =
        DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            setExpandEntityReferences(false)
        }.newDocumentBuilder().parse(InputSource(StringReader(xml)))

    private fun mapRows(table: List<List<String>>): List<ProductImportRow> {
        val headers = table.first().map(::normalizeHeader)
        fun column(vararg names: String) = headers.indexOfFirst { it in names.map(::normalizeHeader) }
        val nameCol = column("اسم المنتج", "اسم الصنف", "اسم المادة", "الاسم", "product name", "name")
        require(nameCol >= 0) { "لم أجد عمود اسم المنتج في الصف الأول" }
        val categoryCol = column("التصنيف", "اسم التصنيف", "الفئة", "category")
        val unitCol = column("وحدة القياس", "الوحدة", "وحدة", "unit")
        val costSypCol = column("تكلفة الشراء ل س", "تكلفة الشراء بالليرة السورية", "purchase cost syp", "cost syp")
        val saleSypCol = column("سعر البيع ل س", "سعر المبيع ل س", "سعر البيع بالليرة السورية", "سعر المبيع بالليرة السورية", "sale price syp", "selling price syp")
        val costUsdCol = column("تكلفة الشراء بالدولار", "purchase cost usd", "cost usd")
        val saleUsdCol = column("سعر البيع بالدولار", "سعر المبيع بالدولار", "sale price usd", "selling price usd")
        val skuCol = column("sku", "رمز sku")
        val barcodeCol = column("الباركود", "barcode", "باركود")
        val trackInventoryCol = column("تتبع المخزون", "track inventory", "track_inventory")
        require(saleSypCol >= 0 || saleUsdCol >= 0) { "لم أجد عمود سعر البيع بالليرة أو الدولار" }
        val output = mutableListOf<ProductImportRow>()
        for (index in 1 until table.size) {
            val cells = table[index]
            fun value(col: Int): String? = if (col < 0) null else cells.getOrNull(col)?.trim()?.takeIf { it.isNotEmpty() }
            val name = value(nameCol) ?: continue
            output.add(ProductImportRow(
                rowNumber = index + 1,
                name = name,
                category = value(categoryCol),
                unit = value(unitCol),
                purchaseCostSyp = value(costSypCol)?.let { money(it, index + 1) },
                salePriceSyp = value(saleSypCol)?.let { money(it, index + 1) },
                purchaseCostUsdCents = value(costUsdCol)?.let { usdCents(it, index + 1) },
                salePriceUsdCents = value(saleUsdCol)?.let { usdCents(it, index + 1) },
                sku = value(skuCol),
                barcode = value(barcodeCol),
                tracksInventory = value(trackInventoryCol)?.let { raw ->
                    when (raw.trim().lowercase(Locale.ROOT)) {
                        "نعم", "yes", "true", "1" -> true
                        "لا", "no", "false", "0" -> false
                        else -> error("قيمة تتبع المخزون غير مفهومة في الصف ${index + 1}: $raw")
                    }
                } ?: true
            ))
        }
        require(output.isNotEmpty()) { "لم أجد أصنافاً صالحة للاستيراد" }
        return output
    }

    private fun normalizeHeader(value: String): String = value.trim().lowercase(Locale.ROOT)
        .replace("(", " ").replace(")", " ").replace(".", " ")
        .replace("ـ", "").replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
        .replace("ى", "ي").replace(Regex("\\s+"), " ").trim()

    private fun normalizeDigits(value: String): String = buildString {
        value.forEach { ch ->
            append(when (ch) {
                in '٠'..'٩' -> ('0'.code + (ch.code - '٠'.code)).toChar()
                in '۰'..'۹' -> ('0'.code + (ch.code - '۰'.code)).toChar()
                else -> ch
            })
        }
    }

    private fun decimal(value: String, row: Int): BigDecimal {
        val clean = normalizeDigits(value).replace("٬", "").replace("٫", ".")
            .replace(" ", "").replace(",", "")
        return try { BigDecimal(clean) } catch (_: Exception) {
            error("قيمة رقمية غير صحيحة في الصف $row: $value")
        }
    }

    private fun money(value: String, row: Int): Long =
        decimal(value, row).setScale(0, RoundingMode.HALF_UP).longValueExact()
            .also { require(it >= 0) { "السعر سالب في الصف $row" } }

    private fun usdCents(value: String, row: Int): Long =
        decimal(value, row).multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP)
            .longValueExact().also { require(it >= 0) { "السعر سالب في الصف $row" } }
}
