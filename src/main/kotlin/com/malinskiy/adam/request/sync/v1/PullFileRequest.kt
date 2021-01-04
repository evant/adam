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

package com.malinskiy.adam.request.sync.v1

import com.malinskiy.adam.Const
import com.malinskiy.adam.exception.PullFailedException
import com.malinskiy.adam.exception.UnsupportedSyncProtocolException
import com.malinskiy.adam.extension.toByteArray
import com.malinskiy.adam.extension.toInt
import com.malinskiy.adam.request.AsyncChannelRequest
import com.malinskiy.adam.request.ValidationResponse
import com.malinskiy.adam.transport.AndroidReadChannel
import com.malinskiy.adam.transport.AndroidWriteChannel
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import java.io.File
import kotlin.coroutines.CoroutineContext

class PullFileRequest(
    val remotePath: String,
    val local: File,
    coroutineContext: CoroutineContext = Dispatchers.IO
) : AsyncChannelRequest<Double, Unit>() {

    val fileWriteChannel = local.writeChannel(coroutineContext = coroutineContext)
    var totalBytes = 1
    var currentPosition = 0L

    override suspend fun handshake(readChannel: AndroidReadChannel, writeChannel: AndroidWriteChannel) {
        super.handshake(readChannel, writeChannel)

        totalBytes = statSize(readChannel, writeChannel)

        val type = Const.Message.RECV_V1

        val path = remotePath.toByteArray(Const.DEFAULT_TRANSPORT_ENCODING)
        val size = path.size.toByteArray().reversedArray()

        val cmd = ByteArray(8 + path.size)

        type.copyInto(cmd)
        size.copyInto(cmd, 4)
        path.copyInto(cmd, 8)

        writeChannel.write(cmd)
    }

    private suspend fun statSize(readChannel: AndroidReadChannel, writeChannel: AndroidWriteChannel): Int {
        val bytes = ByteArray(16)

        val type = Const.Message.LSTAT_V1

        val path = remotePath.toByteArray(Const.DEFAULT_TRANSPORT_ENCODING)
        val size = path.size.toByteArray().reversedArray()

        val cmd = ByteArray(8 + path.size)

        type.copyInto(cmd)
        size.copyInto(cmd, 4)
        path.copyInto(cmd, 8)

        writeChannel.write(cmd)
        readChannel.readFully(bytes, 0, 16)

        if (!bytes.copyOfRange(0, 4).contentEquals(Const.Message.LSTAT_V1)) throw UnsupportedSyncProtocolException()

        return bytes.copyOfRange(8, 12).toInt()
    }

    private val headerBuffer = ByteArray(8)
    private val dataBuffer = ByteArray(Const.MAX_FILE_PACKET_LENGTH)

    override suspend fun readElement(readChannel: AndroidReadChannel, writeChannel: AndroidWriteChannel): Double? {
        readChannel.readFully(headerBuffer, 0, 8)

        val header = headerBuffer.copyOfRange(0, 4)
        when {
            header.contentEquals(Const.Message.DONE) -> {
                fileWriteChannel.close(null)
                readChannel.cancel(null)
                writeChannel.close(null)
                return 1.0
            }
            header.contentEquals(Const.Message.DATA) -> {
                val available = headerBuffer.copyOfRange(4, 8).toInt()
                if (available > Const.MAX_FILE_PACKET_LENGTH) {
                    throw UnsupportedSyncProtocolException()
                }
                readChannel.readFully(dataBuffer, 0, available)
                fileWriteChannel.writeFully(dataBuffer, 0, available)

                currentPosition += available

                return currentPosition.toDouble() / totalBytes
            }
            header.contentEquals(Const.Message.FAIL) -> {
                val size = headerBuffer.copyOfRange(4, 8).toInt()
                readChannel.readFully(dataBuffer, 0, size)
                val errorMessage = String(dataBuffer, 0, size)
                throw PullFailedException("Failed to pull file $remotePath: $errorMessage")
            }
            else -> {
                throw UnsupportedSyncProtocolException("Unexpected header message ${String(header, Const.DEFAULT_TRANSPORT_ENCODING)}")
            }
        }
    }

    override fun close(channel: SendChannel<Double>) {
        fileWriteChannel.close()
    }

    override fun serialize() = createBaseRequest("sync:")

    override fun validate(): ValidationResponse {
        val bytes = remotePath.toByteArray(Const.DEFAULT_TRANSPORT_ENCODING)

        return if (bytes.size <= Const.MAX_REMOTE_PATH_LENGTH) {
            ValidationResponse.Success
        } else ValidationResponse(false, "Remote path should be less that ${Const.MAX_REMOTE_PATH_LENGTH} bytes")
    }

    override suspend fun writeElement(element: Unit, readChannel: AndroidReadChannel, writeChannel: AndroidWriteChannel) = Unit
}
