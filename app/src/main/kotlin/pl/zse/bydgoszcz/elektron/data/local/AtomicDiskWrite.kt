package pl.zse.bydgoszcz.elektron.data.local

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Keep the old file until a synced replacement can be committed, and report commit failures. */
internal fun writeAtomicFile(file: File, data: ByteArray) {
    val parent = file.parentFile ?: throw IOException("Plik nie ma katalogu nadrzędnego")
    if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Nie można utworzyć katalogu zapisu")
    // Recover backups created by older AtomicFile implementations before replacing the base.
    if (File(file.path + ".bak").exists()) AtomicFile(file).openRead().close()
    val pending = File(file.path + ".new")
    try {
        FileOutputStream(pending).use { output -> output.write(data); output.fd.sync() }
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (error: Exception) {
        pending.delete()
        throw error
    }
}
