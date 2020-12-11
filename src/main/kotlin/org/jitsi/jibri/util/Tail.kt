/*
 * Copyright @ 2018 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jitsi.jibri.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Create a [SharedFlow<String>] representing the output from the given input stream,
 * [replay] denotes how many lines will be replayed to new subscribers.  The tail
 * coroutine will stop once the [InputStream] has no more data.
 */
@Suppress("BlockingMethodInNonBlockingContext")
fun tail(
    inputStream: InputStream,
    replay: Int = 50,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
): SharedFlow<String> {
    val reader = BufferedReader(InputStreamReader(inputStream))
    val lines = MutableSharedFlow<String>(replay)
    scope.launch {
        while (isActive) {
            val line = reader.readLine() ?: run {
                lines.emit(EOF)
                return@launch
            }
            lines.emit(line)
        }
    }
    return lines.asSharedFlow()
}

const val EOF = "__eof__"
