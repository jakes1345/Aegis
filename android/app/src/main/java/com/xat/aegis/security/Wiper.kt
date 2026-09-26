package com.xat.aegis.security

import android.content.Context
import android.os.Process
import com.xat.aegis.comms.CommsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.security.KeyStore

/**
 * Full data removal. Erases every Aegis data store, then kills the process.
 * Only one wipe can run at a time; once started it cannot be stopped.
 *
 * The wipe order:
 *   1. Stop COMMS services and nuke the relay account (CommsRepository.unpair).
 *   2. Wipe every SharedPreferences file by name.
 *   3. Delete the SQLite databases.
 *   4. Delete identity and voicemail files from filesDir and cacheDir.
 *   5. Remove all AndroidKeyStore aliases this app created.
 *   6. Kill the process — Android will restart it to a blank slate.
 */
object Wiper {

    @Volatile
    var isActive = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Start the wipe. Idempotent. Returns immediately; the process will die. */
    fun start(context: Context) {
        if (isActive) return
        isActive = true
        val app = context.applicationContext
        scope.launch {
            runCatching { CommsRepository.unpair() }
            runCatching { wipePrefs(app) }
            runCatching { wipeDatabases(app) }
            runCatching { wipeFiles(app) }
            runCatching { wipeKeystore() }
            // Last: kill the process so the next launch finds nothing.
            Process.killProcess(Process.myPid())
        }
    }

    private fun wipePrefs(context: Context) {
        val names = listOf(
            "prefs",            // AppSettings
            "comms_identity",   // IdentityStore
            "comms",            // CommsConfig
            "comms_log",        // CommsLog
            "card_vault",       // CardVault.PREFS_NAME (private companion)
            AppLock.PREFS_NAME,
        )
        for (name in names) {
            runCatching { context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
            // Also delete the backing file (data/data/…/shared_prefs/<name>.xml).
            runCatching { File(context.applicationInfo.dataDir, "shared_prefs/$name.xml").delete() }
        }
    }

    private fun wipeDatabases(context: Context) {
        runCatching { context.deleteDatabase("comms_e2ee.db") }
        // Legacy DB name from pre-v3.
        runCatching { context.deleteDatabase("comms.db") }
    }

    private fun wipeFiles(context: Context) {
        // Identity pickle kept in filesDir.
        runCatching { File(context.filesDir, "comms_identity.pickle").delete() }
        // Voicemail and report temp files in cacheDir.
        context.cacheDir.listFiles()?.forEach { f ->
            if (f.name.startsWith("vm_") || f.name.startsWith("aegis_")) {
                runCatching { f.delete() }
            }
        }
    }

    private fun wipeKeystore() {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        val prefixes = listOf("aegis_vault", "aegis_applock", "aegis_comms")
        ks.aliases().asSequence()
            .filter { alias -> prefixes.any { alias.startsWith(it) } }
            .toList()
            .forEach { alias -> runCatching { ks.deleteEntry(alias) } }
    }
}
