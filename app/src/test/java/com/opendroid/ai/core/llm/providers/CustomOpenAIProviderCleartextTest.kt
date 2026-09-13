package com.opendroid.ai.core.llm.providers

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ProviderRequestConfig
import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.core.llm.error.LLMException
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.repository.SettingsRepository
import com.opendroid.ai.di.AppModule
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * A cleartext endpoint aimed at another host must not receive the credential.
 *
 * This is the transport half of the "Require TLS before forwarding custom credentials"
 * finding. The completion path refuses before building the request, so neither the API
 * key nor a custom header value can leave the device in the clear — and it refuses with
 * a typed `RequestInvalid` rather than leaving OkHttp to fail at connect time with
 * "CLEARTEXT communication not enabled for client".
 *
 * The endpoint here is a loopback mock server standing in for a remote host: the origin
 * address is deliberately reached through a host *name* that is not one of the three
 * locally-trusted cleartext hosts, because that is the rule under test.
 */
class CustomOpenAIProviderCleartextTest {

    private val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `a cleartext endpoint on another host is refused without sending anything`() {
        val provider = CustomOpenAIProvider(AppModule.provideOkHttpClient(), newSettingsRepository())
        // 127.0.0.1 is trusted, so address the same server by a name that is not trusted.
        val untrustedCleartextEndpoint = "http://gateway.example.com:${server.port}/v1"

        val failure = assertThrows(LLMException::class.java) {
            runBlocking { provider.complete(newRequest(untrustedCleartextEndpoint)) }
        }

        assertEquals(LLMError.RequestInvalid, failure.error)
        assertNull(
            "no request may reach a cleartext endpoint on another host",
            server.takeRequest(1, TimeUnit.SECONDS)
        )
    }

    @Test
    fun `a cleartext endpoint on this device is still allowed to proceed`() {
        server.enqueue(
            mockwebserver3.MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body("""{"choices": [{"message": {"content": "pong"}}]}""")
                .build()
        )
        val provider = CustomOpenAIProvider(AppModule.provideOkHttpClient(), newSettingsRepository())

        // 127.0.0.1 is one of the three hosts the app's network security config trusts, so
        // the request must go out rather than being refused by the new rule.
        val response = runBlocking {
            provider.complete(newRequest("http://127.0.0.1:${server.port}/v1"))
        }

        assertEquals("pong", response.content)
        assertEquals("/v1/chat/completions", server.takeRequest().target)
    }

    private fun newRequest(endpoint: String) = LLMRequest(
        systemPrompt = "you are a test",
        messages = listOf(ChatMessage("1", "ping", ChatMessage.Sender.USER)),
        model = "gpt-4o-mini",
        providerConfig = ProviderRequestConfig(
            apiKey = "sk-test-0123456789abcdef",
            endpoint = endpoint
        )
    )

    private fun newSettingsRepository() = SettingsRepository(
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = {
                Files.createTempDirectory("opendroid-cleartext-test")
                    .resolve("settings.preferences_pb")
                    .toFile()
            }
        ),
        providerCredentialStore = EmptyProviderCredentialStore(),
        runStartupMigration = false
    )

    private class EmptyProviderCredentialStore : ProviderCredentialStore {
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
            CredentialStoreResult.Success(emptyMap())

        override fun writeCustomHeaders(
            headers: Map<String, String>,
            removeProviders: Collection<String>
        ): CredentialStoreResult<Unit> = CredentialStoreResult.Success(Unit)

        override fun clearCustomHeaders(): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun migrateLegacyCredentials(): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun resetForReentry(): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)
    }
}
