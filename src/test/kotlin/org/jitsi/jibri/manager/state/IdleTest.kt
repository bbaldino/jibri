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
import com.nhaarman.mockito_kotlin.argumentCaptor
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.whenever
import io.kotlintest.Description
import io.kotlintest.matchers.beTheSameInstanceAs
import io.kotlintest.matchers.haveSize
import io.kotlintest.matchers.instanceOf
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.specs.FunSpec
import net.java.sip.communicator.impl.protocol.jabber.extensions.jibri.JibriStatusPacketExt
import org.jitsi.jibri.CallUrlInfo
import org.jitsi.jibri.FileRecordingRequestParams
import org.jitsi.jibri.config.JibriConfig
import org.jitsi.jibri.config.XmppCredentials
import org.jitsi.jibri.health.EnvironmentContext
import org.jitsi.jibri.manager.JibriManager
import org.jitsi.jibri.selenium.CallParams
import org.jitsi.jibri.service.impl.FileRecordingJibriService
import org.jitsi.jibri.service.JibriServiceFactory
import org.jitsi.jibri.service.ServiceParams

internal class IdleTest : FunSpec() {
    private val jibriManager: JibriManager = mock()
    private lateinit var idleState: JibriManagerState
    private val serviceFactory: JibriServiceFactory = mock()

    override fun beforeTest(description: Description) {
        super.beforeTest(description)
        val jibriConfig: JibriConfig = mock()
        whenever(jibriConfig.recordingDirectory).thenReturn(mock())
        whenever(jibriConfig.finalizeRecordingScriptPath).thenReturn("finalize_dir")
        whenever(jibriManager.config).thenReturn(jibriConfig)

        idleState = Idle(jibriManager, serviceFactory = serviceFactory)
    }

    init {
        test("postStateTransition should publish idle status") {
            val status = argumentCaptor<JibriStatusPacketExt.Status>()
            idleState.postStateTransition()
            verify(jibriManager).publishStatus(status.capture())
            status.allValues should haveSize(1)
            status.firstValue shouldBe JibriStatusPacketExt.Status.IDLE
        }

        test("postStateTransition should invoke a pending idle func") {
            var idleFuncInvoked = false
            idleState = Idle(jibriManager, { idleFuncInvoked = true }, serviceFactory)

            idleState.postStateTransition()
            idleFuncInvoked shouldBe true
        }

        test("stopService should return itself") {
            idleState.stopService() should beTheSameInstanceAs(idleState)
            idleState.stopService() should beTheSameInstanceAs(idleState)
        }

        test("healthCheck should return false and not contain environment context") {
            val health = idleState.healthCheck()
            health.busy shouldBe false
            health.environmentContext shouldBe null
        }

        test("executeWhenIdle should invoke the function immediately") {
            var funcInvoked = false
            idleState.executeWhenIdle {
                funcInvoked = true
            }
            funcInvoked shouldBe true
        }

        test("startFileRecording should return busy state when it succeeds") {
            val fileRecordingService: FileRecordingJibriService = mock()
            whenever(fileRecordingService.start()).thenReturn(true)
            whenever(serviceFactory.createFileRecordingService(any())).thenReturn(fileRecordingService)

            val serviceParams = ServiceParams(0)
            val fileRecordingRequestParams = FileRecordingRequestParams(
                CallParams(CallUrlInfo("baseUrl", "callName")),
                XmppCredentials("domain", "username", "password")
            )
            val environmentContext = EnvironmentContext("environmentName")
            val newState =
                idleState.startFileRecording(serviceParams, fileRecordingRequestParams, environmentContext, listOf())
            newState shouldBe instanceOf(Busy::class)
        }

        test("startFileRecording should return idle state (itself) if the service fails to start") {
            val fileRecordingService: FileRecordingJibriService = mock()
            whenever(fileRecordingService.start()).thenAnswer {
                throw StartServiceErrorException()
            }
            whenever(serviceFactory.createFileRecordingService(any())).thenReturn(fileRecordingService)

            val serviceParams = ServiceParams(0)
            val fileRecordingRequestParams = FileRecordingRequestParams(
                CallParams(CallUrlInfo("baseUrl", "callName")),
                XmppCredentials("domain", "username", "password")
            )
            val environmentContext = EnvironmentContext("environmentName")
            val newState =
                idleState.startFileRecording(serviceParams, fileRecordingRequestParams, environmentContext, listOf())
            newState should beTheSameInstanceAs(idleState)
        }
    }
}
