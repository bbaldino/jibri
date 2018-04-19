package org.jitsi.jibri.manager

import net.java.sip.communicator.impl.protocol.jabber.extensions.jibri.JibriStatusPacketExt
import org.jitsi.jibri.config.JibriConfig
import org.jitsi.jibri.config.XmppCredentials
import org.jitsi.jibri.health.EnvironmentContext
import org.jitsi.jibri.health.JibriHealth
import org.jitsi.jibri.manager.state.AlreadyBusyException
import org.jitsi.jibri.manager.state.Busy
import org.jitsi.jibri.manager.state.Idle
import org.jitsi.jibri.manager.state.JibriManagerState
import org.jitsi.jibri.selenium.CallParams
import org.jitsi.jibri.service.JibriService
import org.jitsi.jibri.service.JibriServiceStatus
import org.jitsi.jibri.service.JibriServiceStatusHandler
import org.jitsi.jibri.service.ServiceParams
import org.jitsi.jibri.service.impl.FileRecordingJibriService
import org.jitsi.jibri.service.impl.FileRecordingParams
import org.jitsi.jibri.service.impl.SipGatewayServiceParams
import org.jitsi.jibri.service.impl.StreamingJibriService
import org.jitsi.jibri.service.impl.StreamingParams
import org.jitsi.jibri.util.StatusPublisher
import org.jitsi.jibri.util.extensions.error
import java.util.logging.Logger

enum class StartServiceResult {
    SUCCESS,
    BUSY,
    ERROR
}

/**
 * Some of the values in [FileRecordingParams] come from the configuration
 * file, so the incoming request won't contain all of them.  This class
 * models the subset of values which will come in the request.
 */
data class FileRecordingRequestParams(
    /**
     * Which call we'll join
     */
    val callParams: CallParams,
    /**
     * The login information needed to appear invisible in
     * the call
     */
    val callLoginParams: XmppCredentials
)

class JibriManager(val config: JibriConfig) : StatusPublisher<JibriStatusPacketExt.Status>() {
    private val logger = Logger.getLogger(this::class.qualifiedName)
    /**
     * The current state of this [JibriManager].  The state object implements
     * the proper behavior for each interface call for that state.
     */
    private var state: JibriManagerState = Idle(this)

    public override fun publishStatus(status: JibriStatusPacketExt.Status) {
        super.publishStatus(status)
    }

    private fun transitionToState(newState: JibriManagerState) {
        state = newState
        state.postStateTransition()
    }

    private fun handleServiceStopped(status: JibriServiceStatus) {
        when (status) {
            JibriServiceStatus.FINISHED -> logger.info("Service finished cleanly, shutting down")
            JibriServiceStatus.ERROR -> logger.error("Service finished with error, shutting down")
        }
        stopService()
    }

    /**
     * Starts a [FileRecordingJibriService] to record the call described
     * in the params to a file.  Returns a [StartServiceResult] to denote
     * whether the service was started successfully or not.
     */
    @Synchronized
    fun startFileRecording(
        serviceParams: ServiceParams,
        fileRecordingRequestParams: FileRecordingRequestParams,
        environmentContext: EnvironmentContext? = null,
        serviceStatusHandlers: List<JibriServiceStatusHandler>
    ): StartServiceResult {
        return try {
            val newState =
                state.startFileRecording(
                    serviceParams,
                    fileRecordingRequestParams,
                    environmentContext,
                    serviceStatusHandlers + ::handleServiceStopped
                )
            transitionToState(newState)
            when (newState) {
                is Idle -> StartServiceResult.ERROR
                is Busy -> StartServiceResult.SUCCESS
            }
        } catch (e: AlreadyBusyException) {
            StartServiceResult.BUSY
        }
    }

    /**
     * Starts a [StreamingJibriService] to capture the call according
     * to [streamingParams].  Returns a [StartServiceResult] to
     * denote whether the service was started successfully or not.
     */
    @Synchronized
    fun startStreaming(
        serviceParams: ServiceParams,
        streamingParams: StreamingParams,
        environmentContext: EnvironmentContext? = null,
        serviceStatusHandlers: List<JibriServiceStatusHandler>
    ): StartServiceResult {
        return try {
            val newState =
                state.startStreaming(
                    serviceParams,
                    streamingParams,
                    environmentContext,
                    serviceStatusHandlers + ::handleServiceStopped
                )
            transitionToState(newState)
            when (newState) {
                is Idle -> StartServiceResult.ERROR
                is Busy -> StartServiceResult.SUCCESS
            }
        } catch (e: AlreadyBusyException) {
            StartServiceResult.BUSY
        }
    }

    fun startSipGateway(
        serviceParams: ServiceParams,
        sipGatewayServiceParams: SipGatewayServiceParams,
        environmentContext: EnvironmentContext? = null,
        serviceStatusHandlers: List<JibriServiceStatusHandler>
    ): StartServiceResult {
        return try {
            val newState =
                state.startSipGateway(
                    serviceParams,
                    sipGatewayServiceParams,
                    environmentContext,
                    serviceStatusHandlers + ::handleServiceStopped
                )
            transitionToState(newState)
            when (newState) {
                is Idle -> StartServiceResult.ERROR
                is Busy -> StartServiceResult.SUCCESS
            }
        } catch (e: AlreadyBusyException) {
            StartServiceResult.BUSY
        }
    }

    /**
     * Stop the currently active [JibriService], if there is one
     */
    @Synchronized
    fun stopService() {
        val newState = state.stopService()
        transitionToState(newState)
    }

    /**
     * Returns an object describing the "health" of this Jibri
     */
    @Synchronized
    fun healthCheck(): JibriHealth = state.healthCheck()

    /**
     * Execute the given function the next time Jibri is idle
     */
    @Synchronized
    fun executeWhenIdle(func: () -> Unit) = state.executeWhenIdle(func)
}
