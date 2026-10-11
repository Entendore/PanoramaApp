package com.example.panoramaapp

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.DateFormat
import java.util.Date
import kotlin.math.max

/** Bottom sheet listing panoramas saved by this app. Async loading + multi-select delete. */
class GallerySheet(
    private val activity: AppCompatActivity,
    private val onOpen: (Uri) -> Unit,
    private val onDeleted: (Uri) -> Unit
) {
    data class Item(val uri: Uri, val dateAdded: Long)

    private var dialog: BottomSheetDialog? = null
    private var dismissed = false

    fun show() {
        val view = activity.layoutInflater.inflate(R.layout.sheet_gallery, null)
        val rv = view.findViewById<RecyclerView>(R.id.rvPanos)
        val tvEmpty = view.findViewById<TextView>(R.id.tvEmpty)
        val tvTitle = view.findViewById<TextView>(R.id.tvTitle)
        val btnSelect = view.findViewById<View>(R.id.btnSelect)
        val llSelection = view.findViewById<View>(R.id.llSelection)
        val tvSelCount = view.findViewById<TextView>(R.id.tvSelCount)
        val btnCancelSel = view.findViewById<View>(R.id.btnCancelSel)
        val btnDeleteSel = view.findViewById<View>(R.id.btnDeleteSel)

        val adapter = Adapter(
            onSelectionChanged = { count ->
                if (count > 0) {
                    llSelection.visibility = View.VISIBLE
                    tvTitle.visibility = View.GONE
                    btnSelect.visibility = View.GONE
                    tvSelCount.text = activity.getString(R.string.gallery_n_selected, count)
                } else {
                    llSelection.visibility = View.GONE
                    tvTitle.visibility = View.VISIBLE
                    btnSelect.visibility = View.VISIBLE
                }
            },
            onCountChanged = { total -> tvEmpty.visibility = if (total == 0) View.VISIBLE else View.GONE },
            onOpenClick = { onOpen(it); dialog?.dismiss() }
        )
        rv.layoutManager = GridLayoutManager(activity, 2)
        rv.adapter = adapter

        btnSelect.setOnClickListener { adapter.enterSelection() }
        btnCancelSel.setOnClickListener { adapter.exitSelection() }
        btnDeleteSel.setOnClickListener {
            val targets = adapter.selectionSnapshot()
            if (targets.isEmpty()) return@setOnClickListener
            val n = targets.size
            MaterialAlertDialogBuilder(activity)
                .setTitle(activity.resources.getQuantityString(R.plurals.gallery_delete_n, n, n))
                .setMessage(activity.getString(R.string.gallery_delete_confirm))
                .setPositiveButton(activity.getString(R.string.gallery_delete)) { _, _ ->
                    activity.lifecycleScope.launch {
                        val deleted = withContext(Dispatchers.IO) {
                            targets.mapNotNull { u ->
                                try { if (activity.contentResolver.delete(u, null, null) > 0) u else null }
                                catch (_: Exception) { null }
                            }
                        }
                        adapter.removeUris(deleted)
                        deleted.forEach { onDeleted(it) }
                        if (deleted.size < n) {
                            Toast.makeText(activity,
                                activity.getString(R.string.gallery_delete_partial), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton(activity.getString(R.string.btn_cancel), null)
                .show()
        }

        dialog = BottomSheetDialog(activity).apply {
            setOnDismissListener { dismissed = true }
            setContentView(view)
            show()
        }

        activity.lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { loadItems() }
            if (dismissed) return@launch
            adapter.replaceAll(items)
        }
    }

    private fun loadItems(): MutableList<Item> {
        val out = mutableListOf<Item>()
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
        activity.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("PANO%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                out += Item(
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol)),
                    c.getLong(dateCol)
                )
            }
        }
        return out
    }

    private fun loadThumb(uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.contentResolver.loadThumbnail(uri, Size(256, 256), null)
        } else decodeSampled(uri, 256)
    } catch (e: Exception) {
        decodeSampled(uri, 256)
    }

    private fun decodeSampled(uri: Uri, req: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= req) sample *= 2
        activity.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Exception) {
        null
    }

    private inner class Adapter(
        private val onSelectionChanged: (Int) -> Unit,
        private val onCountChanged: (Int) -> Unit,
        private val onOpenClick: (Uri) -> Unit
    ) : RecyclerView.Adapter<Adapter.VH>() {

        val items = mutableListOf<Item>()
        private val selected = mutableSetOf<Uri>()
        private var selectionMode = false

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val iv: ImageView = v.findViewById(R.id.ivThumb)
            val tv: TextView = v.findViewById(R.id.tvDate)
            val check: View = v.findViewById(R.id.vCheck)
        }

        fun replaceAll(list: List<Item>) {
            items.clear(); items.addAll(list)
            exitSelection()
            notifyDataSetChanged()
            onCountChanged(items.size)
        }

        fun enterSelection(uri: Uri? = null) {
            selectionMode = true
            uri?.let { selected.add(it) }
            notifyDataSetChanged()
            onSelectionChanged(selected.size)
        }

        fun exitSelection() {
            selectionMode = false
            selected.clear()
            notifyDataSetChanged()
            onSelectionChanged(0)
        }

        fun toggle(uri: Uri) {
            if (!selected.add(uri)) selected.remove(uri)
            if (selected.isEmpty()) exitSelection() else {
                notifyDataSetChanged()
                onSelectionChanged(selected.size)
            }
        }

        fun selectionSnapshot(): Set<Uri> = selected.toSet()

        fun removeUris(uris: Collection<Uri>) {
            items.removeAll { it.uri in uris }
            selectionMode = false
            selected.clear()
            notifyDataSetChanged()
            onSelectionChanged(0)
            onCountChanged(items.size)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_pano, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val item = items[position]
            val isSelected = item.uri in selected
            h.iv.alpha = if (isSelected) 0.35f else 1f
            h.check.visibility = if (isSelected) View.VISIBLE else View.GONE
            h.tv.text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(item.dateAdded * 1000))

            h.iv.tag = item.uri
            activity.lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { loadThumb(item.uri) }
                if (!dismissed && h.iv.tag == item.uri && bmp != null) {
                    h.iv.setImageBitmap(bmp)
                }
            }

            h.iv.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (selectionMode) toggle(items[pos].uri) else onOpenClick(items[pos].uri)
            }
            h.iv.setOnLongClickListener {
                val pos = h.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && !selectionMode) enterSelection(items[pos].uri)
                true
            }
        }
    }
}
