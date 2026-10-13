package pl.zse.bydgoszcz.elektron.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavoritesMigrationTest {
    @Test fun rc4DatabaseUpgradesWithoutLosingArticle() = runBlocking {
        verifyMigration(2)
    }

    @Test fun versionFiveUpgradesToSixWithoutLosingArticle() = runBlocking {
        verifyMigration(5)
    }

    private suspend fun verifyMigration(version: Int) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-favorites.db"
        ctx.deleteDatabase(name)
        try {
            val schema = JSONObject(javaClass.getResource("/pl.zse.bydgoszcz.elektron.data.local.AppDatabase/$version.json")!!.readText())
                .getJSONObject("database")
            ctx.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                    val indices = entity.getJSONArray("indices")
                    for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql")
                        .replace("\${TABLE_NAME}", entity.getString("tableName")))
                }
                old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                old.execSQL("INSERT INTO room_master_table VALUES(42, ?)", arrayOf(schema.getString("identityHash")))
                old.execSQL("INSERT INTO announcements (id, title, url, publishedAtEpochSeconds, fullHtml, isRead, source) VALUES ('saved', 'Tytuł', 'https://zse/a', 1800000000, '<p>Treść</p>', 0, 'RSS_NEWS')")
                old.version = version
            }
            val upgraded = Room.databaseBuilder(ctx, AppDatabase::class.java, name)
                .allowMainThreadQueries().addMigrations(*DatabaseMigrations.ALL).build()
            try {
                val article = upgraded.announcementDao().getById("saved")!!
                assertEquals("<p>Treść</p>", article.fullHtml)
                assertFalse(article.isFavorite)
                upgraded.announcementDao().toggleFavorite("saved")
                assertTrue(upgraded.announcementDao().getById("saved")!!.isFavorite)
                // Opening Room validates every entity and index, including the new grades table.
                assertEquals(DATABASE_VERSION, upgraded.openHelper.writableDatabase.version)
                assertTrue(upgraded.gradeDao().getAll().isEmpty())
            } finally { upgraded.close() }
        } finally { ctx.deleteDatabase(name) }
    }
}
