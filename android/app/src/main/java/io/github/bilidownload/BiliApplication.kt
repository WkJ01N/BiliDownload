package io.github.bilidownload

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class BiliApplication : Application() {
    override fun onCreate() {
        super.onCreate(); app = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("downloads", "视频下载", NotificationManager.IMPORTANCE_LOW))
        session = Session(this); api = BiliApi(session); store = TaskStore(this); engine = DownloadEngine(this)
    }
    companion object {
        lateinit var app: BiliApplication; private set
        lateinit var session: Session; private set
        lateinit var api: BiliApi; private set
        lateinit var store: TaskStore; private set
        lateinit var engine: DownloadEngine; private set
    }
}
class TaskStore(context: Context) : SQLiteOpenHelper(context, "tasks.db", null, 1) {
    private val gson = Gson()
    private val mutable = MutableStateFlow<List<DownloadEntry>>(emptyList())
    val entries = mutable.asStateFlow()
    init {
        val list = mutableListOf<DownloadEntry>()
        readableDatabase.rawQuery("SELECT data FROM tasks ORDER BY rowid DESC", null).use { cursor ->
            while (cursor.moveToNext()) {
                val entry = gson.fromJson(cursor.getString(0), DownloadEntry::class.java)
                list += if (entry.state in listOf("Queued", "Downloading", "Merging")) entry.copy(state = "Paused", message = "上次下载已暂停，可继续") else entry
            }
        }
        mutable.value = list
    }
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE tasks (id TEXT PRIMARY KEY, data TEXT NOT NULL)") }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun put(entry: DownloadEntry) {
        val values = ContentValues().apply { put("id", entry.id); put("data", gson.toJson(entry)) }
        writableDatabase.insertWithOnConflict("tasks", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        val previous = mutable.value
        mutable.value = if(previous.any { it.id == entry.id }) previous.map { if(it.id == entry.id) entry else it } else listOf(entry) + previous
    }
    @Synchronized fun get(id: String) = mutable.value.firstOrNull { it.id == id }
    @Synchronized fun update(id: String, change: (DownloadEntry) -> DownloadEntry) { get(id)?.let { put(change(it)) } }
}
