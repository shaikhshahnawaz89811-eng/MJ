package com.mj.assistant.sketch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Renders a SketchSpec (see SketchModels.kt) to a Bitmap using plain android.graphics
 * Canvas/Paint/Path — the same built-in, zero-dependency drawing surface DocumentScanner
 * already uses for the scan PDF, not a new rendering or vector-graphics library. This is
 * a simple geometric line-sketch renderer, not an illustration engine: circles, lines,
 * rectangles, polygons and text labels are all it draws, since that is what a small
 * on-device model has a realistic chance of describing correctly as JSON — a photoreal
 * or richly-shaded drawing is not what this produces.
 */
object SketchRenderer {

    private const val SKETCH_PDF_RETENTION_DAYS = 30L

    fun render(spec: SketchSpec): Bitmap {
        val bitmap = Bitmap.createBitmap(spec.width.coerceAtLeast(1), spec.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        for (shape in spec.shapes) {
            paint.color = runCatching { Color.parseColor(shape.color) }.getOrDefault(Color.DKGRAY)
            paint.style = if (shape.filled) Paint.Style.FILL else Paint.Style.STROKE
            paint.strokeWidth = shape.strokeWidth.coerceAtLeast(1f)

            when (shape.type) {
                "circle" -> if (shape.r > 0f) canvas.drawCircle(shape.x, shape.y, shape.r, paint)
                "rect" -> if (shape.w > 0f && shape.h > 0f) canvas.drawRect(shape.x, shape.y, shape.x + shape.w, shape.y + shape.h, paint)
                "line" -> canvas.drawLine(shape.x, shape.y, shape.x2, shape.y2, paint)
                "polygon" -> if (shape.points.size >= 2) {
                    val path = Path().apply {
                        moveTo(shape.points[0].first, shape.points[0].second)
                        shape.points.drop(1).forEach { lineTo(it.first, it.second) }
                        close()
                    }
                    canvas.drawPath(path, paint)
                }
                "text" -> if (shape.text.isNotBlank()) {
                    val textPaint = Paint(paint).apply { style = Paint.Style.FILL; textSize = 20f }
                    canvas.drawText(shape.text, shape.x, shape.y, textPaint)
                }
                else -> Unit // unknown shape type from the model — skip, don't fail the whole sketch
            }
        }
        return bitmap
    }

    /** Saves a rendered sketch as a PNG for inline display in chat — the durable form
     * a sketch is kept in until (if ever) the user asks for it as a PDF. PNG rather
     * than JPEG since this is flat line art (a few colors, sharp edges), where PNG's
     * lossless compression is both smaller and artifact-free compared to JPEG here. */
    suspend fun savePng(context: Context, bitmap: Bitmap): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val outDir = File(context.filesDir, "sketch_images").apply { mkdirs() }
            val out = File(outDir, "sketch_${System.currentTimeMillis()}.png")
            FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out
        }
    }

    /** Reloads a previously-saved sketch PNG — used when a follow-up message asks for
     * the PDF of a sketch that was only shown inline earlier. */
    suspend fun loadPng(path: String): Result<Bitmap> = withContext(Dispatchers.IO) {
        runCatching {
            android.graphics.BitmapFactory.decodeFile(path) ?: error("could not decode sketch PNG at $path")
        }
    }

    /** Embeds a rendered sketch as a single-page PDF, scaled to fit an A4-ish page —
     * same page geometry as DocumentScanner's scan PDF, kept as its own function rather
     * than sharing code with an unrelated feature. */
    suspend fun buildSketchPdf(context: Context, image: Bitmap, title: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val pageWidth = 595
                val pageHeight = 842
                val margin = 40f
                val doc = PdfDocument()
                try {
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
                    val maxW = pageWidth - 2 * margin
                    val maxH = pageHeight - 2 * margin - 30f // leave room for the title line
                    val scale = minOf(maxW / image.width, maxH / image.height, 1f)
                    val drawW = image.width * scale
                    val drawH = image.height * scale
                    val matrix = Matrix().apply {
                        setScale(scale, scale)
                        postTranslate((pageWidth - drawW) / 2f, margin + 30f)
                    }
                    if (title.isNotBlank()) {
                        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f; color = Color.DKGRAY }
                        page.canvas.drawText(title, margin, margin, titlePaint)
                    }
                    page.canvas.drawBitmap(image, matrix, Paint(Paint.ANTI_ALIAS_FLAG))
                    doc.finishPage(page)

                    val outDir = File(context.filesDir, "sketch_pdfs").apply { mkdirs() }
                    val out = File(outDir, "sketch_${System.currentTimeMillis()}.pdf")
                    FileOutputStream(out).use { doc.writeTo(it) }
                    out
                } finally {
                    doc.close()
                }
            }
        }

    /** Same auto-cleanup policy as DocumentScanner.cleanupOldScans, kept as its own copy
     * for this feature's own folders rather than reaching into an unrelated file. Covers
     * both sketch_images (inline PNGs) and sketch_pdfs (only ever created if the user
     * actually asks for a PDF), so neither grows forever. */
    suspend fun cleanupOldSketches(context: Context) {
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - SKETCH_PDF_RETENTION_DAYS * 24 * 60 * 60 * 1000
            for (folder in listOf("sketch_pdfs", "sketch_images")) {
                runCatching {
                    File(context.filesDir, folder)
                        .listFiles()
                        ?.filter { it.isFile && it.lastModified() < cutoff }
                        ?.forEach { it.delete() }
                }
            }
        }
    }
}
