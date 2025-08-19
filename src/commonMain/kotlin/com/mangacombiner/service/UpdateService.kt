package com.mangacombiner.service

import com.mangacombiner.model.GithubRelease
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

class UpdateService(private val httpClient: HttpClient) {

    suspend fun getLatestRelease(): GithubRelease? {
        return try {
            httpClient.get("https://api.github.com/repos/bsikar/simple-manga-combiner/releases/latest").body()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
