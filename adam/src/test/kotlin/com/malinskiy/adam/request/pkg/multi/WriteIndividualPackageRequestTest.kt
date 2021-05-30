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

package com.malinskiy.adam.request.pkg.multi

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.malinskiy.adam.Const
import com.malinskiy.adam.exception.RequestRejectedException
import com.malinskiy.adam.extension.newFileWithExtension
import com.malinskiy.adam.extension.testResource
import com.malinskiy.adam.extension.toRequestString
import com.malinskiy.adam.request.Feature
import com.malinskiy.adam.server.stub.StubSocket
import com.malinskiy.adam.transport.use
import io.ktor.util.cio.writeChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WriteIndividualPackageRequestTest {
    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun serialize() {
        val request = stub()
        assertThat(request.serialize().toRequestString())
            .isEqualTo("0042exec:cmd package install-write -S 614 session-id sample-fake.apk -")
    }

    @Test
    fun serializeAbb() {
        val apk = temp.newFile("sample-fake.apk")
        testResource("/fixture/sample-fake.apk").copyTo(apk, overwrite = true)

        val request = WriteIndividualPackageRequest(
            supportedFeatures = listOf(Feature.CMD, Feature.ABB_EXEC),
            file = apk,
            session = "session-id"
        )
        assertThat(request.serialize().toRequestString())
            .isEqualTo("0042abb_exec:package\u0000install-write\u0000-S\u0000614\u0000session-id\u0000sample-fake.apk\u0000-")
    }

    @Test
    fun serializeNoFeatures() {
        val apk = temp.newFile("sample-fake.apk")
        testResource("/fixture/sample-fake.apk").copyTo(apk, overwrite = true)

        val request = WriteIndividualPackageRequest(
            supportedFeatures = emptyList(),
            file = apk,
            session = "session-id"
        )
        assertThat(request.serialize().toRequestString())
            .isEqualTo("0039exec:pm install-write -S 614 session-id sample-fake.apk -")
    }

    @Test
    fun testRead() {
        val fixture = testResource("/fixture/sample-fake.apk")

        val request = WriteIndividualPackageRequest(
            supportedFeatures = listOf(Feature.CMD),
            file = fixture,
            session = "session-id"
        )
        val response = "Success".toByteArray(Const.DEFAULT_TRANSPORT_ENCODING)
        val actual = temp.newFileWithExtension("apk")

        runBlocking {
            val byteWriteChannel = actual.writeChannel(coroutineContext)
            StubSocket(ByteReadChannel(response), byteWriteChannel).use { socket ->
                request.readElement(socket)
            }
        }

        assertThat(actual.readBytes()).isEqualTo(fixture.readBytes())
    }

    @Test(expected = RequestRejectedException::class)
    fun testReadException() {
        val fixture = testResource("/fixture/sample-fake.apk")

        val request = WriteIndividualPackageRequest(
            supportedFeatures = listOf(Feature.CMD),
            file = fixture,
            session = "session-id"
        )
        val response = "Failure".toByteArray(Const.DEFAULT_TRANSPORT_ENCODING)
        val actual = temp.newFileWithExtension("apk")
        runBlocking {
            val byteBufferChannel: ByteWriteChannel = actual.writeChannel(coroutineContext)
            StubSocket(ByteReadChannel(response), byteBufferChannel).use { socket ->
                request.readElement(socket)
            }
        }

        assertThat(actual.readBytes()).isEqualTo(fixture.readBytes())
    }

    private fun stub(): WriteIndividualPackageRequest {
        val apk = temp.newFile("sample-fake.apk")
        testResource("/fixture/sample-fake.apk").copyTo(apk, overwrite = true)

        val request = WriteIndividualPackageRequest(
            supportedFeatures = listOf(Feature.CMD),
            file = apk,
            session = "session-id"
        )
        return request
    }
}
