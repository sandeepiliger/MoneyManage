package ai.labs32.khaata.data.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import ai.labs32.khaata.R
import ai.labs32.khaata.core.calc.CashflowSummary
import ai.labs32.khaata.core.calc.CategorySpend
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.MoneyFormatter
import ai.labs32.khaata.core.money.MoneyStyle
import ai.labs32.khaata.core.money.SignStyle
import ai.labs32.khaata.data.backup.ExportedFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything a statement PDF needs, gathered up front.
 *
 * [periodLabel] is resolved by the caller rather than here: it comes from [ReportPeriod][
 * ai.labs32.khaata.core.common.ReportPeriod] via `stringResource`, and this class has no Compose
 * context to call that from. [generatedOn] is passed in rather than read from the system clock
 * for the same reason every other export in this app takes its timestamp from
 * [ai.labs32.khaata.core.common.KhaataClock]: a fixed clock makes the output reproducible in a
 * test.
 */
data class StatementData(
    val periodLabel: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val generatedOn: LocalDate,
    val currency: CurrencyCode,
    val summary: CashflowSummary,
    val categories: List<CategorySpend>,
    val transactions: List<Transaction>,
)

/**
 * Draws a bank-statement-style PDF from a period's cash flow and hands back a shareable file.
 *
 * Every other export in this app hands the user their own data back in a form only a spreadsheet
 * or this app can read. This one asks a different question: can it be handed to someone else —
 * a landlord, a visa officer, a co-founder — and read as a real financial document on first
 * glance? That is worth drawing by hand with [PdfDocument] rather than reusing
 * [ai.labs32.khaata.data.backup.BackupManager]'s writer, because a backup is a private artifact
 * meant to come back into this app and a statement is meant to leave it.
 *
 * It writes into the same `files/exports` directory the backups use and shares it through the
 * same FileProvider authority, so the one entry already declared in the manifest covers this too.
 * A previous statement is replaced rather than accumulated, for the same reason
 * [ai.labs32.khaata.data.backup.BackupManager] does not keep every export it has ever made.
 */
