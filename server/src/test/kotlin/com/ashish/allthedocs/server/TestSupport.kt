package com.ashish.allthedocs.server

import com.ashish.allthedocs.server.llm.LlmProvider
import com.ashish.allthedocs.server.llm.LlmRequest
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class FakeLlmProvider(private val handler: (LlmRequest) -> String) : LlmProvider {
    val requests = mutableListOf<LlmRequest>()

    override suspend fun complete(request: LlmRequest): String {
        requests += request
        return handler(request)
    }
}

object TestFiles {
    fun png(width: Int = 40, height: Int = 30): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color.BLUE
        graphics.fillRect(0, 0, width / 2, height)
        graphics.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    fun pdf(text: String, pages: Int = 1): ByteArray = PDDocument().use { document ->
        repeat(pages) { index ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 14f)
                stream.newLineAtOffset(72f, 700f)
                stream.showText("$text page ${index + 1}")
                stream.endText()
            }
        }
        ByteArrayOutputStream().also { document.save(it) }.toByteArray()
    }
}
