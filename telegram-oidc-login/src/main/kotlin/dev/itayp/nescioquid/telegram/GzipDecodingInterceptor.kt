package dev.itayp.nescioquid.telegram

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpRequest
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Decompresses `Content-Encoding: gzip` responses. Telegram's OAuth endpoints gzip JSON responses
 * even when the request never sent `Accept-Encoding`, and Spring's default request factories hand
 * the compressed bytes straight to the parser (for the JWKS: "Malformed Jwk set").
 *
 * Only decompresses when the body actually starts with the gzip magic bytes, so a request factory
 * that already decoded the body but left the header in place isn't decoded twice.
 */
internal object GzipDecodingInterceptor : ClientHttpRequestInterceptor {

    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        val response = execution.execute(request, body)
        val encoding = response.headers.getFirst(HttpHeaders.CONTENT_ENCODING)
        return if (encoding != null && encoding.trim().equals("gzip", ignoreCase = true)) {
            GunzippingResponse(response)
        } else {
            response
        }
    }

    private class GunzippingResponse(private val delegate: ClientHttpResponse) : ClientHttpResponse {

        private val decodedBody: InputStream by lazy { decode(delegate.body) }

        // The body no longer matches either header once decoded.
        private val decodedHeaders: HttpHeaders by lazy {
            HttpHeaders.copyOf(delegate.headers).apply {
                remove(HttpHeaders.CONTENT_ENCODING)
                remove(HttpHeaders.CONTENT_LENGTH)
            }
        }

        override fun getBody(): InputStream = decodedBody

        override fun getHeaders(): HttpHeaders = decodedHeaders

        override fun getStatusCode(): HttpStatusCode = delegate.statusCode

        override fun getStatusText(): String = delegate.statusText

        override fun close() = delegate.close()

        private fun decode(raw: InputStream): InputStream {
            val buffered = BufferedInputStream(raw)
            buffered.mark(2)
            val first = buffered.read()
            val second = buffered.read()
            buffered.reset()
            return if (first == 0x1f && second == 0x8b) GZIPInputStream(buffered) else buffered
        }
    }
}