@Singleton
class StatementExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Renders [statement] to PDF and writes it into the exports directory. */
    suspend fun export(statement: StatementData): Result<ExportedFile> = withContext(Dispatchers.IO) {
        runCatching {
            val document = renderDocument(statement)
            // close() in a finally rather than after writeTo: a PdfDocument holds native page
            // memory, and a write that fails part way through would otherwise leak it for the
            // life of the process.
            try {
                val dir = exportsDir()
                // A previous statement is replaced rather than accumulated, mirroring how the
                // JSON and CSV exports treat their own file extension.
                dir.listFiles().orEmpty()
                    .filter { it.isFile && it.extension == "pdf" }
                    .forEach { it.delete() }

                val file = File(dir, "khaata-statement-${timestamp(statement.generatedOn)}.pdf")
                FileOutputStream(file).use { out -> document.writeTo(out) }

                ExportedFile(
                    fileName = file.name,
                    uri = uriFor(file),
                    sizeBytes = file.length(),
                    mimeType = MIME_PDF,
                    recordCount = statement.transactions.size,
                )
            } finally {
                document.close()
            }
        }.onFailure { KhaataLog.e(TAG, "Statement export failed", it) }
    }

    // ---- Rendering -----------------------------------------------------------------------------

    private fun renderDocument(statement: StatementData): PdfDocument {
        val document = PdfDocument()
        var pageNumber = 1
        var page = document.startPage(pageInfo(pageNumber))
        var canvas = page.canvas

        fun closeCurrentPage() {
            drawFooter(canvas, statement, pageNumber)
            document.finishPage(page)
        }

        fun startNextPage(): Float {
            closeCurrentPage()
            pageNumber += 1
            page = document.startPage(pageInfo(pageNumber))
            canvas = page.canvas
            return drawContinuationHeader(canvas, statement)
        }

        var y = drawBrandHeader(canvas, statement)
        y = drawSummary(canvas, statement, y)
        y = drawCategories(canvas, statement, y)

        // The table header takes about 40pt; if it would land in the footer's territory, it
        // belongs on a fresh page rather than being drawn somewhere the footer will overlap it.
        if (y + 40f > BOTTOM_LIMIT) y = startNextPage()
        y = drawSectionTitle(canvas, context.getString(R.string.statement_transactions), y)
        y = drawTableColumnHeaders(canvas, y)

        statement.transactions.forEachIndexed { index, transaction ->
            if (y + ROW_HEIGHT > BOTTOM_LIMIT) {
                y = startNextPage()
                y = drawTableColumnHeaders(canvas, y)
            }
            drawTransactionRow(canvas, transaction, index, y)
            y += ROW_HEIGHT
        }
        closeCurrentPage()

        return document
    }

    private fun pageInfo(pageNumber: Int): PdfDocument.PageInfo =
        PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()

    /** The branded band at the top of the first page — the whole point of this feature. */
    private fun drawBrandHeader(canvas: Canvas, statement: StatementData): Float {
        val bandHeight = 96f
        canvas.drawRect(
            0f,
            0f,
            PAGE_WIDTH.toFloat(),
            bandHeight,
            fillPaint(BRAND_COLOR),
        )

        canvas.drawText(
            context.getString(R.string.app_name),
            MARGIN,
            38f,
            textPaint(26f, Color.WHITE, bold = true),
        )
        canvas.drawText(
            context.getString(R.string.statement_title),
            MARGIN,
            58f,
            textPaint(13f, Color.WHITE),
        )

        val periodPaint = textPaint(11f, Color.WHITE)
        val periodText = "${statement.periodLabel} · ${formatRange(statement.periodStart, statement.periodEnd)}"
        val fitted = ellipsize(periodPaint, periodText, CONTENT_RIGHT - MARGIN - 90f)
        canvas.drawText(fitted, CONTENT_RIGHT - periodPaint.measureText(fitted), 38f, periodPaint)

        return bandHeight + 30f
    }

    /** A slim, unbanded heading repeated on every page after the first. */
    private fun drawContinuationHeader(canvas: Canvas, statement: StatementData): Float {
        val label = "${context.getString(R.string.app_name)} — ${context.getString(R.string.statement_title)}"
        canvas.drawText(label, MARGIN, 30f, textPaint(12f, Color.DKGRAY, bold = true))
        val rangePaint = textPaint(10f, Color.DKGRAY)
        val rangeText = formatRange(statement.periodStart, statement.periodEnd)
        canvas.drawText(rangeText, CONTENT_RIGHT - rangePaint.measureText(rangeText), 30f, rangePaint)
        canvas.drawLine(MARGIN, 40f, CONTENT_RIGHT, 40f, linePaint())
        return 60f
    }

    private fun drawSummary(canvas: Canvas, statement: StatementData, startY: Float): Float {
        var y = startY
        val labelPaint = textPaint(9f, Color.GRAY)
        val amountPaint = textPaint(16f, Color.BLACK, bold = true)
        val colWidth = (CONTENT_RIGHT - MARGIN) / 3f

        val columns = listOf(
            context.getString(R.string.statement_income) to
                MoneyFormatter.format(statement.summary.income, MoneyStyle.FULL, SignStyle.NEVER),
            context.getString(R.string.statement_expense) to
                MoneyFormatter.format(statement.summary.expense, MoneyStyle.FULL, SignStyle.NEVER),
            context.getString(R.string.statement_net) to
                MoneyFormatter.format(statement.summary.net, MoneyStyle.FULL, SignStyle.ALWAYS),
        )

        columns.forEachIndexed { index, (label, amount) ->
            val x = MARGIN + colWidth * index
            canvas.drawText(label, x, y, labelPaint)
            canvas.drawText(amount, x, y + 20f, amountPaint)
        }

        y += 36f
        canvas.drawLine(MARGIN, y, CONTENT_RIGHT, y, linePaint())
        return y + 24f
    }

    private fun drawCategories(canvas: Canvas, statement: StatementData, startY: Float): Float {
        if (statement.categories.isEmpty()) return startY
        var y = drawSectionTitle(canvas, context.getString(R.string.statement_categories), startY)

        val namePaint = textPaint(10f, Color.BLACK)
        val percentPaint = textPaint(9f, Color.GRAY)
        val amountPaint = textPaint(10f, Color.BLACK)
        val uncategorised = context.getString(R.string.categories_uncategorised)
        val amountColWidth = 90f
        val percentColWidth = 50f
        val nameMaxWidth = CONTENT_RIGHT - MARGIN - amountColWidth - percentColWidth - 20f

        for ((index, spend) in statement.categories.withIndex()) {
            if (y + ROW_HEIGHT > BOTTOM_LIMIT) {
                // The category list is rolled up to parents already, so this is reached only by
                // an unusually fragmented month. Naming the remainder beats silently cutting it.
                val remaining = statement.categories.size - index
                canvas.drawText("+$remaining", MARGIN, y, percentPaint)
                break
            }

            val name = ellipsize(namePaint, spend.category?.name ?: uncategorised, nameMaxWidth)
            canvas.drawText(name, MARGIN, y, namePaint)

            val percentText = MoneyFormatter.percentage(spend.shareOfTotalPercent, decimals = 0)
            val percentX = CONTENT_RIGHT - amountColWidth - 10f - percentPaint.measureText(percentText)
            canvas.drawText(percentText, percentX, y, percentPaint)

            val amountText = MoneyFormatter.format(spend.amount, MoneyStyle.FULL, SignStyle.NEVER)
            canvas.drawText(amountText, CONTENT_RIGHT - amountPaint.measureText(amountText), y, amountPaint)

            y += ROW_HEIGHT
        }

        return y + 12f
    }

    private fun drawSectionTitle(canvas: Canvas, title: String, startY: Float): Float {
        canvas.drawText(title, MARGIN, startY, textPaint(13f, Color.BLACK, bold = true))
        return startY + 20f
    }

    private fun drawTableColumnHeaders(canvas: Canvas, startY: Float): Float {
        val headerPaint = textPaint(9f, Color.GRAY, bold = true)
        canvas.drawText(context.getString(R.string.statement_col_date), MARGIN, startY, headerPaint)
        canvas.drawText(context.getString(R.string.statement_col_description), descColX(), startY, headerPaint)
        val amountHeader = context.getString(R.string.statement_col_amount)
        canvas.drawText(
            amountHeader,
            CONTENT_RIGHT - headerPaint.measureText(amountHeader),
            startY,
            headerPaint,
        )
        val ruleY = startY + 6f
        canvas.drawLine(MARGIN, ruleY, CONTENT_RIGHT, ruleY, linePaint())
        return ruleY + ROW_HEIGHT
    }

    private fun drawTransactionRow(
        canvas: Canvas,
        transaction: Transaction,
        index: Int,
        y: Float,
    ) {
        if (index % 2 == 1) {
            canvas.drawRect(
                MARGIN - 4f,
                y - ROW_HEIGHT + 5f,
                CONTENT_RIGHT + 4f,
                y + 5f,
                fillPaint(SHADE_COLOR),
            )
        }

        val bodyPaint = textPaint(9.5f, Color.BLACK)
        val dateText = transaction.occurredOn.format(DateTimeFormatter.ofPattern("d MMM"))
        canvas.drawText(dateText, MARGIN, y, bodyPaint)

        val description = ellipsize(bodyPaint, transaction.displayTitle(NO_DESCRIPTION), descColWidth())
        canvas.drawText(description, descColX(), y, bodyPaint)

        val signedAmount = if (transaction.type == TransactionType.EXPENSE) {
            -transaction.amount
        } else {
            transaction.amount
        }
        val amountText = MoneyFormatter.format(signedAmount, MoneyStyle.FULL, SignStyle.ALWAYS)
        canvas.drawText(amountText, CONTENT_RIGHT - bodyPaint.measureText(amountText), y, bodyPaint)
    }

    private fun drawFooter(canvas: Canvas, statement: StatementData, pageNumber: Int) {
        val notePaint = textPaint(7.5f, Color.GRAY)
        val note = ellipsize(
            notePaint,
            context.getString(R.string.statement_transfers_note),
            CONTENT_RIGHT - MARGIN,
        )
        canvas.drawText(note, MARGIN, PAGE_HEIGHT - 28f, notePaint)

        val footPaint = textPaint(8f, Color.GRAY)
        val generated = context.getString(
            R.string.statement_generated,
            statement.generatedOn.format(DateTimeFormatter.ofPattern("d MMM yyyy")),
        )
        canvas.drawText(generated, MARGIN, PAGE_HEIGHT - 14f, footPaint)

        val pageText = context.getString(R.string.statement_page, pageNumber)
        canvas.drawText(pageText, CONTENT_RIGHT - footPaint.measureText(pageText), PAGE_HEIGHT - 14f, footPaint)
    }

    // ---- Layout helpers --------------------------------------------------------------------------

    private fun descColX(): Float = MARGIN + 70f

    private fun descColWidth(): Float = CONTENT_RIGHT - descColX() - AMOUNT_COL_WIDTH - 10f

    private fun formatRange(start: LocalDate, end: LocalDate): String {
        val formatter = DateTimeFormatter.ofPattern("d MMM yyyy")
        return "${start.format(formatter)} – ${end.format(formatter)}"
    }

    private fun textPaint(size: Float, color: Int, bold: Boolean = false): Paint = Paint().apply {
        isAntiAlias = true
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun fillPaint(color: Int): Paint = Paint().apply {
        isAntiAlias = true
        this.color = color
        style = Paint.Style.FILL
    }

    private fun linePaint(): Paint = Paint().apply {
        color = Color.LTGRAY
        strokeWidth = 1f
    }

    /** Shortens [text] to fit [maxWidth], measuring rather than guessing a character count. */
    private fun ellipsize(paint: Paint, text: String, maxWidth: Float): String {
        if (maxWidth <= 0f || paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + ELLIPSIS) > maxWidth) {
            end--
        }
        return text.substring(0, end) + ELLIPSIS
    }

    // ---- Filesystem ------------------------------------------------------------------------------

    private fun exportsDir(): File = File(context.filesDir, "exports").apply { mkdirs() }

    private fun uriFor(file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun timestamp(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

    private companion object {
        const val TAG = "StatementExporter"
        const val MIME_PDF = "application/pdf"

        // A4 at 72dpi.
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val MARGIN = 40f
        val CONTENT_RIGHT = PAGE_WIDTH - MARGIN
        const val ROW_HEIGHT = 18f
        const val AMOUNT_COL_WIDTH = 90f

        // Leaves room for the two-line footer that is drawn on every page.
        val BOTTOM_LIMIT = PAGE_HEIGHT - 50f

        val BRAND_COLOR = Color.parseColor("#3A3A8C")
        val SHADE_COLOR = Color.rgb(245, 245, 248)

        const val ELLIPSIS = "…"

        /** Locale-neutral placeholder for a row with neither a merchant nor a note. */
        const val NO_DESCRIPTION = "—"
    }
}
