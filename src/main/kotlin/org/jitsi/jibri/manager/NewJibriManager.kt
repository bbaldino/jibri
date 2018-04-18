package org.jitsi.jibri.manager

import net.java.sip.communicator.impl.protocol.jabber.extensions.jibri.JibriStatusPacketExt
import org.jitsi.jibri.FileRecordingRequestParams
import org.jitsi.jibri.StartServiceResult
import org.jitsi.jibri.config.JibriConfig
import org.jitsi.jibri.health.EnvironmentContext
import org.jitsi.jibri.health.JibriHealth
import org.jitsi.jibri.manager.state.Idle
import org.jitsi.jibri.manager.state.JibriManagerState
import org.jitsi.jibri.service.JibriService
import org.jitsi.jibri.service.JibriServiceStatusHandler
import org.jitsi.jibri.service.ServiceParams
import org.jitsi.jibri.service.impl.FileRecordingJibriService
import org.jitsi.jibri.service.impl.SipGatewayServiceParams
import org.jitsi.jibri.service.impl.StreamingJibriService
import org.jitsi.jibri.service.impl.StreamingParams
import org.jitsi.jibri.util.StatusPublisher

class NewJibriManager(val config: JibriConfig) : StatusPublisher<JibriStatusPacketExt.Status>() {
    /**
     * A function to be executed when [NewJibriManager] enters an [Idle] state
     */
    var pendingIdleFunc: () -> Unit = {}

    /**
     * The current state of this [NewJibriManager].  The state object implements
     * the proper behavior for each interface call for that state.
     */
    var state: JibriManagerState = Idle(this)

    public override fun publishStatus(status: JibriStatusPacketExt.Status) {
        super.publishStatus(status)
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
        serviceStatusHandler: JibriServiceStatusHandler? = null
    ): StartServiceResult = state.startFileRecording(
        serviceParams, fileRecordingRequestParams, environmentContext, serviceStatusHandler)

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
        serviceStatusHandler: JibriServiceStatusHandler? = null
    ): StartServiceResult = state.startStreaming(serviceParams, streamingParams, environmentContext, serviceStatusHandler)

    fun startSipGateway(
        serviceParams: ServiceParams,
        sipGatewayServiceParams: SipGatewayServiceParams,
        environmentContext: EnvironmentContext? = null,
        serviceStatusHandler: JibriServiceStatusHandler? = null
    ): StartServiceResult = state.startSipGateway(serviceParams, sipGatewayServiceParams, environmentContext, serviceStatusHandler)

    /**
     * Stop the currently active [JibriService], if there is one
     */
    @Synchronized
    fun stopService() = state.stopService()

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
