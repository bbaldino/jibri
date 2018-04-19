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
import org.jitsi.jibri.health.EnvironmentContext
import org.jitsi.jibri.health.JibriHealth
import org.jitsi.jibri.manager.FileRecordingRequestParams
import org.jitsi.jibri.manager.JibriManager
import org.jitsi.jibri.service.JibriService
import org.jitsi.jibri.service.JibriServiceFactory
import org.jitsi.jibri.service.JibriServiceStatusHandler
import org.jitsi.jibri.service.ServiceParams
import org.jitsi.jibri.service.impl.FileRecordingParams
import org.jitsi.jibri.service.impl.SipGatewayServiceParams
import org.jitsi.jibri.service.impl.StreamingParams
import org.jitsi.jibri.util.extensions.error
import org.jitsi.jibri.util.extensions.schedule
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

sealed class StateTransitionException(message: String) : Exception(message)
class StartServiceErrorException : StateTransitionException("Error starting service")
class AlreadyBusyException : StateTransitionException("Jibri is already busy")

/**
 * [JibriManager] has 2 states: [Busy] and [Idle].  Each state
 * has different implementations of the methods defined here in
 * [JibriManagerState].
 */
sealed class JibriManagerState(protected val jibriManager: JibriManager) {
    open fun postStateTransition() {}

    open fun startFileRecording(
        serviceParams: ServiceParams,
        fileRecordingRequestParams: FileRecordingRequestParams,
        environmentContext: EnvironmentContext? = null,
        serviceStatusHandlers: List<JibriServiceStatusHandler>
    ): JibriManagerState = throw NotImplementedError()

    open fun startStreaming(
        serviceParams: ServiceParams,
        streamingParams: StreamingParams,
        environmentContext: EnvironmentContext? = null,
        serviceStatusHandlers: List<JibriServiceStatusHandler>
    ): JibriManagerState = throw NotImplementedError()

    open fun startSipGateway(
        serviceParams: ServiceParams,
        sipGatewayServiceParams: SipGatewayServiceParams,
        environmentContext: EnvironmentContext? = null,
        serviceStatusHandlers: List<JibriServiceStatusHandler>
    ): JibriManagerState = throw NotImplementedError()

    open fun stopService(): JibriManagerState = throw NotImplementedError()

    open fun healthCheck(): JibriHealth = throw NotImplementedError()

    open fun executeWhenIdle(func: () -> Unit): Unit = throw NotImplementedError()
}

class Busy(
    jibriManager: JibriManager,
    private val activeService: JibriService,
    usageTimeoutMinutes: Int,
    private val environmentContext: EnvironmentContext? = null,
    serviceStatusHandlers: List<JibriServiceStatusHandler>,
    executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
) : JibriManagerState(jibriManager) {
    private val logger = Logger.getLogger(this::class.qualifiedName)
    /**
     * The task which will fire when the usage timeout (if one has been configured)
     * has been reached.
     */
    private var serviceTimeoutTask: ScheduledFuture<*>? = null

    /**
     * A function to be executed when we transition to [Idle] state, we save
     * it in case [Busy.executeWhenIdle] is called so it can be passed to [Idle]
     */
    private var pendingIdleFunc: () -> Unit = {}

    init {
        logger.info("JibriManager entering BUSY state")
        if (usageTimeoutMinutes != 0) {
            logger.info("This service will have a usage timeout of $usageTimeoutMinutes minute(s)")
            serviceTimeoutTask = executor.schedule(usageTimeoutMinutes.toLong(), TimeUnit.MINUTES) {
                logger.info("The usage timeout has elapsed, stopping the currently active service")
                try {
                    jibriManager.stopService()
                } catch (t: Throwable) {
                    logger.error("Error while stopping service due to usage timeout: $t")
                }
            }
        }
        serviceStatusHandlers.forEach(activeService::addStatusHandler)
        if (!activeService.start()) {
            logger.error("Service failed to start")
            throw StartServiceErrorException()
        }
    }

    override fun postStateTransition() {
        logger.info("Jibri entered BUSY state")
        jibriManager.publishStatus(JibriStatusPacketExt.Status.BUSY)
    }

    override fun startFileRecording(
        serviceParams: ServiceParams,
        fileRecordingRequestParams: FileRecordingRequestParams,
        environmentContext: EnvironmentContext?,
        serviceStatusHandlers: List<JibriServiceStatusHandler>
    ): JibriManagerState = throw AlreadyBusyException()

    override fun startStreaming(
        serviceParams: ServiceParams,
        streamingParams: StreamingParams,
        environmentContext: EnvironmentContext?,
        serviceStatusHandlers: List<JibriServiceStatusHandler>
    ): JibriManagerState = throw AlreadyBusyException()

    override fun startSipGateway(
        serviceParams: ServiceParams,
        sipGatewayServiceParams: SipGatewayServiceParams,
        environmentContext: EnvironmentContext?,
        serviceStatusHandlers: List<JibriServiceStatusHandler>
    ): JibriManagerState = throw AlreadyBusyException()

    override fun stopService(): JibriManagerState {
        println("Stopping the current service")
        serviceTimeoutTask?.cancel(false)
        activeService.stop()
        return Idle(jibriManager, pendingIdleFunc)
    }

    override fun healthCheck(): JibriHealth {
        return JibriHealth(true, environmentContext)
    }

    override fun executeWhenIdle(func: () -> Unit) {
        pendingIdleFunc = func
    }
}

