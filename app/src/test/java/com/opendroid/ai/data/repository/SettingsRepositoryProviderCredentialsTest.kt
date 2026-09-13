package com.opendroid.ai.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.models.LLMConfig
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryProviderCredentialsTest {

    @Test
    fun `legacy LLMConfig credentials are migrated then stripped from the DataStore JSON`() = runBlocking {
        val dataStore = newDataStore()
        val credentials = InMemoryProviderCredentialStore()
        val legacyProviderSecret = "sk-legacy-provider-secret"
        val legacyElevenLabsSecret = "elevenlabs-legacy-secret"
        dataStore.edit { preferences ->
            preferences[LLM_CONFIG_KEY] = Json.encodeToString(
                LLMConfig(
                    apiKeys = mapOf("OpenAI" to legacyProviderSecret),
                    elevenLabsApiKey = legacyElevenLabsSecret,
                    elevenLabsVoiceId = "voice-id"
                )
            )
        }
        val repository = SettingsRepository(
            dataStore = dataStore,
            providerCredentialStore = credentials,
            runStartupMigration = false
        )

        repository.updateConfig { it }

        val persistedJson = dataStore.data.first()[LLM_CONFIG_KEY].orEmpty()
        assertFalse(persistedJson.contains(legacyProviderSecret))
        assertFalse(persistedJson.contains(legacyElevenLabsSecret))
        val persisted = Json.decodeFromString<LLMConfig>(persistedJson)
        assertTrue(persisted.apiKeys.isEmpty())
        assertEquals("", persisted.elevenLabsApiKey)
        assertEquals("voice-id", persisted.elevenLabsVoiceId)

        assertEquals(legacyProviderSecret, credentials.values[ProviderCredentialId.ApiKey("OpenAI")])
        assertEquals(legacyElevenLabsSecret, credentials.values[ProviderCredentialId.ElevenLabsApiKey])

        val hydrated = repository.llmConfig.first()
        assertEquals(legacyProviderSecret, hydrated.apiKeys["OpenAI"])
        assertEquals(legacyElevenLabsSecret, hydrated.elevenLabsApiKey)
    }

    @Test
    fun `unavailable credential storage retains prior DataStore source but never exposes it as a fallback`() = runBlocking {
        val dataStore = newDataStore()
        val credentials = InMemoryProviderCredentialStore(unavailable = true)
        val plaintextSecret = "sk-must-not-survive-keystore-failure"
        dataStore.edit { preferences ->
            preferences[LLM_CONFIG_KEY] = Json.encodeToString(
                LLMConfig(
                    apiKeys = mapOf("OpenAI" to plaintextSecret),
                    elevenLabsApiKey = "elevenlabs-must-not-survive"
                )
            )
        }
        val repository = SettingsRepository(dataStore, credentials, runStartupMigration = false)

        assertEquals(
            ProviderCredentialPersistenceState.CredentialsMustBeReentered,
            repository.updateConfig { it }
        )

        val persistedJson = dataStore.data.first()[LLM_CONFIG_KEY].orEmpty()
        assertTrue(persistedJson.contains(plaintextSecret))
        assertTrue(repository.llmConfig.first().apiKeys.isEmpty())
        assertEquals("", repository.llmConfig.first().elevenLabsApiKey)
        assertEquals(
            ProviderCredentialRecoveryState.CredentialsMustBeReentered,
            repository.providerCredentialRecoveryState.value
        )
    }

    @Test
    fun `reentry state blocks plaintext DataStore migration even when another direct credential is readable`() = runBlocking {
        val dataStore = newDataStore()
        val credentials = InMemoryProviderCredentialStore(recoveryRequired = true)
        val plaintextSecret = "sk-not-a-recovery-source"
        dataStore.edit { preferences ->
            preferences[LLM_CONFIG_KEY] = Json.encodeToString(
                LLMConfig(apiKeys = mapOf("OpenAI" to plaintextSecret))
            )
        }
        val repository = SettingsRepository(dataStore, credentials, runStartupMigration = false)

        assertEquals(
            ProviderCredentialPersistenceState.CredentialsMustBeReentered,
            repository.updateConfig { it }
        )

        val persistedJson = dataStore.data.first()[LLM_CONFIG_KEY].orEmpty()
        assertTrue(persistedJson.contains(plaintextSecret))
        assertTrue(credentials.values.isEmpty())
        assertTrue(repository.llmConfig.first().apiKeys.isEmpty())
    }

    @Test
    fun `storage unavailable keeps the prior DataStore credential source unchanged`() = runBlocking {
        val dataStore = newDataStore()
        val credentials = InMemoryProviderCredentialStore(failMutationAt = 1)
        val previousSecret = "sk-existing-source-must-not-be-lost"
        val previousJson = Json.encodeToString(
            LLMConfig(apiKeys = mapOf("OpenAI" to previousSecret), elevenLabsVoiceId = "voice-id")
        )
        dataStore.edit { preferences -> preferences[LLM_CONFIG_KEY] = previousJson }
        val repository = SettingsRepository(dataStore, credentials, runStartupMigration = false)

        assertEquals(
            ProviderCredentialPersistenceState.StorageUnavailable,
            repository.updateConfig { it }
        )

        assertEquals(previousJson, dataStore.data.first()[LLM_CONFIG_KEY])
        assertEquals(
            ProviderCredentialPersistenceState.StorageUnavailable,
            repository.providerCredentialPersistenceState.value
        )
        assertTrue(credentials.values.isEmpty())
        assertTrue(repository.llmConfig.first().apiKeys.isEmpty())
    }

    @Test
    fun `partial direct-store failure rolls back prior mutations and leaves DataStore unchanged`() = runBlocking {
        val dataStore = newDataStore()
        val openAi = ProviderCredentialId.ApiKey("OpenAI")
        val credentials = InMemoryProviderCredentialStore(
            initialValues = mapOf(openAi to "old-openai-secret"),
            failMutationAt = 2
        )
        val previousJson = Json.encodeToString(LLMConfig(elevenLabsVoiceId = "voice-id"))
        dataStore.edit { preferences -> preferences[LLM_CONFIG_KEY] = previousJson }
        val repository = SettingsRepository(dataStore, credentials, runStartupMigration = false)

        assertEquals(
            ProviderCredentialPersistenceState.StorageUnavailable,
            repository.updateConfig {
                it.copy(
                    apiKeys = mapOf("OpenAI" to "new-openai-secret"),
                    elevenLabsApiKey = "new-elevenlabs-secret"
                )
            }
        )

        assertEquals(previousJson, dataStore.data.first()[LLM_CONFIG_KEY])
        assertEquals("old-openai-secret", credentials.values[openAi])
        assertFalse(credentials.values.containsKey(ProviderCredentialId.ElevenLabsApiKey))
        assertTrue(repository.llmConfig.first().apiKeys["OpenAI"] == "old-openai-secret")
    }

    @Test
    fun `custom headers reach the Keystore and never stay in the DataStore JSON`() = runBlocking {
        val dataStore = newDataStore()
        val credentials = InMemoryProviderCredentialStore()
        val gatewayToken = "gw-token-0123456789abcdef"
        val block = "X-Gateway-Token: $gatewayToken"
        val repository = SettingsRepository(dataStore, credentials, runStartupMigration = false)

        repository.updateConfig { it.copy(customHeaders = mapOf(PROVIDER to block)) }

        val persistedJson = dataStore.data.first()[LLM_CONFIG_KEY].orEmpty()
        assertFalse(persistedJson.contains(gatewayToken))
        assertTrue(
            Json.decodeFromString<LLMConfig>(persistedJson).customHeaders.isEmpty()
        )
        assertEquals(
            block,
            credentials.values[ProviderCredentialId.CustomHeaders(PROVIDER)]
        )
        // Readers still see the block, hydrated from the encrypted record.
        assertEquals(block, repository.llmConfig.first().customHeaders[PROVIDER])
        assertEquals(
            block,
            repository.llmConfigForProviderRequests.first().customHeaders[PROVIDER]
        )
    }

    @Test
    fun `clearing a header block removes the encrypted record rather than leaving it live`() = runBlocking {
        val dataStore = newDataStore()
        val credentials = InMemoryProviderCredentialStore()
        val credential = ProviderCredentialId.CustomHeaders(PROVIDER)
        val repository = SettingsRepository(dataStore, credentials, runStartupMigration = false)
        repository.updateConfig { it.copy(customHeaders = mapOf(PROVIDER to "X-Tenant: acme")) }

        repository.updateConfig { it.copy(customHeaders = emptyMap()) }

        assertFalse(credentials.values.containsKey(credential))
        assertTrue(repository.llmConfig.first().customHeaders.isEmpty())
    }

    @Test
    fun `startup migration keeps the encrypted record it just wrote`() = runBlocking {
        // The migration path goes through updateConfig, whose lambda receives
        // mergeSecretsForUpdate(config): that value already contains the encrypted
        // records written moments earlier. Stripping the plaintext keys from it must
        // not be mistaken for "the user cleared these headers", which would tombstone
        // the records the migration just created and lose the block for good.
        val dataStore = newDataStore()
        val credentials = InMemoryProviderCredentialStore()
        val block = "X-Gateway-Token: gw-migration-token-0123456789"
        dataStore.edit { preferences ->
            preferences[LLM_CONFIG_KEY] = Json.encodeToString(
                LLMConfig(customHeaders = mapOf(PROVIDER to block))
            )
        }

        val repository = SettingsRepository(dataStore, credentials, runStartupMigration = true)

        // The migration runs on its own coroutine; wait for it to finish rather than
        // asserting on a race.
        withTimeout(5_000) {
            while (Json.decodeFromString<LLMConfig>(
                    dataStore.data.first()[LLM_CONFIG_KEY].orEmpty()
                ).customHeaders.isNotEmpty()
            ) {
                delay(20)
            }
        }

        assertEquals(
            "the migration must leave the encrypted record in place",
            block,
            credentials.values[ProviderCredentialId.CustomHeaders(PROVIDER)]
        )
        assertFalse(
            "the plaintext copy must be stripped from the DataStore JSON",
            dataStore.data.first()[LLM_CONFIG_KEY].orEmpty().contains("gw-migration-token")
        )
        assertEquals(block, repository.llmConfig.first().customHeaders[PROVIDER])
    }

    @Test
    fun `plaintext header blocks from an older build are migrated into the Keystore and stripped`() = runBlocking {
        val dataStore = newDataStore()
        val credentials = InMemoryProviderCredentialStore()
        val gatewayToken = "gw-legacy-token-0123456789"
        val block = "X-Gateway-Token: $gatewayToken"
        dataStore.edit { preferences ->
            preferences[LLM_CONFIG_KEY] = Json.encodeToString(
                LLMConfig(customHeaders = mapOf(PROVIDER to block))
            )
        }
        val repository = SettingsRepository(dataStore, credentials, runStartupMigration = false)

        assertTrue(repository.updateCustomHeaders(PROVIDER, block) is CredentialStoreResult.Success)
        repository.updateConfig { it }

        val persistedJson = dataStore.data.first()[LLM_CONFIG_KEY].orEmpty()
        assertFalse(persistedJson.contains(gatewayToken))
        assertEquals(block, credentials.values[ProviderCredentialId.CustomHeaders(PROVIDER)])
    }

    @Test
    fun `an update that cannot reach the Keystore leaves the plaintext source retryable`() = runBlocking {
        val dataStore = newDataStore()
        val credentials = InMemoryProviderCredentialStore(unavailable = true)
        val block = "X-Tenant: acme"
        dataStore.edit { preferences ->
            preferences[LLM_CONFIG_KEY] = Json.encodeToString(
                LLMConfig(customHeaders = mapOf(PROVIDER to block))
            )
        }
        val repository = SettingsRepository(dataStore, credentials, runStartupMigration = false)

        assertEquals(
            ProviderCredentialPersistenceState.CredentialsMustBeReentered,
            repository.updateConfig { it }
        )

        // Dropping the plaintext here would lose the user's headers for good.
        val persisted = Json.decodeFromString<LLMConfig>(
            dataStore.data.first()[LLM_CONFIG_KEY].orEmpty()
        )
        assertEquals(block, persisted.customHeaders[PROVIDER])
        assertTrue(repository.llmConfig.first().customHeaders.isEmpty())
    }

    @Test
    fun `a header block pending migration is still used by a provider request`() = runBlocking {
        val dataStore = newDataStore()
        val credentials = InMemoryProviderCredentialStore()
        val block = "X-Tenant: acme"
        dataStore.edit { preferences ->
            preferences[LLM_CONFIG_KEY] = Json.encodeToString(
                LLMConfig(customHeaders = mapOf(PROVIDER to block))
            )
        }
        val repository = SettingsRepository(dataStore, credentials, runStartupMigration = false)

        // The Keystore holds nothing yet, so the not-yet-migrated block is what the
        // request path has to use rather than silently sending no headers.
        assertEquals(
            block,
            repository.llmConfigForProviderRequests.first().customHeaders[PROVIDER]
        )
    }

    @Test
    fun `a failed credential read does not fall back to the persisted header copy`() = runBlocking {
        // Zero Plaintext Fallback applies to headers exactly as it does to API keys: when
        // the Keystore cannot be read, a persisted plaintext block must not be used in its
        // place, or an unreadable record would silently downgrade to the stale copy
        // instead of surfacing recovery.
        //
        // Note the distinction the fake makes explicit: an unavailable *store* fails the
        // read. A store that merely reports re-entry-required still answers reads (with an
        // empty map), and in that case a persisted block genuinely is pre-migration state.
        val dataStore = newDataStore()
        val block = "X-Gateway-Token: gw-should-not-be-sent"
        dataStore.edit { preferences ->
            preferences[LLM_CONFIG_KEY] = Json.encodeToString(
                LLMConfig(customHeaders = mapOf(PROVIDER to block))
            )
        }
        val credentials = InMemoryProviderCredentialStore(unavailable = true)
        val repository = SettingsRepository(dataStore, credentials, runStartupMigration = false)

        assertTrue(
            "no header may reach a request while the credential store is unreadable",
            repository.llmConfigForProviderRequests.first().customHeaders.isEmpty()
        )
        assertTrue(repository.llmConfig.first().customHeaders.isEmpty())
    }

    private fun newDataStore() = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        produceFile = {
            Files.createTempDirectory("opendroid-settings-test")
                .resolve("settings.preferences_pb")
                .toFile()
        }
    )

    private class InMemoryProviderCredentialStore(
        private val unavailable: Boolean = false,
        recoveryRequired: Boolean = false,
        initialValues: Map<ProviderCredentialId, String> = emptyMap(),
        private val failMutationAt: Int? = null
    ) : ProviderCredentialStore {
        val values = initialValues.toMutableMap()
        private var mutationCount = 0
        private val mutableRecoveryState = MutableStateFlow<ProviderCredentialRecoveryState>(
            if (recoveryRequired) {
                ProviderCredentialRecoveryState.CredentialsMustBeReentered
            } else {
                ProviderCredentialRecoveryState.Ready
            }
        )

        override val recoveryState: StateFlow<ProviderCredentialRecoveryState> = mutableRecoveryState

        override fun read(credential: ProviderCredentialId): CredentialStoreResult<String?> =
            unavailableResult() ?: CredentialStoreResult.Success(values[credential])

        override fun readProviderApiKeys(): CredentialStoreResult<Map<String, String>> =
            unavailableResult() ?: CredentialStoreResult.Success(
                values.filterKeys { it is ProviderCredentialId.ApiKey }
                    .mapKeys { (credential, _) -> (credential as ProviderCredentialId.ApiKey).providerName }
            )

        override fun readCustomHeaders(): CredentialStoreResult<Map<String, String>> =
            unavailableResult() ?: CredentialStoreResult.Success(
                values.filterKeys { it is ProviderCredentialId.CustomHeaders }
                    .mapKeys { (credential, _) ->
                        (credential as ProviderCredentialId.CustomHeaders).providerName
                    }
            )

        override fun writeCustomHeaders(
            headers: Map<String, String>,
            removeProviders: Collection<String>
        ): CredentialStoreResult<Unit> {
            for ((providerName, block) in headers) {
                if (block.isBlank()) continue
                val result = write(ProviderCredentialId.CustomHeaders(providerName), block)
                if (result !is CredentialStoreResult.Success) return result
            }
            for (providerName in removeProviders) {
                val result = remove(ProviderCredentialId.CustomHeaders(providerName))
                if (result !is CredentialStoreResult.Success) return result
            }
            return CredentialStoreResult.Success(Unit)
        }

        override fun clearCustomHeaders(): CredentialStoreResult<Unit> {
            values.keys.filterIsInstance<ProviderCredentialId.CustomHeaders>()
                .forEach { values.remove(it) }
            return CredentialStoreResult.Success(Unit)
        }

        override fun write(
            credential: ProviderCredentialId,
            value: String
        ): CredentialStoreResult<Unit> {
            mutationFailureResult()?.let { return it }
            if (value.isBlank()) {
                values.remove(credential)
            } else {
                values[credential] = value
            }
            return CredentialStoreResult.Success(Unit)
        }

        override fun remove(credential: ProviderCredentialId): CredentialStoreResult<Unit> {
            mutationFailureResult()?.let { return it }
            values.remove(credential)
            return CredentialStoreResult.Success(Unit)
        }

        override fun migrateLegacyCredentials(): CredentialStoreResult<Unit> =
            unavailableResult<Unit>() ?: CredentialStoreResult.Success(Unit)

        override fun resetForReentry(): CredentialStoreResult<Unit> {
            values.clear()
            return CredentialStoreResult.Success(Unit)
        }

        private fun <T> unavailableResult(): CredentialStoreResult<T>? {
            if (!unavailable) return null
            mutableRecoveryState.value = ProviderCredentialRecoveryState.CredentialsMustBeReentered
            return CredentialStoreResult.CredentialsMustBeReentered
        }

        private fun mutationFailureResult(): CredentialStoreResult<Unit>? {
            unavailableResult<Unit>()?.let { return it }
            mutationCount += 1
            if (mutationCount == failMutationAt) return CredentialStoreResult.StorageUnavailable
            return null
        }
    }

    private companion object {
        val LLM_CONFIG_KEY = stringPreferencesKey("llm_config")
        const val PROVIDER = "Custom OpenAI Compatible"
    }
}
