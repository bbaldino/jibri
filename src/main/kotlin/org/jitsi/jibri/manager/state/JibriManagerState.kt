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

import net.java.sip.communicator.impl.protocol.jabber.extensions.jibri.JibriStatusPacketExt
import org.jitsi.jibri.FileRecordingRequestParams
import org.jitsi.jibri.StartServiceResult
import org.jitsi.jibri.health.EnvironmentContext
import org.jitsi.jibri.health.JibriHealth
import org.jitsi.jibri.manager.NewJibriManager
import org.jitsi.jibri.service.JibriService
import org.jitsi.jibri.service.JibriServiceStatus
import org.jitsi.jibri.service.JibriServiceStatusHandler
import org.jitsi.jibri.service.ServiceParams
import org.jitsi.jibri.service.impl.FileRecordingJibriService
import org.jitsi.jibri.service.impl.FileRecordingParams
import org.jitsi.jibri.service.impl.SipGatewayJibriService
import org.jitsi.jibri.service.impl.SipGatewayServiceParams
import org.jitsi.jibri.service.impl.StreamingJibriService
import org.jitsi.jibri.service.impl.StreamingParams
import org.jitsi.jibri.util.extensions.error
import org.jitsi.jibri.util.extensions.schedule
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * [NewJibriManager] has 2 states: [Busy] and [Idle].  Each state
 * has different implementations of the methods defined here in
 * [JibriManagerState].
 */
sealed class JibriManagerState(protected val jibriManager: NewJibriManager) {
    open fun startFileRecording(
        serviceParams: ServiceParams,
        fileRecordingRequestParams: FileRecordingRequestParams,
        environmentContext: EnvironmentContext? = null,
        serviceStatusHandler: JibriServiceStatusHandler? = null
    ): StartServiceResult = throw NotImplementedError()

    open fun startStreaming(
        serviceParams: ServiceParams,
        streamingParams: StreamingParams,
        environmentContext: EnvironmentContext? = null,
        serviceStatusHandler: JibriServiceStatusHandler? = null
    ): StartServiceResult = throw NotImplementedError()

    open fun startSipGateway(
        serviceParams: ServiceParams,
        sipGatewayServiceParams: SipGatewayServiceParams,
        environmentContext: EnvironmentContext? = null,
        serviceStatusHandler: JibriServiceStatusHandler? = null
    ): StartServiceResult = throw NotImplementedError()

    open fun stopService(): Unit = throw NotImplementedError()

    open fun healthCheck(): JibriHealth = throw NotImplementedError()

    open fun executeWhenIdle(func: () -> Unit): Unit = throw NotImplementedError()
}

class Busy(
    jibriManager: NewJibriManager,
    private val activeService: JibriService,
    usageTimeoutMinutes: Int,
    private val environmentContext: EnvironmentContext? = null,
    result: CompletableFuture<StartServiceResult>,
    executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
) : JibriManagerState(jibriManager) {
    private val logger = Logger.getLogger(this::class.qualifiedName)
    /**
     * The task which will fire when the usage timeout (if one has been configured)
     * has been reached.
     */
    private var serviceTimeoutTask: ScheduledFuture<*>? = null

    init {
        logger.info("JibriManager entering BUSY state")
        jibriManager.publishStatus(JibriStatusPacketExt.Status.BUSY)
        if (usageTimeoutMinutes != 0) {
            logger.info("This service will have a usage timeout of $usageTimeoutMinutes minute(s)")
            serviceTimeoutTask = executor.schedule(usageTimeoutMinutes.toLong(), TimeUnit.MINUTES) {
                logger.info("The usage timeout has elapsed, stopping the currently active service")
                try {
                    stopService()
                } catch (t: Throwable) {
                    logger.error("Error while stopping service due to usage timeout: $t")
                }
            }
        }
        // The manager adds its own status handler so that it can stop
        // the error'd service and update presence appropriately
        activeService.addStatusHandler {
            when (it) {
                JibriServiceStatus.ERROR, JibriServiceStatus.FINISHED -> stopService()
            }
        }
        if (!activeService.start()) {
            result.complete(StartServiceResult.ERROR)
            stopService()
        } else {
            result.complete(StartServiceResult.SUCCESS)
        }
    }
    override fun startFileRecording(
        serviceParams: ServiceParams,
        fileRecordingRequestParams: FileRecordingRequestParams,
        environmentContext: EnvironmentContext?,
        serviceStatusHandler: JibriServiceStatusHandler?
    ): StartServiceResult = StartServiceResult.BUSY

    override fun startStreaming(
        serviceParams: ServiceParams,
        streamingParams: StreamingParams,
        environmentContext: EnvironmentContext?,
        serviceStatusHandler: JibriServiceStatusHandler?
    ): StartServiceResult = StartServiceResult.BUSY

    override fun startSipGateway(
        serviceParams: ServiceParams,
        sipGatewayServiceParams: SipGatewayServiceParams,
        environmentContext: EnvironmentContext?,
        serviceStatusHandler: JibriServiceStatusHandler?
    ): StartServiceResult = StartServiceResult.BUSY

    override fun stopService() {
        println("Stopping the current service")
        serviceTimeoutTask?.cancel(false)
        activeService.stop()
        jibriManager.state = Idle(jibriManager)
    }

    override fun healthCheck(): JibriHealth {
        return JibriHealth(true, environmentContext)
    }

    override fun executeWhenIdle(func: () -> Unit) {
        jibriManager.pendingIdleFunc = func
    }
}