class Idle(
    jibriManager: JibriManager,
    private val idleFunc: () -> Unit = {},
    private val serviceFactory: JibriServiceFactory = JibriServiceFactory()
) : JibriManagerState(jibriManager) {
    private val logger = Logger.getLogger(this::class.qualifiedName)

    init {
        logger.info("JibriManager entering IDLE state")
    }

    override fun postStateTransition() {
        logger.info("Jibri entered IDLE state")
        idleFunc()
        jibriManager.publishStatus(JibriStatusPacketExt.Status.IDLE)
    }

    override fun startFileRecording(
        serviceParams: ServiceParams,
        fileRecordingRequestParams: FileRecordingRequestParams,
        environmentContext: EnvironmentContext?,
        serviceStatusHandlers: List<JibriServiceStatusHandler>
    ): JibriManagerState {
        logger.info("Starting a file recording with params: $fileRecordingRequestParams " +
                "finalize script path: ${jibriManager.config.finalizeRecordingScriptPath} and " +
                "recordings directory: ${jibriManager.config.recordingDirectory}")
        val service = serviceFactory.createFileRecordingService(
            FileRecordingParams(
                fileRecordingRequestParams.callParams,
                fileRecordingRequestParams.callLoginParams,
                jibriManager.config.finalizeRecordingScriptPath,
                jibriManager.config.recordingDirectory
            )
        )
        return startService(service, serviceParams, environmentContext, serviceStatusHandlers)
    }

    override fun startStreaming(
        serviceParams: ServiceParams,
        streamingParams: StreamingParams,
        environmentContext: EnvironmentContext?,
        serviceStatusHandlers: List<JibriServiceStatusHandler>
    ): JibriManagerState {
        logger.info("Starting a stream with params: $serviceParams $streamingParams")
        val service = serviceFactory.createStreamingJibriService(streamingParams)
        return startService(service, serviceParams, environmentContext, serviceStatusHandlers)
    }

    override fun startSipGateway(
        serviceParams: ServiceParams,
        sipGatewayServiceParams: SipGatewayServiceParams,
        environmentContext: EnvironmentContext?,
        serviceStatusHandlers: List<JibriServiceStatusHandler>
    ): JibriManagerState {
        logger.info("Starting a SIP gateway with params: $serviceParams $sipGatewayServiceParams")
        val service = serviceFactory.createSipGatewayJibriService(SipGatewayServiceParams(
            sipGatewayServiceParams.callParams,
            sipGatewayServiceParams.sipClientParams
        ))
        return startService(service, serviceParams, environmentContext, serviceStatusHandlers)
    }

    private fun startService(
        jibriService: JibriService,
        serviceParams: ServiceParams,
        environmentContext: EnvironmentContext?,
        serviceStatusHandlers: List<JibriServiceStatusHandler>
    ): JibriManagerState {
        return try {
            Busy(jibriManager, jibriService, serviceParams.usageTimeoutMinutes, environmentContext, serviceStatusHandlers)
        } catch (e: StateTransitionException) {
            this
        }
    }
    override fun stopService(): JibriManagerState = this

    override fun healthCheck(): JibriHealth {
        return JibriHealth(false)
    }

    override fun executeWhenIdle(func: () -> Unit) {
        func()
    }
}
