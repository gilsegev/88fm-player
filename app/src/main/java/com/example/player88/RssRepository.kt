package com.example.player88

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class RssRepository(
    private val client: OkHttpClient = OkHttpClient(),
    private val parser: RssParser = RssParser()
) {
    private val feedUrl = "https://www.omnycontent.com/d/playlist/23f697a0-7e6a-4e96-a223-a82c00962b12/a888a279-9911-4085-9a92-ab3900a0c129/425d386f-3564-4ec5-95d3-ab3900a0c251/podcast.rss?limit=1000"

    suspend fun fetchEpisodes(): Result<List<Episode>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(feedUrl).build()
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("Unexpected code $response"))
            }

            val body = response.body ?: return@withContext Result.failure(IOException("Empty body"))
            val episodes = parser.parse(body.byteStream())
            Result.success(episodes)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
