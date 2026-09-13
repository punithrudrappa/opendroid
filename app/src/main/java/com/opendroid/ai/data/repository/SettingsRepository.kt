package com.opendroid.ai.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.models.AutoReplyConfig
import com.opendroid.ai.data.models.LLMConfig
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Outcome for a configuration save that needs direct credential-store persistence. */
sealed interface ProviderCredentialPersistenceState {
    data object Ready : ProviderCredentialPersistenceState
    data object StorageUnavailable : ProviderCredentialPersistenceState
    data object CredentialsMustBeReentered : ProviderCredentialPersistenceState
}

@Singleton
class SettingsRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val providerCredentialStore: ProviderCredentialStore,
    private val runStartupMigration: Boolean
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        providerCredentialStore: ProviderCredentialStore
    ) : this(context.dataStore, providerCredentialStore, runStartupMigration = true)

    private val json = Json { ignoreUnknownKeys = true }
    private val llmConfigKey = stringPreferencesKey("llm_config")

    /** A UI-safe recovery signal; it never contains credential or ciphertext data. */
    val providerCredentialRecoveryState = providerCredentialStore.recoveryState

    private val mutableProviderCredentialPersistenceState =
        MutableStateFlow<ProviderCredentialPersistenceState>(ProviderCredentialPersistenceState.Ready)
    /** Observable save result for callers that need to retry a transient storage failure. */
    val providerCredentialPersistenceState: StateFlow<ProviderCredentialPersistenceState> =
        mutableProviderCredentialPersistenceState.asStateFlow()

    init {
        if (runStartupMigration) {
            // Legacy encrypted-preference credentials are imported before DataStore
            // secrets are stripped. If either store is unavailable, updateConfig still strips
            // plaintext rather than using it as a recovery fallback.
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                try {
                    providerCredentialStore.migrateLegacyCredentials()
                    updateConfig { it }
                    // Header blocks written by earlier builds sat in the plaintext config;
                    // this moves them into the Keystore and strips them from the JSON.
                    migratePlaintextCustomHeaders()
                } catch (_: Exception) {
                    // The credential store has no plaintext fallback; a later update retries.
                }
            }
        }
    }

    /**
     * Reads only authenticated direct-store values. Persisted JSON credentials are migration
     * input, never a runtime credential fallback.
     *
     * Custom-header blocks are credentials too, so a reader sees the decrypted block here while
     * the config the DataStore actually holds keeps its copy empty.
     */
    private fun mergeSecretsForRead(persisted: LLMConfig): LLMConfig {
        val snapshot = (readCredentialSnapshot() as? CredentialSnapshotResult.Success)?.snapshot
            ?: return persisted.strippedOfSecrets()
        return persisted.copy(
            apiKeys = snapshot.providerApiKeys,
            elevenLabsApiKey = snapshot.elevenLabsApiKey.orEmpty(),
            customHeaders = snapshot.customHeaders
        )
    }

    /**
     * Supplies legacy DataStore values to a write only when direct credential reads are healthy.
     * This gives the one-time DataStore migration a source without exposing it to callers.
     *
     * A header block the Keystore does not hold yet is carried over from the persisted config, so
     * an update that cannot reach the credential store leaves the plaintext source retryable
     * instead of dropping the user's headers.
     */
    private fun mergeSecretsForUpdate(persisted: LLMConfig): LLMConfig {
        val snapshot = (readCredentialSnapshot() as? CredentialSnapshotResult.Success)?.snapshot
            ?: return persisted.strippedOfSecrets()
        return persisted.copy(
            apiKeys = persisted.apiKeys + snapshot.providerApiKeys,
            elevenLabsApiKey = snapshot.elevenLabsApiKey ?: persisted.elevenLabsApiKey,
            customHeaders = persisted.customHeaders + snapshot.customHeaders
        )
    }

    /** The shape every read/write path uses when the Keystore cannot be trusted. */
    private fun LLMConfig.strippedOfSecrets(): LLMConfig =
        copy(apiKeys = emptyMap(), elevenLabsApiKey = "", customHeaders = emptyMap())

    /**
     * Configuration for an outbound provider request.
     *
     * Only the two credential families whose *absence* changes a request's shape are resolved
     * (API keys and custom-header blocks). Latency benchmarks, model caches, and every other
     * setting come straight from the DataStore snapshot, so this stays off the main thread.
     *
     * A plaintext block still in the DataStore is used only while the Keystore holds none for that
     * provider. That is the pre-migration state — [migratePlaintextCustomHeaders] writes the
     * encrypted record before it strips the plaintext, so the two sources never both hold a value
     * and a request can never silently lose a user's headers.
     */
    val llmConfigForProviderRequests: Flow<LLMConfig> = dataStore.data
        .map { preferences -> hydrateProviderRequestSecrets(decodeConfig(preferences[llmConfigKey])) }
        .flowOn(Dispatchers.IO)

    private fun hydrateProviderRequestSecrets(persisted: LLMConfig): LLMConfig {
        val apiKeys = when (val stored = providerCredentialStore.readProviderApiKeys()) {
            is CredentialStoreResult.Success -> stored.value
            CredentialStoreResult.CredentialsMustBeReentered,
            CredentialStoreResult.StorageUnavailable -> emptyMap()
        }
        val encryptedHeaders = when (val stored = providerCredentialStore.readCustomHeaders()) {
            is CredentialStoreResult.Success -> stored.value
            CredentialStoreResult.CredentialsMustBeReentered,
            CredentialStoreResult.StorageUnavailable -> emptyMap()
        }
        return persisted.copy(
            apiKeys = apiKeys,
            customHeaders = persisted.customHeaders + encryptedHeaders,
            elevenLabsApiKey = ""
        )
    }

    /**
     * Commits direct credentials before returning a stripped DataStore configuration.
     * Any failed direct-store mutation rolls back successful earlier mutations and aborts the
     * DataStore transaction, preserving the previously persisted configuration as retry input.
     */
    private fun storeSecretsAndStrip(config: LLMConfig): CredentialStripResult {
        val snapshot = when (val snapshotResult = readCredentialSnapshot()) {
            is CredentialSnapshotResult.Success -> snapshotResult.snapshot
            is CredentialSnapshotResult.Failure -> return CredentialStripResult.Failure(snapshotResult.state)
        }
        val desiredProviderApiKeys = linkedMapOf<String, String>()
        config.apiKeys.forEach { (provider, key) ->
            // Invalid historical JSON keys are deliberately stripped, never materialized as
            // direct records, and cannot block a safe migration.
            val credential = runCatching { ProviderCredentialId.ApiKey(provider) }.getOrNull()
                ?: return@forEach
            if (key.isNotBlank()) desiredProviderApiKeys[credential.providerName] = key
        }
        val attemptedCredentials = linkedSetOf<ProviderCredentialId>()
        val providerNames = linkedSetOf<String>().apply {
            addAll(snapshot.providerApiKeys.keys)
            addAll(desiredProviderApiKeys.keys)
        }

        for (providerName in providerNames) {
            val previousValue = snapshot.providerApiKeys[providerName]
            val desiredValue = desiredProviderApiKeys[providerName]
            if (previousValue == desiredValue) continue
            val credential = ProviderCredentialId.ApiKey(providerName)
            attemptedCredentials += credential
            val result = persistCredential(credential, desiredValue)
            if (result !is CredentialStoreResult.Success) {
                return CredentialStripResult.Failure(
                    restoreSnapshot(snapshot, attemptedCredentials) ?: result.toPersistenceState()
                )
            }
        }

        val desiredElevenLabsKey = config.elevenLabsApiKey.takeUnless(String::isBlank)
        if (snapshot.elevenLabsApiKey != desiredElevenLabsKey) {
            val credential = ProviderCredentialId.ElevenLabsApiKey
            attemptedCredentials += credential
            val result = persistCredential(credential, desiredElevenLabsKey)
            if (result !is CredentialStoreResult.Success) {
                return CredentialStripResult.Failure(
                    restoreSnapshot(snapshot, attemptedCredentials) ?: result.toPersistenceState()
                )
            }
        }

        // Custom-header blocks are committed here and stripped from the persisted config, so a
        // gateway token never stays readable in the DataStore JSON.
        val stripResult = storeCustomHeadersAndStrip(config, snapshot, attemptedCredentials)
        if (stripResult != null) return CredentialStripResult.Failure(stripResult)

        return CredentialStripResult.Success(
            config.copy(apiKeys = emptyMap(), elevenLabsApiKey = "", customHeaders = emptyMap())
        )
    }

    /**
     * Writes every configured header block to the Keystore and returns null on success, or the
     * state to report when a mutation failed and the snapshot could not be restored.
     *
     * Providers whose block was cleared are removed explicitly, so a cleared editor does not leave
     * the old encrypted record behind as an invisible live credential.
     */
    private fun storeCustomHeadersAndStrip(
        config: LLMConfig,
        snapshot: CredentialSnapshot,
        attemptedCredentials: MutableSet<ProviderCredentialId>
    ): ProviderCredentialPersistenceState? {
        val desired = linkedMapOf<String, String>()
        config.customHeaders.forEach { (provider, block) ->
            val credential = runCatching { ProviderCredentialId.CustomHeaders(provider) }.getOrNull()
                ?: return@forEach
            if (block.isNotBlank()) desired[credential.providerName] = block
        }
        val removals = snapshot.customHeaders.keys - desired.keys
        if (desired.isEmpty() && removals.isEmpty()) return null

        attemptedCredentials +=
            desired.keys.map { ProviderCredentialId.CustomHeaders(it) } +
                removals.map { ProviderCredentialId.CustomHeaders(it) }

        return when (val result = providerCredentialStore.writeCustomHeaders(desired, removals)) {
            is CredentialStoreResult.Success -> null
            else -> restoreSnapshot(snapshot, attemptedCredentials) ?: result.toPersistenceState()
        }
    }

    private fun persistCredential(
        credential: ProviderCredentialId,
        value: String?
    ): CredentialStoreResult<Unit> = if (value == null) {
        providerCredentialStore.remove(credential)
    } else {
        providerCredentialStore.write(credential, value)
    }

    /** Restores the pre-update semantic credential snapshot after a partial direct-store write. */
    private fun restoreSnapshot(
        snapshot: CredentialSnapshot,
        attemptedCredentials: Set<ProviderCredentialId>
    ): ProviderCredentialPersistenceState? {
        for (credential in attemptedCredentials) {
            val previousValue = when (credential) {
                is ProviderCredentialId.ApiKey -> snapshot.providerApiKeys[credential.providerName]
                is ProviderCredentialId.CustomHeaders -> snapshot.customHeaders[credential.providerName]
                ProviderCredentialId.ElevenLabsApiKey -> snapshot.elevenLabsApiKey
                ProviderCredentialId.HuggingFaceToken -> null
            }
            val result = persistCredential(credential, previousValue)
            if (result !is CredentialStoreResult.Success) return result.toPersistenceState()
        }
        return null
    }

    private fun CredentialStoreResult<*>.toPersistenceState(): ProviderCredentialPersistenceState = when (this) {
        CredentialStoreResult.CredentialsMustBeReentered ->
            ProviderCredentialPersistenceState.CredentialsMustBeReentered
        CredentialStoreResult.StorageUnavailable -> ProviderCredentialPersistenceState.StorageUnavailable
        is CredentialStoreResult.Success -> ProviderCredentialPersistenceState.Ready
    }

    private fun readCredentialSnapshot(): CredentialSnapshotResult {
        if (providerCredentialStore.recoveryState.value ==
            ProviderCredentialRecoveryState.CredentialsMustBeReentered
        ) {
            return CredentialSnapshotResult.Failure(
                ProviderCredentialPersistenceState.CredentialsMustBeReentered
            )
        }
        val providerApiKeys = providerCredentialStore.readProviderApiKeys()
        val elevenLabsApiKey = providerCredentialStore.read(ProviderCredentialId.ElevenLabsApiKey)
        val customHeaders = providerCredentialStore.readCustomHeaders()
        if (providerApiKeys !is CredentialStoreResult.Success ||
            elevenLabsApiKey !is CredentialStoreResult.Success ||
            customHeaders !is CredentialStoreResult.Success ||
            providerCredentialStore.recoveryState.value ==
                ProviderCredentialRecoveryState.CredentialsMustBeReentered
        ) {
            val failure = when {
                providerCredentialStore.recoveryState.value ==
                    ProviderCredentialRecoveryState.CredentialsMustBeReentered ->
                    ProviderCredentialPersistenceState.CredentialsMustBeReentered
                providerApiKeys !is CredentialStoreResult.Success ->
                    providerApiKeys.toPersistenceState()
                elevenLabsApiKey !is CredentialStoreResult.Success ->
                    elevenLabsApiKey.toPersistenceState()
                else -> customHeaders.toPersistenceState()
            }
            return CredentialSnapshotResult.Failure(failure)
        }
        return CredentialSnapshotResult.Success(
            CredentialSnapshot(providerApiKeys.value, elevenLabsApiKey.value, customHeaders.value)
        )
    }

    /**
     * Writes a header block encrypted, for the editor's save path.
     *
     * A blank block removes the record, and the DataStore copy stays empty either way — this is
     * the only way the plaintext the user types reaches persistence, and it reaches it as
     * ciphertext under a Keystore key.
     */
    suspend fun updateCustomHeaders(
        providerName: String,
        headers: String
    ): CredentialStoreResult<Unit> = withContext(Dispatchers.IO) {
        val credential = runCatching { ProviderCredentialId.CustomHeaders(providerName) }.getOrNull()
            ?: return@withContext CredentialStoreResult.StorageUnavailable
        val result = if (headers.isBlank()) {
            providerCredentialStore.writeCustomHeaders(emptyMap(), listOf(credential.providerName))
        } else {
            providerCredentialStore.writeCustomHeaders(
                mapOf(credential.providerName to headers),
                emptyList()
            )
        }
        mutableProviderCredentialPersistenceState.value = result.toPersistenceState()
        result
    }

    /**
     * Migrates header blocks written by builds that kept them in the plaintext DataStore config.
     *
     * Each block is committed to the Keystore first and the config is stripped only after the
     * whole batch succeeded, so a storage failure leaves the DataStore as the single retryable
     * source instead of losing the user's headers.
     */
    private suspend fun migratePlaintextCustomHeaders(): CredentialStoreResult<Unit> {
        val persisted = decodeConfig(dataStore.data.first()[llmConfigKey])
        val plaintextBlocks = persisted.customHeaders.filterValues { it.isNotBlank() }
        if (plaintextBlocks.isEmpty()) return CredentialStoreResult.Success(Unit)
        return when (providerCredentialStore.writeCustomHeaders(plaintextBlocks, emptyList())) {
            is CredentialStoreResult.Success -> updateConfig { current ->
                current.copy(customHeaders = current.customHeaders - plaintextBlocks.keys)
            }.let { CredentialStoreResult.Success(Unit) }
            CredentialStoreResult.CredentialsMustBeReentered ->
                CredentialStoreResult.CredentialsMustBeReentered
            CredentialStoreResult.StorageUnavailable -> CredentialStoreResult.StorageUnavailable
        }
    }

    suspend fun resetProviderCredentialsForReentry(): CredentialStoreResult<Unit> =
        withContext(Dispatchers.IO) {
            providerCredentialStore.resetForReentry().also { result ->
                mutableProviderCredentialPersistenceState.value = result.toPersistenceState()
            }
        }

    private data class CredentialSnapshot(
        val providerApiKeys: Map<String, String>,
        val elevenLabsApiKey: String?,
        /** Provider -> encrypted custom-header block. */
        val customHeaders: Map<String, String>
    )

    private sealed interface CredentialSnapshotResult {
        data class Success(val snapshot: CredentialSnapshot) : CredentialSnapshotResult
        data class Failure(val state: ProviderCredentialPersistenceState) : CredentialSnapshotResult
    }

    private sealed interface CredentialStripResult {
        data class Success(val strippedConfig: LLMConfig) : CredentialStripResult
        data class Failure(val state: ProviderCredentialPersistenceState) : CredentialStripResult
    }

    private class CredentialPersistenceAborted(
        val state: ProviderCredentialPersistenceState
    ) : RuntimeException(null, null, false, false)

    // Auto-reply preference keys
    private val autoReplyGlobalKey = booleanPreferencesKey("auto_reply_global")
    private val autoReplyWhatsAppKey = booleanPreferencesKey("auto_reply_whatsapp")
    private val autoReplySmsKey = booleanPreferencesKey("auto_reply_sms")
    private val autoReplyEmailKey = booleanPreferencesKey("auto_reply_email")
    private val autoReplyDelayKey = intPreferencesKey("auto_reply_delay_minutes")
    private val autoReplyBlacklistKey = stringSetPreferencesKey("auto_reply_blacklist")
    private val autoReplyWhitelistKey = stringSetPreferencesKey("auto_reply_whitelist")
    private val autoReplyCustomPromptKey = stringPreferencesKey("auto_reply_custom_prompt")
    private val autoReplyMaxPerHourKey = intPreferencesKey("auto_reply_max_per_hour")

    val llmConfig: Flow<LLMConfig> = dataStore.data
        .map { preferences -> mergeSecretsForRead(decodeConfig(preferences[llmConfigKey])) }
        .flowOn(Dispatchers.IO)

    val autoReplyConfig: Flow<AutoReplyConfig> = dataStore.data.map { preferences ->
        AutoReplyConfig(
            // Auto-reply is opt-in (see AutoReplyConfig): default OFF until the
            // user explicitly enables each channel.
            globalEnabled = preferences[autoReplyGlobalKey] ?: false,
            whatsappEnabled = preferences[autoReplyWhatsAppKey] ?: false,
            smsEnabled = preferences[autoReplySmsKey] ?: false,
            emailEnabled = preferences[autoReplyEmailKey] ?: false,
            replyDelayMinutes = preferences[autoReplyDelayKey] ?: 15,
            blacklistedContacts = preferences[autoReplyBlacklistKey] ?: emptySet(),
            whitelistedContacts = preferences[autoReplyWhitelistKey] ?: emptySet(),
            customPrompt = preferences[autoReplyCustomPromptKey],
            maxRepliesPerContactPerHour = preferences[autoReplyMaxPerHourKey] ?: 3
        )
    }

    suspend fun updateConfig(
        update: (LLMConfig) -> LLMConfig
    ): ProviderCredentialPersistenceState = withContext(Dispatchers.IO) {
        try {
            dataStore.edit { preferences ->
                val currentConfig = decodeConfig(preferences[llmConfigKey])
                val newConfig = update(mergeSecretsForUpdate(currentConfig))
                when (val result = storeSecretsAndStrip(newConfig)) {
                    is CredentialStripResult.Success -> {
                        preferences[llmConfigKey] = json.encodeToString(result.strippedConfig)
                    }
                    is CredentialStripResult.Failure -> throw CredentialPersistenceAborted(result.state)
                }
            }
            mutableProviderCredentialPersistenceState.value = ProviderCredentialPersistenceState.Ready
            ProviderCredentialPersistenceState.Ready
        } catch (aborted: CredentialPersistenceAborted) {
            mutableProviderCredentialPersistenceState.value = aborted.state
            aborted.state
        } catch (_: IOException) {
            mutableProviderCredentialPersistenceState.value =
                ProviderCredentialPersistenceState.StorageUnavailable
            ProviderCredentialPersistenceState.StorageUnavailable
        } catch (_: SecurityException) {
            mutableProviderCredentialPersistenceState.value =
                ProviderCredentialPersistenceState.StorageUnavailable
            ProviderCredentialPersistenceState.StorageUnavailable
        }
    }

    private fun decodeConfig(configStr: String?): LLMConfig = if (configStr != null) {
        try {
            json.decodeFromString<LLMConfig>(configStr)
        } catch (_: Exception) {
            LLMConfig()
        }
    } else {
        LLMConfig()
    }

    suspend fun saveModelCache(provider: String, models: List<com.opendroid.ai.core.llm.AIModel>) {
        updateConfig { current ->
            val cache = current.modelCache.toMutableMap()
            cache[provider] = models
            val fetchMap = current.lastModelFetch.toMutableMap()
            fetchMap[provider] = System.currentTimeMillis()
            current.copy(modelCache = cache, lastModelFetch = fetchMap)
        }
    }

    suspend fun updateAutoReplyConfig(config: AutoReplyConfig) {
        dataStore.edit { preferences ->
            preferences[autoReplyGlobalKey] = config.globalEnabled
            preferences[autoReplyWhatsAppKey] = config.whatsappEnabled
            preferences[autoReplySmsKey] = config.smsEnabled
            preferences[autoReplyEmailKey] = config.emailEnabled
            preferences[autoReplyDelayKey] = config.replyDelayMinutes
            preferences[autoReplyBlacklistKey] = config.blacklistedContacts
            preferences[autoReplyWhitelistKey] = config.whitelistedContacts
            if (config.customPrompt != null) {
                preferences[autoReplyCustomPromptKey] = config.customPrompt
            } else {
                preferences.remove(autoReplyCustomPromptKey)
            }
            preferences[autoReplyMaxPerHourKey] = config.maxRepliesPerContactPerHour
        }
    }
}
