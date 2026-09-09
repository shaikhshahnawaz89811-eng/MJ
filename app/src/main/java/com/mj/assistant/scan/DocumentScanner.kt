package com.mj.assistant.scan

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Document Scan -> OCR -> PDF pipeline (see MJ_OCR_PDF_SCAN_PLAN.pdf).
 *
 * Every step here is on-device and free, matching Phase 7's own rule
 * (ROADMAP_PROGRESS.md: "what's honestly buildable without a paid API key or
 * a risky permission"):
 *  - OCR: ML Kit's BUNDLED Text Recognition (com.google.mlkit:text-recognition).
 *    The model ships inside the APK and runs fully offline. This is
 *    deliberately NOT com.google.android.gms:play-services-mlkit-text-recognition
 *    (the unbundled variant, which needs Play services + a first-run download).
 *  - Understanding the text: reuses whichever local Qwen AIEngine slot the
 *    caller already has active for chat. No second model, no cloud LLM key —
 *    this file never touches that step itself, it only builds the prompt
 *    (see [cleanupPrompt]); the actual call happens where the caller already
 *    has the active AIEngine.
 *  - PDF: android.graphics.pdf.PdfDocument, built into the Android SDK — zero
 *    extra dependency, no pdf-lib/iText/PDFBox.
 * Nothing in this file makes a network call.
 *
 * Deliberately kept free of chat-facing strings (no Hinglish reply text) —
 * this is the mechanical layer only. Callers decide what to say, same as
 * DeviceCommands.kt keeps its own responses separate from MainActivity's UI.
 */
object DocumentScanner {

    private const val PAGE_WIDTH = 595   // A4 @ 72dpi, in points
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f
    private const val TEXT_SIZE = 12f
    private const val LINE_HEIGHT = 16f

    // Memory safety: a modern phone camera photo is commonly 12-108MP. Decoded at full
    // resolution as ARGB_8888 (4 bytes/pixel), a single 12MP photo alone is already
    // ~48MB, a 48MP photo ~192MB — on a device where a multi-hundred-MB-to-multi-GB
    // Qwen model may already be loaded for chat, that is a real OutOfMemoryError / hang
    // risk, not a theoretical one. This app has no scanning use case that needs more
    // than this: ML Kit's own guidance is that OCR accuracy stops improving once a
    // character is bigger than ~16-24px tall, and this cap is comfortably above what
    // that needs even for a full A4 page of normal body text (a 1600px-long-side photo
    // of an A4 page still gives ~13-16px-tall characters for typical 10-12pt print).
    // Long-side cap in pixels; every decode path below is downsampled to this, so the
    // in-memory bitmap is bounded regardless of the source photo's real resolution.
    private const val MAX_IMAGE_DIMENSION = 1600

    // Auto-cleanup: how long a generated scan PDF is kept before a later app launch
    // deletes it automatically. This app has no per-message delete or "keep this one"
    // action to hook cleanup into instead, so a simple age-based cap is what keeps
    // scan_pdfs/ from growing forever without ever touching something just made.
    private const val SCAN_PDF_RETENTION_DAYS = 30L

    /** A destination for TakePicture(): the app-private file it will write to, and the
     * content:// URI (via FileProvider) that the camera app is actually granted. */
    data class CaptureTarget(val file: File, val uri: Uri)

    private fun authority(context: Context) = "${context.packageName}.fileprovider"

    fun uriForFile(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    /** Where TakePicture() should write the full-resolution photo before OCR reads it
     * back. Lives under cacheDir since it's disposable once the PDF is built — the
     * caller deletes [CaptureTarget.file] itself once the scan finishes. */
    fun newCaptureTarget(context: Context): CaptureTarget {
        val dir = File(context.cacheDir, "scan_captures").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        return CaptureTarget(file, uriForFile(context, file))
    }

    /**
     * Deletes scan PDFs older than [SCAN_PDF_RETENTION_DAYS], plus any leftover
     * scan_captures temp file. A completed scan already deletes its own capture file
     * (see MainActivity's runScan), so anything still sitting in that folder is one
     * whose cleanup never ran — e.g. the app got killed mid-scan — and is safe to
     * remove regardless of age. Meant to be called once per app launch; safe to call
     * more often too, since it's a no-op when there's nothing old enough to remove.
     */
    suspend fun cleanupOldScans(context: Context) {
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - SCAN_PDF_RETENTION_DAYS * 24 * 60 * 60 * 1000
            runCatching {
                File(context.filesDir, "scan_pdfs")
                    .listFiles()
                    ?.filter { it.isFile && it.lastModified() < cutoff }
                    ?.forEach { it.delete() }
            }
            runCatching {
                File(context.cacheDir, "scan_captures").listFiles()?.forEach { it.delete() }
            }
        }
    }

    /**
     * Loads a picked/captured image URI into a Bitmap, downsampled to [MAX_IMAGE_DIMENSION]
     * on the long side. Never throws — failures come back as Result, the same convention
     * QwenModelEngine's import/load/unload/delete already use.
     *
     * The downsampling is done AS PART OF the decode on both paths below (ImageDecoder's
     * setTargetSize, BitmapFactory's inSampleSize) — never decode-full-size-then-shrink,
     * since that would still allocate the full, potentially huge bitmap for a moment
     * (the exact hang/OOM risk this exists to avoid) before throwing it away.
     */
    suspend fun loadBitmap(context: Context, uri: Uri): Result<Bitmap> = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // ImageDecoder applies Exif rotation itself for JPEG/HEIC (what the
                // camera and the gallery picker both hand back), so no manual
                // rotation is needed on this path.
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    // PdfDocument's page canvas is a software canvas — a HARDWARE
                    // bitmap would crash it with IllegalArgumentException.
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val (srcW, srcH) = info.size.width to info.size.height
                    val longSide = maxOf(srcW, srcH)
                    if (longSide > MAX_IMAGE_DIMENSION) {
                        val scale = MAX_IMAGE_DIMENSION.toFloat() / longSide
                        decoder.setTargetSize(
                            (srcW * scale).toInt().coerceAtLeast(1),
                            (srcH * scale).toInt().coerceAtLeast(1)
                        )
                    }
                }
            } else {
                // The legacy path (API 26/27 only, since minSdk is 26) never applies
                // Exif rotation, so it's corrected by hand below.
                val legacy = decodeDownsampledLegacy(context, uri)
                rotateForExif(context, uri, legacy)
            }
        }
    }

    /** BitmapFactory has no equivalent of ImageDecoder's setTargetSize, so this does the
     * classic two-pass decode by hand: read only the bounds first (no pixel allocation),
     * compute a power-of-2 inSampleSize from those bounds, then decode for real at that
     * sample size. A content:// stream can't be rewound, so bounds and the real decode
     * each need their own fresh InputStream. */
    @Suppress("DEPRECATION")
    private fun decodeDownsampledLegacy(context: Context, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sampleSize = 1
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (longSide / sampleSize > MAX_IMAGE_DIMENSION) sampleSize *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Bitmap decode returned null")
    }

    private fun rotateForExif(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val degrees = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        }.getOrDefault(0)
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * On-device OCR via ML Kit's bundled TextRecognizer. A blank result comes back as
     * `Result.success("")` — NOT a failure — so the caller can give the honest
     * "couldn't find readable text" reply the plan calls for, instead of a generic
     * error message. This mirrors MJPersonality's own honesty rule ("never claim an
     * action happened unless the result says it happened").
     */
    suspend fun recognizeText(bitmap: Bitmap): Result<String> = runCatching {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            suspendCancellableCoroutine { cont ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { visionText -> cont.resume(visionText.text) }
                    .addOnFailureListener { e -> cont.resumeWithException(e) }
            }
        } finally {
            // Google's own docs for TextRecognizer: "should be closed to release its
            // resources." This is one-shot usage (a picked/captured photo), not a
            // continuous camera feed, so a fresh client per scan — closed right after —
            // is simpler and safer than holding one open for the app's whole lifetime.
            recognizer.close()
        }
    }

    /** The cleanup instruction the plan specifies, kept in one place. Caller passes the
     * result to whichever AIEngine slot is already active — see MainActivity's runScan. */
    fun cleanupPrompt(ocrText: String): String =
        "Clean up and lightly format this scanned text. Keep it faithful, don't add facts that aren't there:\n\n$ocrText"

    /**
     * Builds a PDF: page 1 is the scanned image scaled to fit the page, page(s) 2+ are
     * the cleaned OCR text — word-wrapped and paginated for real. (The plan's own sketch
     * skipped this on purpose: "left out here since this is a plan, not the final file.")
     */
    suspend fun buildScanPdf(context: Context, image: Bitmap, cleanedText: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val doc = PdfDocument()
                try {
                    val maxW = PAGE_WIDTH - 2 * MARGIN
                    val maxH = PAGE_HEIGHT - 2 * MARGIN

                    val imgPage = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create())
                    val scale = minOf(maxW / image.width, maxH / image.height, 1f)
                    val drawW = image.width * scale
                    val drawH = image.height * scale
                    val matrix = Matrix().apply {
                        setScale(scale, scale)
                        postTranslate((PAGE_WIDTH - drawW) / 2f, (PAGE_HEIGHT - drawH) / 2f)
                    }
                    imgPage.canvas.drawBitmap(image, matrix, Paint(Paint.ANTI_ALIAS_FLAG))
                    doc.finishPage(imgPage)

                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = TEXT_SIZE }
                    val wrapped = wrapText(cleanedText, paint, maxW)
                    val linesPerPage = (maxH / LINE_HEIGHT).toInt().coerceAtLeast(1)
                    val pages = if (wrapped.isEmpty()) listOf(emptyList()) else wrapped.chunked(linesPerPage)
                    pages.forEachIndexed { idx, pageLines ->
                        val textPage = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, idx + 2).create())
                        pageLines.forEachIndexed { i, line ->
                            textPage.canvas.drawText(line, MARGIN, MARGIN + (i + 1) * LINE_HEIGHT, paint)
                        }
                        doc.finishPage(textPage)
                    }

                    val outDir = File(context.filesDir, "scan_pdfs").apply { mkdirs() }
                    val out = File(outDir, "scan_${System.currentTimeMillis()}.pdf")
                    FileOutputStream(out).use { doc.writeTo(it) }
                    out
                } finally {
                    doc.close()
                }
            }
        }

    /** Greedy word-wrap: breaks text into lines no wider than maxWidth (preferring a
     * space so words aren't split mid-word), respecting existing newlines as-is. */
    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        text.lineSequence().forEach { rawLine ->
            if (rawLine.isEmpty()) {
                lines += ""
                return@forEach
            }
            var remaining = rawLine
            while (remaining.isNotEmpty()) {
                var count = paint.breakText(remaining, true, maxWidth, null)
                if (count <= 0) count = 1 // always make progress, even for one very wide glyph
                var breakAt = count
                if (breakAt < remaining.length && remaining[breakAt] != ' ') {
                    val lastSpace = remaining.lastIndexOf(' ', breakAt - 1)
                    if (lastSpace > 0) breakAt = lastSpace
                }
                lines += remaining.substring(0, breakAt).trimEnd()
                remaining = remaining.substring(breakAt).trimStart()
            }
        }
        return lines
    }

    /** Opens a generated PDF in whatever viewer the device has, via a FileProvider
     * content:// URI. Returns whether an activity was actually found to handle it. */
    fun openPdf(context: Context, path: String): Boolean = runCatching {
        val uri = uriForFile(context, File(path))
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
