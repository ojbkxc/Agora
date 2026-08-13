package com.lxseek.chat.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

object AppExecutors {
    val io: CoroutineDispatcher = Dispatchers.IO
    val cpu: CoroutineDispatcher = Executors.newFixedThreadPool(
        (Runtime.getRuntime().availableProcessors()).coerceAtLeast(2)
    ).asCoroutineDispatcher()
}
