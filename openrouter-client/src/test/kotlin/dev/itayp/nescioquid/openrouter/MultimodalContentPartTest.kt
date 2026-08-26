package dev.itayp.nescioquid.openrouter

import tools.jackson.core.type.TypeReference
import tools.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MultimodalContentPartTest {

    private val objectMapper = jacksonObjectMapper()

    private fun toMap(message: ChatMessage): Map<String, Any?> =
        objectMapper.readValue(
            objectMapper.writeValueAsString(message),
            object : TypeReference<Map<String, Any?>>() {},
        )

    @Suppress("UNCHECKED_CAST")
    private fun partsOf(message: ChatMessage): List<Map<String, Any?>> =
        toMap(message)["content"] as List<Map<String, Any?>>

    @Test
    fun `image content part serializes as image_url with a plain URL`() {
        val message = ChatMessage.withAttachments(
            role = "user",
            text = "what is in this picture?",
            attachments = listOf(ContentPart.ImageUrl.of("https://example.com/cat.png")),
        )

        val parts = partsOf(message)
        assertEquals(2, parts.size)
        assertEquals(mapOf("type" to "text", "text" to "what is in this picture?"), parts[0])
        assertEquals("image_url", parts[1]["type"])
        assertEquals(mapOf("url" to "https://example.com/cat.png"), parts[1]["image_url"])
    }

    @Test
    fun `image content part built from bytes serializes as a base64 data URL`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val part = ContentPart.ImageUrl.ofBytes(bytes, mediaType = "image/jpeg")

        val message = ChatMessage.withAttachments(role = "user", attachments = listOf(part))
        val expectedUrl = "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(bytes)}"

        assertEquals(1, partsOf(message).size, "no text part when text is omitted")
        assertEquals(mapOf("url" to expectedUrl), partsOf(message).single()["image_url"])
    }

    @Test
    fun `file content part serializes as file with filename and file_data`() {
        val part = ContentPart.File.of("report.pdf", "https://example.com/report.pdf")

        val message = ChatMessage.withAttachments(role = "user", text = "summarize this", attachments = listOf(part))
        val filePart = partsOf(message)[1]

        assertEquals("file", filePart["type"])
        assertEquals(
            mapOf("filename" to "report.pdf", "file_data" to "https://example.com/report.pdf"),
            filePart["file"],
        )
    }

    @Test
    fun `file content part built from bytes serializes as a base64 data URL`() {
        val bytes = "not a real pdf".toByteArray()
        val part = ContentPart.File.ofBytes("doc.pdf", bytes)
        val expectedUrl = "data:application/pdf;base64,${Base64.getEncoder().encodeToString(bytes)}"

        val message = ChatMessage.withAttachments(role = "user", attachments = listOf(part))

        assertEquals(
            mapOf("filename" to "doc.pdf", "file_data" to expectedUrl),
            partsOf(message).single()["file"],
        )
    }

    @Test
    fun `audio content part serializes as input_audio with base64 data and format`() {
        val bytes = byteArrayOf(5, 6, 7)
        val part = ContentPart.InputAudio.ofBytes(bytes, format = "wav")

        val message = ChatMessage.withAttachments(role = "user", attachments = listOf(part))
        val audioPart = partsOf(message).single()

        assertEquals("input_audio", audioPart["type"])
        assertEquals(
            mapOf("data" to Base64.getEncoder().encodeToString(bytes), "format" to "wav"),
            audioPart["input_audio"],
        )
    }

    @Test
    fun `withAttachments omits the text part when text is null and combines multiple attachments`() {
        val message = ChatMessage.withAttachments(
            role = "user",
            attachments = listOf(
                ContentPart.ImageUrl.of("https://example.com/a.png"),
                ContentPart.ImageUrl.of("https://example.com/b.png"),
            ),
        )

        val parts = partsOf(message)
        assertEquals(2, parts.size)
        assertEquals("https://example.com/a.png", (parts[0]["image_url"] as Map<*, *>)["url"])
        assertEquals("https://example.com/b.png", (parts[1]["image_url"] as Map<*, *>)["url"])
    }

    @Test
    fun `chat request plugins field is omitted by default and serializes pdf engine selection`() {
        val bare = ChatRequest(model = "m", messages = emptyList())
        val bareMap = objectMapper.readValue(
            objectMapper.writeValueAsString(bare),
            object : TypeReference<Map<String, Any?>>() {},
        )
        assertNull(bareMap["plugins"])

        val withPlugin = bare.copy(plugins = listOf(PluginConfig.pdfEngine(PdfEngine.MISTRAL_OCR)))
        val pluginMap = objectMapper.readValue(
            objectMapper.writeValueAsString(withPlugin),
            object : TypeReference<Map<String, Any?>>() {},
        )
        @Suppress("UNCHECKED_CAST")
        val plugins = pluginMap["plugins"] as List<Map<String, Any?>>
        assertEquals(
            listOf(mapOf("id" to "file-parser", "pdf" to mapOf("engine" to "mistral-ocr"))),
            plugins,
        )
    }
}
