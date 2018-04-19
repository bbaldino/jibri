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

package org.jitsi.jibri.manager.state

import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.anyOrNull
import com.nhaarman.mockito_kotlin.argumentCaptor
import com.nhaarman.mockito_kotlin.eq
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.never
import com.nhaarman.mockito_kotlin.reset
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.whenever
import io.kotlintest.Description
import io.kotlintest.TestResult
import io.kotlintest.matchers.haveSize
import io.kotlintest.matchers.instanceOf
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.FunSpec
import net.java.sip.communicator.impl.protocol.jabber.extensions.jibri.JibriStatusPacketExt
import org.jitsi.jibri.config.JibriConfig
import org.jitsi.jibri.health.EnvironmentContext
import org.jitsi.jibri.manager.JibriManager
import org.jitsi.jibri.service.JibriService
import org.jitsi.jibri.service.JibriServiceStatusHandler
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal class BusyTest : FunSpec() {
    private val jibriManager: JibriManager = mock()
    private val stateFactory: StateFactory = mock()
    private val idleState: Idle = mock()

    override fun beforeTest(description: Description) {
        super.beforeTest(description)
        val jibriConfig: JibriConfig = mock()
        whenever(jibriConfig.recordingDirectory).thenReturn(mock())
        whenever(jibriConfig.finalizeRecordingScriptPath).thenReturn("finalize_dir")
        whenever(jibriManager.config).thenReturn(jibriConfig)
        whenever(jibriManager.stateFactory).thenReturn(stateFactory)
    }

    override fun afterTest(description: Description, result: TestResult) {
        reset(jibriManager, stateFactory, idleState)
    }

    init {
        test("should add all status handlers to the service") {
            val service: JibriService = mock()
            whenever(service.start()).thenReturn(true)
            val handler1: JibriServiceStatusHandler = mock()
            val handler2: JibriServiceStatusHandler = mock()
            Busy(jibriManager, service, 0, serviceStatusHandlers = listOf(handler1, handler2))

            verify(service).addStatusHandler(handler1)
            verify(service).addStatusHandler(handler2)
        }

        test("should throw if the service doesn't start") {
            val service: JibriService = mock()
            whenever(service.start()).thenReturn(false)
            shouldThrow<StartServiceErrorException> {
                Busy(jibriManager, service, 0, serviceStatusHandlers = listOf())
            }
        }

        test("should not schedule the usage timeout if none is given") {
            val service: JibriService = mock()
            val executor: ScheduledExecutorService = mock()
            val task = argumentCaptor<Runnable>()
            whenever(executor.schedule(task.capture(), any(), any())).thenReturn(mock())
            whenever(service.start()).thenReturn(true)
            Busy(jibriManager, service, 0, serviceStatusHandlers = listOf(), executor = executor)
            // No task should have been scheduled which will call JibriManager#stopService
            task.allValues.forEach { it.run() }
            verify(jibriManager, never()).stopService()
        }

        test("should schedule the usage timeout when one is given") {
            val service: JibriService = mock()
            val executor: ScheduledExecutorService = mock()
            val task = argumentCaptor<Runnable>()
            whenever(executor.schedule(task.capture(), any(), any())).thenReturn(mock())
            whenever(service.start()).thenReturn(true)
            Busy(jibriManager, service, 1, serviceStatusHandlers = listOf(), executor = executor)
            verify(executor).schedule(any(), eq(1L), eq(TimeUnit.MINUTES))
            // Execute all scheduled tasks, one of them should call JibriManager#stopService
            task.allValues.forEach { it.run() }
            verify(jibriManager).stopService()
        }

        test("healthCheck should return true and contain environment context when one is set") {
            val service: JibriService = mock()
            whenever(service.start()).thenReturn(true)
            val environmentContext = EnvironmentContext("env_name")
            val busy = Busy(jibriManager, service, 0, environmentContext, listOf())
            val health = busy.healthCheck()
            health.busy shouldBe true
            health.environmentContext shouldBe environmentContext
        }

        test("healthCheck should return true and not contain environment context when none is set") {
            val service: JibriService = mock()
            whenever(service.start()).thenReturn(true)
            val busy = Busy(jibriManager, service, 0, serviceStatusHandlers = listOf())
            val health = busy.healthCheck()
            health.busy shouldBe true
            health.environmentContext shouldBe null
        }

        test("postStateTransition should publish busy status") {
            val service: JibriService = mock()
            whenever(service.start()).thenReturn(true)
            val status = argumentCaptor<JibriStatusPacketExt.Status>()
            val busy = Busy(jibriManager, service, 0, serviceStatusHandlers = listOf())
            busy.postStateTransition()
            verify(jibriManager).publishStatus(status.capture())
            status.allValues should haveSize(1)
            status.firstValue shouldBe JibriStatusPacketExt.Status.BUSY
        }

        test("should throw when trying to start a new service") {
            val service: JibriService = mock()
            whenever(service.start()).thenReturn(true)
            val busy = Busy(jibriManager, service, 0, serviceStatusHandlers = listOf())

            shouldThrow<AlreadyBusyException> {
                busy.startFileRecording(mock(), mock(), serviceStatusHandlers = listOf())
            }
            shouldThrow<AlreadyBusyException> {
                busy.startStreaming(mock(), mock(), serviceStatusHandlers = listOf())
            }
            shouldThrow<AlreadyBusyException> {
                busy.startSipGateway(mock(), mock(), serviceStatusHandlers = listOf())
            }
        }

        test("stopService should call stop on the active service") {
            val service: JibriService = mock()
            whenever(service.start()).thenReturn(true)
            whenever(stateFactory.createIdleState(any(), anyOrNull(), anyOrNull())).thenReturn(idleState)
            val busy = Busy(jibriManager, service, 0, serviceStatusHandlers = listOf())
            busy.stopService()
            verify(service).stop()
        }

        test("stopService should return idle state") {
            val service: JibriService = mock()
            whenever(service.start()).thenReturn(true)
            whenever(stateFactory.createIdleState(any(), anyOrNull(), anyOrNull())).thenReturn(idleState)
            val busy = Busy(jibriManager, service, 0, serviceStatusHandlers = listOf())
            val newState = busy.stopService()
            newState shouldBe instanceOf(Idle::class)
        }

        test("executeWhenIdle should save the idle func and pass it to idle state") {
            val service: JibriService = mock()
            whenever(service.start()).thenReturn(true)
            val busy = Busy(jibriManager, service, 0, serviceStatusHandlers = listOf())
            val func = { }
            busy.executeWhenIdle(func)
            val executeWhenIdleFunc = argumentCaptor<() -> Unit>()
            whenever(stateFactory.createIdleState(any(), executeWhenIdleFunc.capture(), anyOrNull())).thenReturn(mock())
            busy.stopService()
            executeWhenIdleFunc.allValues should haveSize(1)
            executeWhenIdleFunc.firstValue shouldBe func
        }
    }
}
