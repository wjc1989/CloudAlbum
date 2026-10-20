package com.cloudalbum.wallpaper.data

import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

object WallpaperActions {

    /** 下载图片到缓存目录，返回临时文件 */
    private fun download(context: Context, url: String): File {
        val request = Request.Builder().url(url).build()
        ImageApi.client.newCall(request).execute().use { res ->
            if (res.code != 200) throw IOException("Download error: ${res.code}")
            val body = res.body ?: throw IOException("Download error: empty body")
            val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
            val ext = url.substringAfterLast('.', "jpg").substringBefore('?').take(4).ifBlank { "jpg" }
            val file = File(dir, "img_${System.currentTimeMillis()}.$ext")
            file.outputStream().use { out -> body.byteStream().copyTo(out) }
            return file
        }
    }

    /** 按屏幕尺寸降采样解码，避免大图 OOM */
    private fun decodeForScreen(context: Context, path: String): Bitmap {
        val dm = context.resources.displayMetrics
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Decode bitmap failed")

        var sample = 1
        val reqW = dm.widthPixels
        val reqH = dm.heightPixels
        if (bounds.outHeight > reqH || bounds.outWidth > reqW) {
            val halfH = bounds.outHeight / 2
            val halfW = bounds.outWidth / 2
            while (halfH / sample >= reqH && halfW / sample >= reqW) sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, opts) ?: throw IOException("Decode bitmap failed")
    }

    /** 设置为锁屏壁纸 */
    suspend fun setLockScreen(context: Context, url: String) = withContext(Dispatchers.IO) {
        val file = download(context, url)
        try {
            val bmp = decodeForScreen(context, file.absolutePath)
            try {
                // minSdk 24，可直接只设置锁屏
                WallpaperManager.getInstance(context)
                    .setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)
            } finally {
                bmp.recycle()
            }
        } finally {
            file.delete()
        }
    }

    /** 保存到系统相册 Pictures/Wallpaper */
    suspend fun saveToAlbum(context: Context, url: String) = withContext(Dispatchers.IO) {
        val file = download(context, url)
        try {
            val name = "wallpaper_${System.currentTimeMillis()}.${file.extension}"
            val mime = when (file.extension.lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "image/jpeg"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Wallpaper")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Create media entry failed")
                try {
                    resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                        ?: throw IOException("Open media stream failed")
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "Wallpaper",
                ).apply { mkdirs() }
                val target = File(dir, name)
                file.copyTo(target, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
            }
        } finally {
            file.delete()
        }
    }
}
