package com.esct.album.data

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
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

    enum class Target(val flags: Int) {
        HOME(WallpaperManager.FLAG_SYSTEM),
        LOCK(WallpaperManager.FLAG_LOCK),
        BOTH(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK),
    }

    /** 待机界面（屏保 StandbyDreamService）显示的图片 */
    fun standbyFile(context: Context) = File(context.filesDir, "standby_wallpaper")

    // 华为锁屏显示的是主题锁屏图，不读取 FLAG_LOCK 壁纸，需要交给系统组件设置
    private val HUAWEI_WALLPAPER = ComponentName("com.huawei.photos", "com.android.gallery3d.app.Wallpaper")
    private val HUAWEI_AOD = ComponentName("com.huawei.aod", "com.huawei.aod.PreviewPersonalityPictureActivityAlias")

    private fun attachIntent(context: Context, component: ComponentName): Intent? {
        val intent = Intent(Intent.ACTION_ATTACH_DATA).setComponent(component)
            .setType("image/*")
        return intent.takeIf { context.packageManager.resolveActivity(it, 0) != null }
    }

    /** 当前设备是否支持设置华为息屏显示图片 */
    fun supportsAod(context: Context) = attachIntent(context, HUAWEI_AOD) != null

    /** 复制到可分享目录，返回带读取授权的 ATTACH_DATA intent */
    private fun shareTo(context: Context, file: File, component: ComponentName): Intent? {
        val intent = attachIntent(context, component) ?: return null
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val shared = File(dir, "wallpaper_${System.currentTimeMillis()}.${file.extension}")
        file.copyTo(shared, overwrite = true)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shared)
        return intent.setDataAndType(uri, "image/*")
            .putExtra("mimeType", "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 设置华为息屏显示图片，返回需要启动的系统页面 */
    suspend fun setAod(context: Context, url: String): Intent = withContext(Dispatchers.IO) {
        val file = download(context, url)
        try {
            shareTo(context, file, HUAWEI_AOD) ?: throw IOException("Always-on display not supported")
        } finally {
            file.delete()
        }
    }

    /**
     * 设置壁纸；涉及锁屏时同时更新待机界面图片。
     * 华为设备设置锁屏时返回华为图库的设置页面，需要启动后由用户确认。
     */
    suspend fun setWallpaper(context: Context, url: String, target: Target): Intent? = withContext(Dispatchers.IO) {
        val file = download(context, url)
        try {
            val bmp = decodeForScreen(context, file.absolutePath)
            try {
                // minSdk 24，可直接按 flag 分别设置桌面 / 锁屏
                WallpaperManager.getInstance(context).setBitmap(bmp, null, true, target.flags)
            } finally {
                bmp.recycle()
            }
            if (target != Target.HOME) {
                val dest = standbyFile(context)
                val tmp = File(dest.path + ".tmp")
                file.copyTo(tmp, overwrite = true)
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                shareTo(context, file, HUAWEI_WALLPAPER)
            } else {
                null
            }
        } finally {
            file.delete()
        }
    }

    /** 按屏幕尺寸解码待机图片，不存在时返回 null */
    fun loadStandbyBitmap(context: Context): Bitmap? {
        val file = standbyFile(context)
        if (!file.exists()) return null
        return try {
            decodeForScreen(context, file.absolutePath)
        } catch (e: IOException) {
            null
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
