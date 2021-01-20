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

package com.malinskiy.adam.request.device

import com.malinskiy.adam.Const
import com.malinskiy.adam.request.ComplexRequest
import com.malinskiy.adam.request.Feature
import com.malinskiy.adam.request.SerialTarget
import com.malinskiy.adam.transport.Socket
import java.nio.ByteBuffer


class FetchDeviceFeaturesRequest(serial: String) : ComplexRequest<List<Feature>>(target = SerialTarget(serial)) {

    override fun serialize() = createBaseRequest("features")

    override suspend fun readElement(socket: Socket): List<Feature> {
        val sizeBuffer: ByteBuffer = ByteBuffer.allocate(4)
        socket.readFully(sizeBuffer)
        val size = String(sizeBuffer.array(), Const.DEFAULT_TRANSPORT_ENCODING).toInt(radix = 16)

        val payloadBuffer = ByteBuffer.allocate(size)
        socket.readFully(payloadBuffer)
        return String(payloadBuffer.array(), Const.DEFAULT_TRANSPORT_ENCODING).split(',').mapNotNull { Feature.of(it) }
    }
}