package com.ashish.allthedocs.server.verify

import com.ashish.allthedocs.server.InvalidInputException
import com.ashish.allthedocs.server.UnsupportedFileTypeException
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream

enum class SupportedFileType(val mimeType: String) {
    PDF("application/pdf"),
    JPEG("image/jpeg"),
    PNG("image/png"),
}

class RenderedDocument(val text: String?, val pageImages: List<ByteArray>)

object DocumentRenderer {
    const val MAX_PAGES = 3
    const val MAX_DIMENSION = 1600
    const val MAX_TEXT_LENGTH = 6000
    private const val RENDER_DPI = 110f

    init {
        System.setProperty("java.awt.headless", "true")
        ImageIO.setUseCache(false)
    }

    fun detectType(bytes: ByteArray): SupportedFileType? = when {
        bytes.startsWith(byteArrayOf(0x25, 0x50, 0x44, 0x46)) -> SupportedFileType.PDF
        bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) -> SupportedFileType.JPEG
        bytes.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) -> SupportedFileType.PNG
        else -> null
    }

    fun render(bytes: ByteArray): RenderedDocument =
        when (detectType(bytes)) {
            SupportedFileType.PDF -> renderPdf(bytes)
            SupportedFileType.JPEG, SupportedFileType.PNG -> RenderedDocument(null, listOf(renderImage(bytes)))
            null -> throw UnsupportedFileTypeException("Only PDF, JPEG and PNG files are supported")
        }

    private fun renderPdf(bytes: ByteArray): RenderedDocument {
        try {
            Loader.loadPDF(bytes).use { document ->
                val pageCount = document.numberOfPages
                if (pageCount == 0) throw InvalidInputException("The PDF has no pages")
                val lastPage = minOf(pageCount, MAX_PAGES)
                val text = PDFTextStripper().apply {
                    startPage = 1
                    endPage = lastPage
                }.getText(document).trim().take(MAX_TEXT_LENGTH).ifBlank { null }
                val renderer = PDFRenderer(document)
                val images = (0 until lastPage).map { index ->
                    toJpeg(scaleDown(renderer.renderImageWithDPI(index, RENDER_DPI, ImageType.RGB)))
                }
                return RenderedDocument(text, images)
            }
        } catch (e: InvalidPasswordException) {
            throw InvalidInputException("Password-protected PDFs are not supported. Remove the password and try again.")
        } catch (e: IOException) {
            throw InvalidInputException("Could not read the PDF")
        }
    }

    private fun renderImage(bytes: ByteArray): ByteArray {
        val image = try {
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (e: IOException) {
            null
        } ?: throw InvalidInputException("Could not read the image")
        return toJpeg(scaleDown(image))
    }

    private fun scaleDown(source: BufferedImage): BufferedImage {
        val largest = maxOf(source.width, source.height)
        val scale = if (largest > MAX_DIMENSION) MAX_DIMENSION.toDouble() / largest else 1.0
        val width = maxOf(1, (source.width * scale).toInt())
        val height = maxOf(1, (source.height * scale).toInt())
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    private fun toJpeg(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val output = ByteArrayOutputStream()
        try {
            MemoryCacheImageOutputStream(output).use { stream ->
                writer.output = stream
                val params = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = 0.85f
                }
                writer.write(null, IIOImage(image, null, null), params)
            }
        } finally {
            writer.dispose()
        }
        return output.toByteArray()
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
