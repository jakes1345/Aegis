package com.xat.aegis.security

import android.content.Context
import android.os.Process
import com.xat.aegis.comms.CommsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.KeyStore

/**
 * Full data removal. Erases every Aegis data store, then kills the process.
 * Only one wipe can run at a time; once started it cannot be stopped.
 *
 * Nothing here works from a list of what the app is known to store: the
 * stores are enumerated (every Keystore alias, every preferences file, every
 * database, everything under the private and external app directories), so a
 * store added later is wiped without anyone remembering to add it here.
 *
 * The wipe order:
 *   1. Ask the relay to delete this phone's account (CommsRepository.unpair),
 *      given a few seconds: it needs the identity keys that go next, but the
 *      network must never hold up the wipe of what is on the phone.
 *   2. Delete every AndroidKeyStore key. From here on, whatever survives
 *      below by accident (the vault, cached message bodies, the identity
 *      pickle) is ciphertext nobody holds a key to.
 *   3. Clear and delete every SharedPreferences file.
 *   4. Delete every SQLite database.
 *   5. Delete everything under filesDir, cacheDir, code_cache, no_backup, any
 *      other private directory except the native-library link, and the
 *      external files and cache directories (osmdroid's map tiles live there,
 *      and tiles are a record of where the owner has been).
 *   6. Kill the process — Android restarts it to a blank slate.
 *
 * [isActive] is also read by the stores that buffer writes ([com.xat.aegis.Store]):
 * a flush after step 5 would put a plaintext timeline straight back on disk.
 */
object Wiper {

    @Volatile
    var isActive = false
        private set

    /** How long the relay gets to acknowledge before the local wipe goes ahead without it. */
    private const val RELAY_BUDGET_MS = 6_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Start the wipe. Idempotent. Returns immediately; the process will die. */
    fun start(context: Context) {
        synchronized(this) {
            if (isActive) return
            isActive = true
        }
        val app = context.applicationContext
        scope.launch {
            try {
                val remote = launch { runCatching { CommsRepository.unpair() } }
                withTimeoutOrNull(RELAY_BUDGET_MS) { remote.join() }
                runCatching { wipeKeystore() }
                runCatching { wipePrefs(app) }
                runCatching { wipeDatabases(app) }
                runCatching { wipeFiles(app) }
            } finally {
                // Last, and whatever happened above: the next launch finds nothing.
                Process.killProcess(Process.myPid())
            }
        }
    }

    /** Every key in the app's Keystore namespace: the vault's, the lock's, the biometric one, COMMS'. */
    private fun wipeKeystore() {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        for (alias in ks.aliases().toList()) {
            runCatching { ks.deleteEntry(alias) }
        }
    }

    private fun wipePrefs(context: Context) {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        val onDisk = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".xml") }
            ?.map { it.name.removeSuffix(".xml") }
            ?: emptyList()
        // Named as well as found: an instance already loaded in this process keeps
        // its map in memory and would write it back over a deleted file.
        val known = listOf(
            "prefs",            // AppSettings, onboarding
            "comms_identity",   // IdentityStore
            "comms",            // CommsConfig
            "comms_log",        // CommsLog
            "comms_ui",         // CommsScreen
            "card_vault",       // CardVault
            "osmdroid",         // map configuration
            AppLock.PREFS_NAME,
        )
        for (name in (onDisk + known).toSet()) {
            runCatching { context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
        }
        runCatching { dir.deleteRecursively() }
    }

    private fun wipeDatabases(context: Context) {
        val names = context.databaseList().toList() + listOf("comms_e2ee.db", "comms.db")
        for (name in names.toSet()) {
            runCatching { context.deleteDatabase(name) }
        }
        runCatching { File(context.applicationInfo.dataDir, "databases").deleteRecursively() }
    }

    private fun wipeFiles(context: Context) {
        val roots = ArrayList<File>()
        roots += context.filesDir
        roots += context.cacheDir
        roots += context.codeCacheDir
        roots += context.noBackupFilesDir
        context.getExternalFilesDirs(null)?.filterNotNull()?.let { roots += it }
        context.externalCacheDirs?.filterNotNull()?.let { roots += it }
        // Whatever else lives in the private data directory (getDir() folders,
        // WebView caches), apart from the link to the APK's native libraries.
        File(context.applicationInfo.dataDir).listFiles()
            ?.filter { it.name !in setOf("lib", "shared_prefs", "databases") }
            ?.let { roots += it }
        for (root in roots.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) }) {
            runCatching {
                val children = root.listFiles() ?: return@runCatching
                for (child in children) runCatching { child.deleteRecursively() }
            }
        }
    }
}
