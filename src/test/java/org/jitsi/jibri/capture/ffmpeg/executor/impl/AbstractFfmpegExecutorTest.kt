/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
 *
 */

package org.jitsi.jibri.capture.ffmpeg.executor.impl

import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.whenever
import io.kotlintest.Description
import io.kotlintest.Spec
import io.kotlintest.TestCase
import io.kotlintest.runner.junit5.TestCaseContext
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import io.kotlintest.specs.StringSpec
import org.jitsi.jibri.capture.ffmpeg.executor.FfmpegExecutorParams
import org.jitsi.jibri.sink.Sink
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.time.Duration

class TestableAbstractFfmpegExecutor(fakeProcessBuilder: ProcessBuilder) : AbstractFfmpegExecutor(fakeProcessBuilder) {
    override fun getFfmpegCommand(ffmpegExecutorParams: FfmpegExecutorParams, sink: Sink): String = ""
}

fun waitFor(duration: Duration, func: () -> Boolean): Boolean {
    val currentTime = System.currentTimeMillis()
    while (duration.plusMillis(currentTime).toMillis() > System.currentTimeMillis()) {
        if (func()) {
            return true
        }
        Thread.sleep(500)
    }
    return false
}

class AbstractFfmpegExecutorTest : ShouldSpec() {
    private val processBuilder: ProcessBuilder = mock()
    private val process: Process = mock()
    private val sink: Sink = mock()
    private lateinit var processStdOutWriter: PipedOutputStream
    private lateinit var processStdOut: InputStream

    private val ffmpegExecutor = TestableAbstractFfmpegExecutor(processBuilder)

    override fun beforeSpec(description: Description, spec: Spec) {
        super.beforeSpec(description, spec)

        processStdOutWriter = PipedOutputStream()
        processStdOut = PipedInputStream(processStdOutWriter)
        whenever(process.inputStream).thenReturn(processStdOut)
        whenever(processBuilder.start()).thenReturn(process)
    }

    override fun beforeTest(description: Description) {
        super.beforeTest(description)
    }

    init {
        "before ffmpeg is launched" {
            "getExitCode" {
                should("return null") {
                    ffmpegExecutor.getExitCode() shouldBe null
                }
            }
            "isHealthy" {
                should("return false") {
                    ffmpegExecutor.isHealthy() shouldBe false
                }
            }
        }

        "after ffmpeg is launched" {
            whenever(process.isAlive).thenReturn(true)
            ffmpegExecutor.launchFfmpeg(FfmpegExecutorParams(), sink)
            "if the process is alive" {
                "getExitCode" {
                    should("return null") {
                        ffmpegExecutor.getExitCode() shouldBe null
                    }
                }
                "and ffmpeg is encoding" {
                    processStdOutWriter.write("frame=24\n".toByteArray())
                    "isHealthy" {
                        should("return true") {
                            waitFor(Duration.ofSeconds(5)) {
                                ffmpegExecutor.isHealthy()
                            } shouldBe true
                        }
                    }
                }
                "and ffmpeg has a warning" {
                    processStdOutWriter.write("Past duration 0.53 too large".toByteArray())
                    "isHealthy" {
                        should("return true") {
                            waitFor(Duration.ofSeconds(5)) {
                                ffmpegExecutor.isHealthy()
                            } shouldBe true
                        }
                    }
                }
            }
            "if the process dies" {
                whenever(process.isAlive).thenReturn(false)
                "getExitCode" {
                    whenever(process.exitValue()).thenReturn(42)
                    should("return its exit code") {
                        ffmpegExecutor.getExitCode() shouldBe 42
                    }
                }
            }
        }
    }
}

//import com.nhaarman.mockito_kotlin.mock
//import com.nhaarman.mockito_kotlin.reset
//import com.nhaarman.mockito_kotlin.whenever
//import org.jitsi.jibri.capture.ffmpeg.executor.FfmpegExecutorParams
//import org.jitsi.jibri.sink.Sink
//import org.junit.Assert.assertEquals
//import org.testng.Assert.assertFalse
//import org.testng.Assert.assertNull
//import org.testng.annotations.BeforeMethod
//import org.testng.annotations.Test
//import java.io.InputStream
//import java.io.PipedInputStream
//import java.io.PipedOutputStream
//
//class FakeFfmpegExecutor(fakeProcessBuilder: ProcessBuilder) : AbstractFfmpegExecutor(fakeProcessBuilder) {
//    override fun getFfmpegCommand(ffmpegExecutorParams: FfmpegExecutorParams, sink: Sink): String = ""
//}
//
//class AbstractFfmpegExecutorTest {
//    private val mockSink: Sink = mock()
//    private val mockProcessBuilder: ProcessBuilder = mock()
//    private val mockProcess: Process = mock()
//    private lateinit var processStdOutWriter: PipedOutputStream
//    private lateinit var mockProcessStdOut: InputStream
//    private lateinit var ffmpegExecutor: AbstractFfmpegExecutor
//
//    private val mocks = listOf(
//        mockSink,
//        mockProcessBuilder,
//        mockProcess
//    )
//
//    @BeforeMethod
//    fun setUp() {
//        ffmpegExecutor = FakeFfmpegExecutor(mockProcessBuilder)
//        processStdOutWriter = PipedOutputStream()
//        mockProcessStdOut = PipedInputStream(processStdOutWriter)
//        mocks.forEach { reset(it) }
//
//        whenever(mockProcessBuilder.start()).thenReturn(mockProcess)
//        whenever(mockProcess.inputStream).thenReturn(mockProcessStdOut)
//    }
//
//    @Test
//    fun `test if the process was never launched, then 'null' is returned as the exit code`() {
//        assertNull(ffmpegExecutor.getExitCode())
//    }
//
//    @Test
//    fun `test if the process is alive, then 'null' is returned as the exit code`() {
//        whenever(mockProcess.isAlive).thenReturn(true)
//
//        ffmpegExecutor.launchFfmpeg(FfmpegExecutorParams(), mockSink)
//        assertNull(ffmpegExecutor.getExitCode())
//    }
//
//    @Test
//    fun `test if the process is dead, then its exit code is returned`() {
//        whenever(mockProcess.isAlive).thenReturn(true)
//        ffmpegExecutor.launchFfmpeg(FfmpegExecutorParams(), mockSink)
//        whenever(mockProcess.exitValue()).thenReturn(42)
//        whenever(mockProcess.isAlive).thenReturn(false)
//        assertEquals(42, ffmpegExecutor.getExitCode())
//    }
//
//    @Test
//    fun `test if the process was never launched, then it doesn't show as healthy`() {
//        assertFalse(ffmpegExecutor.isHealthy())
//    }
//}
