package pl.zse.bydgoszcz.elektron.data.repository

import android.content.Context
import android.util.AtomicFile
import pl.zse.bydgoszcz.elektron.data.local.writeAtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pl.zse.bydgoszcz.elektron.data.local.AnnouncementEntity
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** User-selected articles live in filesDir, independently of the excluded Room cache. */
@Singleton
class FavoriteAnnouncementsStore internal constructor(private val file: File?) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "favorite_announcements.json"))
    private var lastSnapshot: List<AnnouncementEntity>? = null
    private var memory: List<AnnouncementEntity>? = null
    suspend fun read(): List<AnnouncementEntity>? = withContext(Dispatchers.IO) {
        if (file == null) return@withContext memory
        if (!file.exists() && !File(file.path + ".bak").exists()) return@withContext null
        val array = JSONArray(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
        (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            fun optional(key: String) = if (item.isNull(key)) null else item.getString(key)
            AnnouncementEntity(item.getString("id"), item.getString("title"), item.getString("url"),
                item.getLong("published"), optional("excerpt"), optional("cover"), optional("html"),
                false, item.getString("source"), true)
        }.also { lastSnapshot = it }
    }
    suspend fun write(entries: List<AnnouncementEntity>) = withContext(Dispatchers.IO) {
        if (file == null) { memory = entries; return@withContext }
        if (entries == lastSnapshot && file.exists()) return@withContext
        val data = JSONArray().apply { entries.forEach { article ->
            put(JSONObject().put("id", article.id).put("title", article.title).put("url", article.url)
                .put("published", article.publishedAtEpochSeconds).put("excerpt", article.excerpt ?: JSONObject.NULL)
                .put("cover", article.coverImageUrl ?: JSONObject.NULL).put("html", article.fullHtml ?: JSONObject.NULL)
                .put("source", article.source))
        } }.toString().toByteArray(Charsets.UTF_8)
        writeAtomicFile(file, data)
        lastSnapshot = entries.toList()
    }
}
