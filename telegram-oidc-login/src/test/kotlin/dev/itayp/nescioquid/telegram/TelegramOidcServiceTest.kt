package dev.itayp.nescioquid.telegram

import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TelegramOidcServiceTest {

    private val props = TelegramOidcProperties(
        clientId = "123456789",
        clientSecret = "s3cr3t",
        authorizationUri = "https://oauth.example/auth",
        tokenUri = "https://oauth.example/token",
    )
    private val decoder: JwtDecoder = mock()

    private fun newService(restClient: RestClient = RestClient.create(), properties: TelegramOidcProperties = props) =
        TelegramOidcService(properties, decoder, restClient)

    private fun jwt(builder: Jwt.Builder.() -> Unit): Jwt =
        Jwt.withTokenValue("token").header("alg", "RS256").apply(builder).build()

    @Test
    fun `isConfigured requires client id and secret`() {
        assertTrue(newService().isConfigured())
        assertFalse(newService(properties = props.copy(clientSecret = "")).isConfigured())
    }

    @Test
    fun `buildAuthorizationRequest produces a PKCE authorize url with fresh state`() {
        val service = newService()
        val req1 = service.buildAuthorizationRequest("https://app.example/cb")
        val req2 = service.buildAuthorizationRequest("https://app.example/cb")

        val url = req1.authorizationUrl
        assertTrue(url.startsWith("https://oauth.example/auth?"))
        assertTrue(url.contains("client_id=123456789"))
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fapp.example%2Fcb"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("code_challenge="))
        assertTrue(url.contains("state="))
        assertTrue(req1.state.isNotBlank())
        assertTrue(req1.codeVerifier.isNotBlank())
        // Each request is unguessable and unique.
        assertNotEquals(req1.state, req2.state)
        assertNotEquals(req1.codeVerifier, req2.codeVerifier)
    }

    @Test
    fun `verifyIdToken maps telegram claims`() {
        whenever(decoder.decode("good")).thenReturn(jwt {
            claim("id", 987654321L)
            claim("preferred_username", "alice")
            claim("name", "Alice")
            claim("picture", "https://cdn/p.png")
            issuedAt(Instant.parse("2026-04-21T12:00:00Z"))
        })

        val data = newService().verifyIdToken("good")

        assertEquals(987654321L, data.telegramId)
        assertEquals("alice", data.username)
        assertEquals("Alice", data.firstName)
        assertEquals("https://cdn/p.png", data.photoUrl)
        assertEquals(Instant.parse("2026-04-21T12:00:00Z"), data.authDate)
    }

    @Test
    fun `verifyIdToken accepts a string id claim`() {
        whenever(decoder.decode("strid")).thenReturn(jwt { claim("id", "555") })
        assertEquals(555L, newService().verifyIdToken("strid").telegramId)
    }

    @Test
    fun `verifyIdToken rejects a token missing the id claim`() {
        whenever(decoder.decode("noid")).thenReturn(jwt { claim("name", "Nobody") })
        val exception = assertFailsWith<TelegramAuthException> { newService().verifyIdToken("noid") }
        assertTrue(exception.message.orEmpty().contains("id"))
    }

    @Test
    fun `verifyIdToken wraps decoder failures`() {
        whenever(decoder.decode("bad")).thenThrow(JwtException("bad signature"))
        val exception = assertFailsWith<TelegramAuthException> { newService().verifyIdToken("bad") }
        assertTrue(exception.message.orEmpty().contains("Invalid id_token"))
    }

    @Test
    fun `completeAuthorization exchanges the code then validates the returned id_token`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://oauth.example/token"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"id_token":"the-jwt"}""", MediaType.APPLICATION_JSON))
        whenever(decoder.decode("the-jwt")).thenReturn(jwt { claim("id", 42L) })

        val data = newService(builder.build())
            .completeAuthorization("auth-code", "verifier", "https://app.example/cb")

        assertEquals(42L, data.telegramId)
        server.verify()
    }

    @Test
    fun `completeAuthorization fails when the token response has no id_token`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://oauth.example/token"))
            .andRespond(withSuccess("""{"access_token":"x"}""", MediaType.APPLICATION_JSON))

        val exception = assertFailsWith<TelegramAuthException> {
            newService(builder.build()).completeAuthorization("c", "v", "https://app.example/cb")
        }
        assertTrue(exception.message.orEmpty().contains("id_token"))
    }

    @Test
    fun `completeAuthorization wraps token endpoint errors`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://oauth.example/token")).andRespond(withServerError())

        assertFailsWith<TelegramAuthException> {
            newService(builder.build()).completeAuthorization("c", "v", "https://app.example/cb")
        }
    }
}
