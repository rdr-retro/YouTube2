package com.rdr.youtube2

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.Formatter
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadsActivity : AppCompatActivity() {

    private data class DownloadItem(
        val uri: Uri,
        val title: String,
        val sizeBytes: Long,
        val dateAddedSeconds: Long
    )

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var adapter: DownloadsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        recyclerView = findViewById(R.id.downloads_recycler)
        emptyText = findViewById(R.id.downloads_empty)

        findViewById<Button>(R.id.downloads_back_button).setOnClickListener {
            finish()
        }

        adapter = DownloadsAdapter(::openDownloadItem)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        loadDownloads()
    }

    private fun loadDownloads() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { queryDownloads() }
            adapter.submit(items)
            emptyText.text = if (items.isEmpty()) {
                "No hay videos descargados"
            } else {
                ""
            }
        }
    }

    private fun queryDownloads(): List<DownloadItem> {
        val projection = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED
        )

        val selection: String
        val selectionArgs: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.Video.Media.RELATIVE_PATH)
            selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            selectionArgs = arrayOf("Movies/Youtube2%")
        } else {
            @Suppress("DEPRECATION")
            val dataColumn = MediaStore.Video.Media.DATA
            projection.add(dataColumn)
            selection = "$dataColumn LIKE ?"
            selectionArgs = arrayOf("%/Movies/Youtube2/%")
        }

        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        val items = mutableListOf<DownloadItem>()

        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection.toTypedArray(),
                selection,
                selectionArgs,
                sort
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    val title = cursor.getString(nameIdx).orEmpty()
                    val size = cursor.getLong(sizeIdx)
                    val dateAdded = cursor.getLong(dateIdx)
                    items.add(
                        DownloadItem(
                            uri = uri,
                            title = title,
                            sizeBytes = size,
                            dateAddedSeconds = dateAdded
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
            return emptyList()
        }
        return items
    }

    private fun openDownloadItem(item: DownloadItem) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(this, "No hay app para abrir este video", Toast.LENGTH_SHORT).show()
        }
    }

    private inner class DownloadsAdapter(
        private val onClick: (DownloadItem) -> Unit
    ) : RecyclerView.Adapter<DownloadsAdapter.DownloadViewHolder>() {

        private val items = mutableListOf<DownloadItem>()

        fun submit(newItems: List<DownloadItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): DownloadViewHolder {
            val view = layoutInflater.inflate(R.layout.item_download_video, parent, false)
            return DownloadViewHolder(view)
        }

        override fun onBindViewHolder(holder: DownloadViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class DownloadViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
            private val title = itemView.findViewById<TextView>(R.id.download_item_title)
            private val meta = itemView.findViewById<TextView>(R.id.download_item_meta)

            fun bind(item: DownloadItem) {
                title.text = item.title
                val sizeText = Formatter.formatFileSize(this@DownloadsActivity, item.sizeBytes)
                val dateText = formatDate(item.dateAddedSeconds)
                meta.text = "$sizeText · $dateText"
                itemView.setOnClickListener { onClick(item) }
            }
        }
    }

    private fun formatDate(seconds: Long): String {
        if (seconds <= 0L) return "N/A"
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return formatter.format(Date(seconds * 1000L))
    }
}
