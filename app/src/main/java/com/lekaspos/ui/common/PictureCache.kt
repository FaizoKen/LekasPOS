package com.lekaspos.ui.common

import android.graphics.Bitmap
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import com.lekaspos.app.AppGraph
import com.lekaspos.data.product.ProductLookDao
import com.lekaspos.ui.products.Pictures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Product pictures ready to draw (D-066), shared by every screen: a tile scrolled back into view does
 * not read and decode its picture again. Bounded by memory (a 240 px picture is ~115 KB at 16 bits);
 * holds only bitmaps, never a view.
 */
class PictureCache(private val graph: AppGraph) {

    private val cache = object : LruCache<Long, Bitmap>(maxBytes()) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    /** Pictures another till took that have not arrived yet: not looked for again until the catalogue changes. */
    private val missing = HashSet<Long>()

    fun cached(id: Long): Bitmap? = cache.get(id)

    /** The picture [id], read and decoded off the main thread; null while it has not arrived (sync). */
    suspend fun load(id: Long): Bitmap? {
        cache.get(id)?.let { return it }
        synchronized(missing) { if (id in missing) return null }
        val data = graph.db().read { ProductLookDao.imageData(it, id) }
        if (data == null) {
            synchronized(missing) { missing.add(id) }
            return null
        }
        val bitmap = withContext(Dispatchers.Default) { Pictures.decode(data) } ?: return null
        cache.put(id, bitmap)
        return bitmap
    }

    /** Sync brought products, categories or pictures: look again for those that were missing. */
    fun catalogChanged() = synchronized(missing) { missing.clear() }

    /** Android is short of memory. */
    fun trim() = cache.evictAll()

    /**
     * Shows picture [id] in [view] (gone when null). A recycled view keeps only the picture asked last
     * (its tag): a slow read for the tile's earlier product never lands on the new one.
     */
    fun show(view: ImageView, id: Long?, scope: CoroutineScope) {
        view.tag = id
        if (id == null) {
            view.setImageDrawable(null)
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        val hit = cache.get(id)
        if (hit != null) {
            view.setImageBitmap(hit)
            return
        }
        view.setImageDrawable(null)
        scope.launch {
            val bitmap = try {
                load(id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.lekaspos.util.Log.w("A product picture could not be read", e)
                null
            }
            if (bitmap != null && view.tag == id) view.setImageBitmap(bitmap)
        }
    }

    private companion object {
        /** A tenth of the app's memory, at most 10 MB (about 90 pictures). */
        fun maxBytes(): Int = (Runtime.getRuntime().maxMemory() / 10).coerceAtMost(10L * 1024 * 1024).toInt()
    }
}