class Idle(
    jibriManager: NewJibriManager
) : JibriManagerState(jibriManager) {
    private val logger = Logger.getLogger(this::class.qualifiedName)

    init {
        logger.info("JibriManager entering IDLE state")
        jibriManager.pendingIdleFunc()
        jibriManager.pendingIdleFunc = {}
        jibriManager.publishStatus(JibriStatusPacketExt.Status.IDLE)
    }

    override fun startFileRecording(
        serviceParams: ServiceParams,
        fileRecordingRequestParams: FileRecordingRequestParams,
        environmentContext: EnvironmentContext?,
        serviceStatusHandler: JibriServiceStatusHandler?
    ): StartServiceResult {
        logger.info("Starting a file recording with params: $fileRecordingRequestParams " +
                "finalize script path: ${jibriManager.config.finalizeRecordingScriptPath} and " +
                "recordings directory: ${jibriManager.config.recordingDirectory}")
        val service = FileRecordingJibriService(
            FileRecordingParams(
                fileRecordingRequestParams.callParams,
                fileRecordingRequestParams.callLoginParams,
                jibriManager.config.finalizeRecordingScriptPath,
                jibriManager.config.recordingDirectory
            )
        )
        return startService(service, serviceParams, environmentContext, serviceStatusHandler).get()
    }

    override fun startStreaming(
        serviceParams: ServiceParams,
        streamingParams: StreamingParams,
        environmentContext: EnvironmentContext?,
        serviceStatusHandler: JibriServiceStatusHandler?
    ): StartServiceResult {
        logger.info("Starting a stream with params: $serviceParams $streamingParams")
        val service = StreamingJibriService(streamingParams)
        return startService(service, serviceParams, environmentContext, serviceStatusHandler).get()
    }

    override fun startSipGateway(
        serviceParams: ServiceParams,
        sipGatewayServiceParams: SipGatewayServiceParams,
        environmentContext: EnvironmentContext?,
        serviceStatusHandler: JibriServiceStatusHandler?
    ): StartServiceResult {
        logger.info("Starting a SIP gateway with params: $serviceParams $sipGatewayServiceParams")
        val service = SipGatewayJibriService(SipGatewayServiceParams(
            sipGatewayServiceParams.callParams,
            sipGatewayServiceParams.sipClientParams
        ))
        return startService(service, serviceParams, environmentContext, serviceStatusHandler).get()
    }

    private fun startService(
        jibriService: JibriService,
        serviceParams: ServiceParams,
        environmentContext: EnvironmentContext?,
        serviceStatusHandler: JibriServiceStatusHandler? = null
    ): Future<StartServiceResult> {
        if (serviceStatusHandler != null) {
            jibriService.addStatusHandler(serviceStatusHandler)
        }
        // The manager adds its own status handler so that it can stop
        // the error'd service and update presence appropriately
        jibriService.addStatusHandler {
            when (it) {
                JibriServiceStatus.ERROR, JibriServiceStatus.FINISHED -> stopService()
            }
        }

        // The Busy state is responsible for starting the service, but we want to return from this call whether
        // or not it succeeded, so we'll pass a future to the Busy state and let it tell us its result so
        // we can return it here
        // TODO: is this a hack?  Another option would be to have a state transition method which handled all
        // changes and could return a result, but even that is tough since the work will happen in the state's
        // ctor (unless we also added a 'enter' method or something?)
        val res = CompletableFuture<StartServiceResult>()
        jibriManager.state =
                Busy(jibriManager, jibriService, serviceParams.usageTimeoutMinutes, environmentContext, res)

        return res
    }

    override fun healthCheck(): JibriHealth {
        return JibriHealth(false)
    }

    override fun executeWhenIdle(func: () -> Unit) {
        func()
    }
}
