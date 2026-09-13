package com.opendroid.ai.core.llm.providers

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ProviderRequestConfig
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.repository.SettingsRepository
import com.opendroid.ai.di.AppModule
import java.net.InetAddress
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A custom endpoint must not be able to move a gateway token to another host.
 *
 * Measured behaviour of the shared client: OkHttp drops `Authorization` when a
 * redirect crosses origins, but it forwards arbitrary custom headers (verified in the
 * probe these tests replaced). Since a custom header block routinely carries a token,
 * following a redirect would hand it to whatever host the redirect names — so the
 * custom provider refuses redirects and fails visibly instead.
 *
 * The endpoint is a mock server rather than a live gateway: the credential-leak
 * property is what is under test, not any particular vendor.
 */
class CustomOpenAIProviderRedirectTest {

    private val origin = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
    private val other = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }

    private val gatewayToken = "gw-token-0123456789abcdef"

    @After
    fun tearDown() {
        origin.close()
        other.close()
    }

    @Test
    fun `a redirect never forwards the gateway token to another host`() = runBlocking {
        val provider = providerWithHeaders("X-Gateway-Token: $gatewayToken")
        origin.enqueue(
            MockResponse.Builder()
                .code(307)
                .addHeader("Location", other.url("/v1/chat/completions").toString())
                .build()
        )
        other.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body("""{"choices": [{"message": {"content": "pong"}}]}""")
                .build()
        )

        // The call fails with the redirect status rather than silently succeeding
        // through a different origin.
        runCatching { runBlocking { provider.complete(newRequest()) } }

        // A regression that stops the initial request must fail this test, not hang it:
        // both takeRequest calls are bounded.
        val originRequest = origin.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("the origin must receive the request", originRequest)
        assertEquals(
            "the origin must carry the header",
            gatewayToken,
            originRequest!!.headers["X-Gateway-Token"]
        )
        assertNull(
            "no request may reach the redirect target",
            other.takeRequest(1, TimeUnit.SECONDS)
        )
    }

    private fun newRequest() = LLMRequest(
        systemPrompt = "you are a test",
        messages = listOf(ChatMessage("1", "ping", ChatMessage.Sender.USER)),
        model = "gpt-4o-mini",
        providerConfig = ProviderRequestConfig(
            apiKey = "sk-test-0123456789abcdef",
            endpoint = origin.url("/v1").toString()
        )
    )

    private suspend fun providerWithHeaders(headers: String): CustomOpenAIProvider {
        val repository = SettingsRepository(
            dataStore = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                produceFile = {
                    Files.createTempDirectory("opendroid-redirect-test")
                        .resolve("settings.preferences_pb")
                        .toFile()
                }
            ),
            providerCredentialStore = EmptyProviderCredentialStore(),
            runStartupMigration = false
        )
        repository.updateCustomHeaders(PROVIDER, headers)
        return CustomOpenAIProvider(AppModule.provideOkHttpClient(), repository)
    }

    private companion object {
        const val PROVIDER = "Custom OpenAI Compatible"
    }

    private class EmptyProviderCredentialStore : ProviderCredentialStore {
        private val customHeaders = mutableMapOf<String, String>()

        override val recoveryState: StateFlow<ProviderCredentialRecoveryState> =
            MutableStateFlow(ProviderCredentialRecoveryState.Ready)

        override fun read(credential: ProviderCredentialId): CredentialStoreResult<String?> =
            CredentialStoreResult.Success(null)

        override fun readProviderApiKeys(): CredentialStoreResult<Map<String, String>> =
            CredentialStoreResult.Success(emptyMap())

        override fun write(credential: ProviderCredentialId, value: String): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun remove(credential: ProviderCredentialId): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun readCustomHeaders(): CredentialStoreResult<Map<String, String>> =
            CredentialStoreResult.Success(customHeaders.toMap())

        override fun writeCustomHeaders(
            headers: Map<String, String>,
            removeProviders: Collection<String>
        ): CredentialStoreResult<Unit> {
            headers.filterValues { it.isNotBlank() }.forEach { (provider, block) ->
                customHeaders[provider] = block
            }
            removeProviders.forEach(customHeaders::remove)
            return CredentialStoreResult.Success(Unit)
        }

        override fun clearCustomHeaders(): CredentialStoreResult<Unit> {
            customHeaders.clear()
            return CredentialStoreResult.Success(Unit)
        }

        override fun migrateLegacyCredentials(): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun resetForReentry(): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)
    }
}
