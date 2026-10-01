package dev.itayp.nescioquid.telegram

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestOperations
import org.springframework.web.client.RestTemplate

/**
 * Beans [TelegramOidcService] needs: a decoder that validates Telegram id_tokens (signature,
 * issuer, expiry, and audience once [TelegramOidcProperties.clientId] is set) and a
 * `RestClient` for the token exchange. Both beans are named — `telegramJwtDecoder` /
 * `telegramTokenRestClient` — precisely so they don't collide with a consumer's own `JwtDecoder`
 * or `RestClient` beans (e.g. a resource-server decoder validating the app's own tokens).
 */
@Configuration
class TelegramOidcConfiguration {

    @Bean
    fun telegramJwtDecoder(properties: TelegramOidcProperties): JwtDecoder =
        buildJwtDecoder(properties, jwkSetRestTemplate())

    internal fun buildJwtDecoder(properties: TelegramOidcProperties, jwkSetClient: RestOperations): JwtDecoder {
        val decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri)
            .restOperations(jwkSetClient)
            .build()
        val validators = buildList {
            add(JwtValidators.createDefaultWithIssuer(properties.issuer))
            // Only enforce the audience when configured, so a dev/test setup without a Client ID
            // can still boot.
            if (properties.clientId.isNotBlank()) {
                add(JwtClaimValidator<List<String>>(JwtClaimNames.AUD) { aud ->
                    aud.contains(properties.clientId)
                })
            }
        }
        decoder.setJwtValidator(DelegatingOAuth2TokenValidator(validators))
        return decoder
    }

    /**
     * Client for the Telegram token exchange. Built standalone via [RestClient.builder] rather than
     * an autoconfigured `RestClient.Builder` (not guaranteed to be present in every consumer), but
     * still uses the framework's default message converters for the token JSON. Decodes gzip
     * responses, like the JWKS fetch; see [GzipDecodingInterceptor].
     */
    @Bean
    fun telegramTokenRestClient(): RestClient =
        RestClient.builder().requestInterceptor(GzipDecodingInterceptor).build()

    /** The JWKS fetch; Telegram serves the key set gzip-compressed (see [GzipDecodingInterceptor]). */
    internal fun jwkSetRestTemplate(): RestTemplate =
        RestTemplate().apply { interceptors.add(GzipDecodingInterceptor) }
}
