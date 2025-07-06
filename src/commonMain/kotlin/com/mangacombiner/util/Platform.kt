package com.mangacombiner.util

import io.ktor.client.HttpClient

// We expect each platform to provide its own Ktor HttpClient Engine
expect fun createHttpClient(): HttpClient

// We expect each platform to provide a way to get a temporary directory
expect fun getTmpDir(): String
