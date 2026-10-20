package com.cloudalbum.wallpaper.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

const val HOST = "https://omessage.ecloudoman.com/prod-api"

data class ImageInfo(val path: String) {
    val url: String get() = HOST + path
}

/** 统一的列表结构体 */
data class PageResult<T>(
    val total: Int,
    val rows: List<T>,
    val code: Int,
    val msg: String?,
)

/** 请求失败；[silent] 为 true 时只需提示（toast），否则需要弹窗 */
class ApiException(message: String, val silent: Boolean = false) : Exception(message)

object ImageApi {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 请求图片列表 */
    suspend fun getImageList(pageNum: Int, pageSize: Int): PageResult<ImageInfo> =
        rqList("/system/attachments/list2", mapOf("pageNum" to pageNum, "pageSize" to pageSize)) {
            ImageInfo(it.getString("path"))
        }

    private suspend fun <T> rqList(
        path: String,
        params: Map<String, Any>,
        mapper: (JSONObject) -> T,
    ): PageResult<T> = withContext(Dispatchers.IO) {
        val url = (HOST + path).toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v.toString()) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { res ->
            if (res.code != 200) throw ApiException("Error Code:${res.code}")
            val body = res.body?.string()
            val raw = try {
                JSONObject(body ?: "")
            } catch (e: Exception) {
                throw ApiException("Server Error")
            }

            val code = raw.optInt("code", -999)
            val msg = raw.optString("msg").takeIf { raw.has("msg") && !raw.isNull("msg") }
            if (code == -1) throw ApiException("Not Login", silent = true)
            if (code != 200) throw ApiException(msg.orEmpty(), silent = true)

            val rowsJson = raw.optJSONArray("rows")
            val rows = buildList {
                if (rowsJson != null) {
                    for (i in 0 until rowsJson.length()) add(mapper(rowsJson.getJSONObject(i)))
                }
            }
            PageResult(total = raw.optInt("total", 0), rows = rows, code = code, msg = msg)
        }
    }
}
