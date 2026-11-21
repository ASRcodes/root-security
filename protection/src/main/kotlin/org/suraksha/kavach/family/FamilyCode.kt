package org.suraksha.kavach.family

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import org.suraksha.core.PassphraseHash
import org.suraksha.kavach.data.SecretStore

class FamilyCode(private val secrets: SecretStore) {
    private val changes = Mutex()
    private val generation = AtomicLong()
    suspend fun configured(): Boolean = withContext(Dispatchers.IO) {
        secrets.read(KEY)?.let { it.fill(0); true } ?: false
    }
    suspend fun save(input: String): Boolean = withContext(Dispatchers.Default) {
        val token = generation.get()
        if (!PassphraseHash.valid(input)) return@withContext false
        val record = PassphraseHash.create(input).toByteArray()
        try {
            changes.withLock {
                if (token != generation.get()) return@withLock false
                withContext(Dispatchers.IO) { secrets.write(KEY, record) }
                true
            }
        } finally { record.fill(0) }
    }
    suspend fun reset() {
        generation.incrementAndGet()
        changes.withLock { withContext(Dispatchers.IO) { secrets.remove(KEY) } }
    }
    suspend fun matches(input: String): Boolean = withContext(Dispatchers.Default) {
        val record = withContext(Dispatchers.IO) { secrets.read(KEY) } ?: return@withContext false
        try { PassphraseHash.matches(input, record.toString(Charsets.UTF_8)) } finally { record.fill(0) }
    }
    companion object { private const val KEY = "family_code_hash_v1" }
}
