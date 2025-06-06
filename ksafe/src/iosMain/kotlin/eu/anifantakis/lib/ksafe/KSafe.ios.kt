package eu.anifantakis.lib.ksafe

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.providers.openssl3.Openssl3
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okio.Path.Companion.toPath
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*
import platform.posix.memcpy
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

@OptIn(ExperimentalEncodingApi::class)
private fun encodeBase64(bytes: ByteArray): String = Base64.encode(bytes)

@OptIn(ExperimentalEncodingApi::class)
private fun decodeBase64(encoded: String): ByteArray = Base64.decode(encoded)

actual class KSafe {

    companion object Companion {
        private const val KEY_SIZE = 32 // 256 bits for AES-256
        private const val SERVICE_NAME = "eu.anifantakis.ksafe"
        private const val KEY_PREFIX = "eu.anifantakis.ksafe."
        private const val INSTALLATION_ID_KEY = "ksafe_installation_id"
    }

    @PublishedApi
    internal val json = Json { ignoreUnknownKeys = true }

    // Create DataStore using a file in the app's Documents directory.
    @OptIn(ExperimentalForeignApi::class)
    @PublishedApi
    internal val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath(
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        produceFile = {
            val docDir: NSURL? = NSFileManager.defaultManager.URLForDirectory(
                directory = NSDocumentDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = false,
                error = null
            )
            requireNotNull(docDir).path.plus("/eu_anifantakis_ksafe_datastore.preferences_pb").toPath()
        }
    )

    // Track if cleanup has been performed
    private var cleanupPerformed = false

    init {
        // Force registration of the Apple provider.
        registerAppleProvider()
        forceAesGcmRegistration()
    }

    private fun registerAppleProvider() {
        // This explicitly registers the Apple provider with the default CryptographyProvider.
        CryptographyProvider.Openssl3
    }

    private fun forceAesGcmRegistration() {
        // Dummy reference to ensure AES.GCM is not stripped.
        @Suppress("UNUSED_VARIABLE")
        val dummy = AES.GCM
    }

    /**
     * Ensures cleanup is performed once. This is called lazily on first access.
     */
    suspend fun ensureCleanupPerformed() {
        if (!cleanupPerformed) {
            cleanupPerformed = true
            try {
                cleanupOrphanedKeychainEntries()
            } catch (e: Exception) {
                // Log error but don't crash the app
                println("KSafe: Failed to cleanup orphaned keychain entries: ${e.message}")
            }
        }
    }

    /**
     * Gets or creates a unique installation ID. This helps us detect fresh installs.
     */
    private suspend fun getOrCreateInstallationId(): String {
        val installationIdKey = stringPreferencesKey(INSTALLATION_ID_KEY)
        val currentId = dataStore.data.map { it[installationIdKey] }.first()

        if (currentId != null) {
            return currentId
        }

        // Generate new installation ID
        val newId = generateInstallationId()
        dataStore.edit { preferences ->
            preferences[installationIdKey] = newId
        }

        return newId
    }

    private fun generateInstallationId(): String {
        val bytes = ByteArray(16)
        Random.nextBytes(bytes)
        return encodeBase64(bytes)
    }

    /**
     * Clean up orphaned Keychain entries from previous installations
     */
    private suspend fun cleanupOrphanedKeychainEntries() {
        val installationId = getOrCreateInstallationId()

        // Get all keys that have markers in DataStore
        val validKeys = mutableSetOf<String>()
        val preferences = dataStore.data.first()

        preferences.asMap().forEach { (key, _) ->
            if (key.name.startsWith("encrypted_")) {
                val keyId = key.name.removePrefix("encrypted_")
                validKeys.add(keyId)
            }
        }

        // Find and remove all Keychain entries that don't have corresponding data
        removeOrphanedKeychainKeys(validKeys)
    }

    /**
     * Remove Keychain entries that don't have corresponding encrypted data in DataStore
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun removeOrphanedKeychainKeys(validKeys: Set<String>) {
        memScoped {
            // Query for all our Keychain items
            val query = CFDictionaryCreateMutable(
                kCFAllocatorDefault,
                0,
                null,
                null
            ).apply {
                CFDictionarySetValue(this, kSecClass, kSecClassGenericPassword)
                CFDictionarySetValue(this, kSecAttrService, CFBridgingRetain(SERVICE_NAME))
                CFDictionarySetValue(this, kSecReturnAttributes, kCFBooleanTrue)
                CFDictionarySetValue(this, kSecMatchLimit, kSecMatchLimitAll)
            }

            val resultRef = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, resultRef.ptr)
            CFRelease(query as CFTypeRef?)

            if (status == errSecSuccess) {
                val items = CFBridgingRelease(resultRef.value) as? NSArray
                items?.let { array ->
                    for (i in 0 until array.count.toInt()) {
                        val dict = array.objectAtIndex(i.toULong()) as? NSDictionary
                        val account = dict?.objectForKey(kSecAttrAccount as Any) as? String
                        if (account != null && account.startsWith(KEY_PREFIX)) {
                            val keyId = account.removePrefix(KEY_PREFIX)
                            if (keyId !in validKeys) deleteKeychainKey(keyId)
                        }
                    }
                }
            }
        }
    }

    // ----- Unencrypted Storage Functions -----
    @PublishedApi
    internal suspend inline fun <reified T> getUnencrypted(key: String, defaultValue: T): T {
        // Ensure cleanup on first access
        ensureCleanupPerformed()

        val preferencesKey: Preferences.Key<Any> = when (defaultValue) {
            is Boolean -> booleanPreferencesKey(key)
            is Int -> intPreferencesKey(key)
            is Float -> floatPreferencesKey(key)
            is Long -> longPreferencesKey(key)
            is String -> stringPreferencesKey(key)
            is Double -> doublePreferencesKey(key)
            else -> stringPreferencesKey(key)
        } as Preferences.Key<Any>

        return dataStore.data.map { preferences ->
            val storedValue = preferences[preferencesKey]
            when (defaultValue) {
                is Boolean -> (storedValue as? Boolean ?: defaultValue) as T
                is Int -> {
                    when (storedValue) {
                        is Int -> storedValue as T
                        is Long -> if (storedValue in Int.MIN_VALUE..Int.MAX_VALUE) storedValue.toInt() as T else defaultValue
                        else -> defaultValue
                    }
                }

                is Long -> {
                    when (storedValue) {
                        is Long -> storedValue as T
                        is Int -> storedValue.toLong() as T
                        else -> defaultValue
                    }
                }

                is Float -> (storedValue as? Float ?: defaultValue) as T
                is String -> (storedValue as? String ?: defaultValue) as T
                is Double -> (storedValue as? Double ?: defaultValue) as T
                else -> {
                    val jsonString = storedValue as? String ?: return@map defaultValue
                    try {
                        json.decodeFromString(serializer<T>(), jsonString)
                    } catch (e: Exception) {
                        defaultValue
                    }
                }
            }
        }.first()
    }

    @PublishedApi
    internal suspend inline fun <reified T> putUnencrypted(key: String, value: T) {
        // Ensure cleanup on first access
        ensureCleanupPerformed()

        val preferencesKey: Preferences.Key<Any> = when (value) {
            is Boolean -> booleanPreferencesKey(key)
            is Number -> {
                when {
                    value is Int || (value is Long && value in Int.MIN_VALUE..Int.MAX_VALUE) ->
                        intPreferencesKey(key)
                    value is Long -> longPreferencesKey(key)
                    value is Float -> floatPreferencesKey(key)
                    value is Double -> doublePreferencesKey(key)
                    else -> stringPreferencesKey(key)
                }
            }
            is String -> stringPreferencesKey(key)
            else -> stringPreferencesKey(key)
        } as Preferences.Key<Any>

        val storedValue: Any = when (value) {
            is Boolean -> value
            is Number -> {
                when {
                    value is Int || (value is Long && value in Int.MIN_VALUE..Int.MAX_VALUE) -> value.toInt()
                    else -> value
                }
            }
            is String -> value
            else -> json.encodeToString(serializer<T>(), value)
        }

        dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[preferencesKey] = storedValue
            }
        }
    }

    // ----- iOS Keychain Encryption Functions -----

    private fun encryptedPrefKey(key: String) = stringPreferencesKey("encrypted_$key")

    /**
     * Gets or creates an encryption key from iOS Keychain
     */
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    @PublishedApi
    internal fun getOrCreateKeychainKey(keyId: String): ByteArray {
        val account = KEY_PREFIX + keyId

        // First try to retrieve existing key
        memScoped {
            val query = CFDictionaryCreateMutable(
                kCFAllocatorDefault,
                0,
                null,
                null
            ).apply {
                CFDictionarySetValue(this, kSecClass, kSecClassGenericPassword)
                CFDictionarySetValue(this, kSecAttrService, CFBridgingRetain(SERVICE_NAME))
                CFDictionarySetValue(this, kSecAttrAccount, CFBridgingRetain(account))
                CFDictionarySetValue(this, kSecReturnData, kCFBooleanTrue)
                CFDictionarySetValue(this, kSecMatchLimit, kSecMatchLimitOne)
            }

            val resultRef = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, resultRef.ptr)
            CFRelease(query as CFTypeRef?)

            if (status == errSecSuccess) {
                val data = CFBridgingRelease(resultRef.value) as NSData
                return data.toByteArray()
            }
        }

        // Key doesn't exist, generate new one
        val newKey = ByteArray(KEY_SIZE)
        Random.nextBytes(newKey)

        // Store in keychain
        memScoped {
            val keyData = NSData.create(
                bytes = newKey.refTo(0).getPointer(this),
                length = newKey.size.toULong()
            )

            val addQuery = CFDictionaryCreateMutable(
                kCFAllocatorDefault,
                0,
                null,
                null
            ).apply {
                CFDictionarySetValue(this, kSecClass, kSecClassGenericPassword)
                CFDictionarySetValue(this, kSecAttrService, CFBridgingRetain(SERVICE_NAME))
                CFDictionarySetValue(this, kSecAttrAccount, CFBridgingRetain(account))
                CFDictionarySetValue(this, kSecValueData, CFBridgingRetain(keyData))
                CFDictionarySetValue(this, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
            }

            SecItemAdd(addQuery, null)
            CFRelease(addQuery as CFTypeRef?)
        }

        return newKey
    }

    /**
     * Deletes a key from iOS Keychain
     */
    @OptIn(ExperimentalForeignApi::class)
    @PublishedApi
    internal fun deleteKeychainKey(keyId: String) {
        val account = KEY_PREFIX + keyId

        memScoped {
            val query = CFDictionaryCreateMutable(
                kCFAllocatorDefault,
                0,
                null,
                null
            ).apply {
                CFDictionarySetValue(this, kSecClass, kSecClassGenericPassword)
                CFDictionarySetValue(this, kSecAttrService, CFBridgingRetain(SERVICE_NAME))
                CFDictionarySetValue(this, kSecAttrAccount, CFBridgingRetain(account))
            }

            SecItemDelete(query)
            CFRelease(query as CFTypeRef?)
        }
    }

    /**
     * Extension function to convert NSData to ByteArray
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun NSData.toByteArray(): ByteArray {
        return ByteArray(this.length.toInt()).apply {
            usePinned {
                memcpy(it.addressOf(0), this@toByteArray.bytes, this@toByteArray.length)
            }
        }
    }

    suspend fun storeEncryptedData(key: String, data: ByteArray) {
        val encoded = encodeBase64(data)
        dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[encryptedPrefKey(key)] = encoded
            }
        }
    }

    suspend fun loadEncryptedData(key: String): ByteArray? {
        val stored = dataStore.data.map { it[encryptedPrefKey(key)] }.first()
        return stored?.let { decodeBase64(it) }
    }

    suspend inline fun <reified T> putEncrypted(key: String, value: T) {
        // Ensure cleanup on first access
        ensureCleanupPerformed()

        val jsonString = json.encodeToString(serializer<T>(), value)
        val plaintext = jsonString.encodeToByteArray()

        // Get key from Keychain
        val keychainKey = getOrCreateKeychainKey(key)

        // Use whyoleg cryptography with the Keychain key
        val aesGcm = obtainAesGcm()
        val symmetricKey = aesGcm.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, keychainKey)
        val cipher = symmetricKey.cipher()
        val ciphertext = cipher.encrypt(plaintext = plaintext)

        storeEncryptedData(key, ciphertext)
    }

    suspend inline fun <reified T> getEncrypted(key: String, defaultValue: T): T {
        // Ensure cleanup on first access
        ensureCleanupPerformed()

        val ciphertext = loadEncryptedData(key) ?: return defaultValue

        return try {
            // Get key from Keychain
            val keychainKey = getOrCreateKeychainKey(key)

            // Use whyoleg cryptography with the Keychain key
            val aesGcm = obtainAesGcm()
            val symmetricKey = aesGcm.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, keychainKey)
            val cipher = symmetricKey.cipher()
            val decryptedBytes = cipher.decrypt(ciphertext = ciphertext)
            val jsonString = decryptedBytes.decodeToString()
            json.decodeFromString(serializer<T>(), jsonString)
        } catch (e: Exception) {
            // If decryption fails, return default value
            defaultValue
        }
    }

    actual suspend inline fun <reified T> get(key: String, defaultValue: T, encrypted: Boolean): T {
        return if (encrypted) {
            getEncrypted(key, defaultValue)
        }
        else {
            getUnencrypted(key, defaultValue)
        }
    }

    actual inline fun <reified T> getDirect(key: String, defaultValue: T, encrypted: Boolean): T {
        return runBlocking {
            get(key, defaultValue, encrypted)
        }
    }

    actual suspend inline fun <reified T> put(key: String, value: T, encrypted: Boolean) {
        if (encrypted) {
            putEncrypted(key, value)
        } else {
            putUnencrypted(key, value)
        }
    }

    actual inline fun <reified T> putDirect(key: String, value: T, encrypted: Boolean): Unit {
        CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            put(key, value, encrypted)
        }
    }

    /**
     * Deletes a value from DataStore.
     *
     * @param key The key of the value to delete.
     */
    actual suspend fun delete(key: String) {
        val dataKey = stringPreferencesKey(key)
        dataStore.edit { preferences ->
            preferences.remove(dataKey)
        }

        // Also delete the encryption key from Keychain
        deleteKeychainKey(key)
    }

    /**
     * Deletes a value from DataStore without using coroutines.
     * This function is **non-blocking**.
     *
     * @param key The key of the value to delete.
     */
    actual fun deleteDirect(key: String) {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            delete(key)
        }
    }

    /**
     * Clear all data including Keychain entries.
     * Useful for complete cleanup or testing.
     */
    suspend fun clearAll() {
        // Get all encrypted keys before clearing
        val encryptedKeys = mutableSetOf<String>()
        val preferences = dataStore.data.first()

        preferences.asMap().forEach { (key, _) ->
            if (key.name.startsWith("encrypted_")) {
                val keyId = key.name.removePrefix("encrypted_")
                encryptedKeys.add(keyId)
            }
        }

        // Clear all DataStore preferences
        dataStore.edit { it.clear() }

        // Delete all associated Keychain entries
        encryptedKeys.forEach { keyId ->
            deleteKeychainKey(keyId)
        }
    }
}

// iOS: non-inline helper to get the AES-GCM algorithm instance.
fun obtainAesGcm(): AES.GCM {
    return CryptographyProvider.Openssl3.get(AES.GCM)
}