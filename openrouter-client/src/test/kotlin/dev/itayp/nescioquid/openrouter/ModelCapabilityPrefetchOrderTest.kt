package dev.itayp.nescioquid.openrouter

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Duration
import java.util.function.Supplier
import kotlin.test.assertEquals

class ModelCapabilityPrefetchOrderTest {

    /** A consumer's startup check: it reads the capabilities on the same event the prefetch runs on. */
    class StartupProbe(private val capabilityService: ModelCapabilityService) {
        var seen: ModelCapabilities? = null

        @EventListener(ApplicationReadyEvent::class)
        fun onReady() {
            seen = capabilityService.get(MODEL)
        }
    }

    @Test
    fun `the prefetch runs ahead of the consumer's own ApplicationReadyEvent listeners`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://openrouter.ai/api/v1/model/$MODEL"))
            .andRespond(withSuccess("""{"data":{"architecture":{"input_modalities":["audio"],"output_modalities":["transcription"]}}}""", MediaType.APPLICATION_JSON))
        val properties = AiClientProperties(
            apiKey = "k",
            baseUrl = "https://openrouter.ai/api/v1",
            configuredModels = setOf(MODEL),
            zeroDataRetention = false,
        )

        AnnotationConfigApplicationContext().use { context ->
            // Registered first, so registration order alone would run it first.
            context.registerBean(StartupProbe::class.java, *emptyArray<BeanDefinitionCustomizer>())
            context.registerBean(ModelCapabilityService::class.java, Supplier { ModelCapabilityService(properties, builder) })
            context.refresh()

            context.publishEvent(ApplicationReadyEvent(SpringApplication(), emptyArray(), context, Duration.ZERO))

            assertEquals(listOf("transcription"), context.getBean(StartupProbe::class.java).seen?.outputModalities)
        }
    }

    private companion object {
        const val MODEL = "openai/whisper-large-v3"
    }
}
