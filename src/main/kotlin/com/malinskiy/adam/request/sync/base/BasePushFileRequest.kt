/*
 * Copyright (C) 2021 Anton Malinskiy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.malinskiy.adam.request.sync.base

import com.malinskiy.adam.Const
import com.malinskiy.adam.exception.PushFailedException
import com.malinskiy.adam.extension.toByteArray
import com.malinskiy.adam.request.AsyncChannelRequest
import com.malinskiy.adam.request.ValidationResponse
import com.malinskiy.adam.transport.AndroidReadChannel
import com.malinskiy.adam.transport.AndroidWriteChannel
import io.ktor.util.cio.readChannel
import io.ktor.utils.io.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import java.io.File
import kotlin.coroutines.CoroutineContext

abstract class BasePushFileRequest(
    private val local: File,
    protected val remotePath: String,
    protected val mode: String = "0777",
    coroutineContext: CoroutineContext = Dispatchers.IO
) : AsyncChannelRequest<Double, Unit>() {
    protected val fileReadChannel = local.readChannel(coroutineContext = coroutineContext)
    protected val buffer = ByteArray(8 + Const.MAX_FILE_PACKET_LENGTH)
    protected var totalBytes = local.length()
    protected var currentPosition = 0L
    protected val modeValue: Int
        get() = mode.toInt(8) and "0777".toInt(8)

    override suspend fun readElement(readChannel: AndroidReadChannel, writeChannel: AndroidWriteChannel): Double? {
        val available = fileReadChannel.readAvailable(buffer, 8, Const.MAX_FILE_PACKET_LENGTH)
        return when {
            available < 0 -> {
                Const.Message.DONE.copyInto(buffer)
                (local.lastModified() / 1000).toInt().toByteArray().copyInto(buffer, destinationOffset = 4)
                writeChannel.write(request = buffer, length = 8)
                val transportResponse = readChannel.read()
                readChannel.cancel(null)
                writeChannel.close(null)
                fileReadChannel.cancel()
                return if (transportResponse.okay) {
                    1.0
                } else {
                    throw PushFailedException("adb didn't acknowledge the file transfer: ${transportResponse.message ?: ""}")
                }
            }
            available > 0 -> {
                Const.Message.DATA.copyInto(buffer)
                available.toByteArray().reversedArray().copyInto(buffer, destinationOffset = 4)
                writeChannel.writeFully(buffer, 0, available + 8)
                currentPosition += available
                currentPosition.toDouble() / totalBytes
            }
            else -> currentPosition.toDouble() / totalBytes
        }
    }

    override fun serialize() = createBaseRequest("sync:")

    override fun close(channel: SendChannel<Double>) {
        fileReadChannel.cancel()
    }

    override suspend fun writeElement(element: Unit, readChannel: AndroidReadChannel, writeChannel: AndroidWriteChannel) = Unit

    override fun validate(): ValidationResponse {
        val response = super.validate()

        val bytes = remotePath.toByteArray(Const.DEFAULT_TRANSPORT_ENCODING)
        return if (!response.success) {
            response
        } else if (!local.exists()) {
            ValidationResponse(false, "Local file $local doesn't exist")
        } else if (!local.isFile) {
            ValidationResponse(false, "$local is not a file")
        } else if (bytes.size > Const.MAX_REMOTE_PATH_LENGTH) {
            ValidationResponse(false, "Remote path should be less that ${Const.MAX_REMOTE_PATH_LENGTH} bytes")
        } else {
            ValidationResponse.Success
        }
    }
}
