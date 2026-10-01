package dev.itayp.nescioquid.telegram

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Date
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals

/** Telegram's OAuth endpoints answer gzip-compressed whether or not the client asked for it. */
class GzipDecodingTest {

    private val props = TelegramOidcProperties(clientId = "123456789", clientSecret = "s3cr3t")
    private val configuration = TelegramOidcConfiguration()
    private val key = RSAKeyGenerator(2048).keyID("k1").generate()

    private fun gzip(text: String): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()

    private fun gzipHeaders() = HttpHeaders().apply { set(HttpHeaders.CONTENT_ENCODING, "gzip") }

    private fun idToken(): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .issuer(props.issuer)
            .audience(props.clientId)
            .claim("id", 987654321L)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), claims)
            .apply { sign(RSASSASigner(key)) }
            .serialize()
    }

    @Test
    fun `decoder verifies a token against a gzip-compressed JWKS`() {
        val restTemplate = configuration.jwkSetRestTemplate()
        val server = MockRestServiceServer.bindTo(restTemplate).build()
        val jwks = JWKSet(key.toPublicJWK()).toString()
        server.expect(requestTo(props.jwkSetUri))
            .andRespond(withSuccess(gzip(jwks), MediaType.APPLICATION_JSON).headers(gzipHeaders()))

        val jwt = configuration.buildJwtDecoder(props, restTemplate).decode(idToken())

        assertEquals(987654321L, jwt.getClaim<Long>("id"))
        server.verify()
    }

    @Test
    fun `token client decodes a gzip-compressed response`() {
        val builder = RestClient.builder().requestInterceptor(GzipDecodingInterceptor)
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo(props.tokenUri))
            .andRespond(withSuccess(gzip("""{"id_token":"abc"}"""), MediaType.APPLICATION_JSON).headers(gzipHeaders()))

        val body = builder.build().post().uri(props.tokenUri).retrieve().body(Map::class.java)

        assertEquals("abc", body?.get("id_token"))
    }

    @Test
    fun `a body labelled gzip but already decoded passes through`() {
        val builder = RestClient.builder().requestInterceptor(GzipDecodingInterceptor)
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo(props.tokenUri))
            .andRespond(withSuccess("""{"id_token":"abc"}""", MediaType.APPLICATION_JSON).headers(gzipHeaders()))

        val body = builder.build().post().uri(props.tokenUri).retrieve().body(Map::class.java)

        assertEquals("abc", body?.get("id_token"))
    }

    @Test
    fun `an empty body labelled gzip passes through`() {
        val builder = RestClient.builder().requestInterceptor(GzipDecodingInterceptor)
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo(props.tokenUri))
            .andRespond(withSuccess().headers(gzipHeaders()))

        val body = builder.build().post().uri(props.tokenUri).retrieve().body(String::class.java)

        assertEquals(null, body)
    }
}
