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

package org.jitsi.jibri.manager

import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.anyOrNull
import com.nhaarman.mockito_kotlin.argumentCaptor
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.reset
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.whenever
import io.kotlintest.Description
import io.kotlintest.TestResult
import io.kotlintest.shouldBe
import io.kotlintest.specs.FunSpec
import org.jitsi.jibri.config.JibriConfig
import org.jitsi.jibri.manager.state.AlreadyBusyException
import org.jitsi.jibri.manager.state.Busy
import org.jitsi.jibri.manager.state.Idle
import org.jitsi.jibri.manager.state.StateFactory
import org.jitsi.jibri.service.JibriServiceStatus
import org.jitsi.jibri.service.JibriServiceStatusHandler

internal class JibriManagerTest : FunSpec() {
    private val config: JibriConfig = mock()
    private val stateFactory: StateFactory = mock()
    private val idleState: Idle = mock()
    private val busyState: Busy = mock()

    private lateinit var jibriManager: JibriManager

    override fun beforeTest(description: Description) {
        super.beforeTest(description)
        whenever(stateFactory.createIdleState(any(), any(), anyOrNull())).thenReturn(idleState)
        whenever(stateFactory.createBusyState(any(), any(), any(), any(), any(), anyOrNull())).thenReturn(busyState)

        jibriManager = JibriManager(config, stateFactory)
    }

    override fun afterTest(description: Description, result: TestResult) {
        super.afterTest(description, result)
        reset(stateFactory, idleState, busyState)
    }

    init {
        test("healthCheck should invoke current state's health check") {
            // Current state should be idle
            jibriManager.healthCheck()
            verify(idleState).healthCheck()
        }

        test("stopService should invoke the current state's stopService") {
            whenever(idleState.stopService()).thenReturn(idleState)
            jibriManager.stopService()
            verify(idleState).stopService()
        }

        test("executeWhenIdle should invoke the current state's executeWhenIdle") {
            jibriManager.executeWhenIdle {  }
            verify(idleState).executeWhenIdle(any())
        }

        test("startFileRecording should return success when it succeeds") {
            whenever(idleState.startFileRecording(any(), any(), any(), any())).thenReturn(busyState)
            val result = jibriManager.startFileRecording(mock(), mock(), mock(), listOf())
            result shouldBe StartServiceResult.SUCCESS
        }

        test("startFileRecording should return error if the service fails to start") {
            whenever(idleState.startFileRecording(any(), any(), any(), any())).thenReturn(idleState)
            val result = jibriManager.startFileRecording(mock(), mock(), mock(), listOf())
            result shouldBe StartServiceResult.ERROR
        }

        test("startFileRecording should return busy if it was already busy") {
            whenever(idleState.startFileRecording(any(), any(), any(), any())).thenReturn(busyState)
            whenever(busyState.startFileRecording(any(), any(), any(), any())).thenThrow(AlreadyBusyException())
            // First put it in busy state
            jibriManager.startFileRecording(mock(), mock(), mock(), listOf())
            // Now try to start it again
            val result = jibriManager.startFileRecording(mock(), mock(), mock(), listOf())
            result shouldBe StartServiceResult.BUSY
        }

        test("stopService is called if a service finishes") {
            val handlers = argumentCaptor<List<JibriServiceStatusHandler>>()
            whenever(idleState.startFileRecording(any(), any(), any(), handlers.capture())).thenReturn(busyState)
            whenever(busyState.stopService()).thenReturn(idleState)

            jibriManager.startFileRecording(mock(), mock(), mock(), listOf())
            handlers.firstValue.forEach { it(JibriServiceStatus.FINISHED) }
            verify(busyState).stopService()
        }

        test("stopService is called if a service errors") {
            val handlers = argumentCaptor<List<JibriServiceStatusHandler>>()
            whenever(idleState.startFileRecording(any(), any(), any(), handlers.capture())).thenReturn(busyState)
            whenever(busyState.stopService()).thenReturn(idleState)

            jibriManager.startFileRecording(mock(), mock(), mock(), listOf())
            handlers.firstValue.forEach { it(JibriServiceStatus.ERROR) }
            verify(busyState).stopService()
        }
    }
}
