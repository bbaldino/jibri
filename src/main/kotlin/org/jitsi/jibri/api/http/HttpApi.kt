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

package org.jitsi.jibri.api.http

import org.jitsi.jibri.CallParams
import org.jitsi.jibri.FileRecordingParams
import org.jitsi.jibri.JibriManager
import org.jitsi.jibri.RecordingSinkType
import org.jitsi.jibri.ServiceParams
import org.jitsi.jibri.StartServiceResult
import org.jitsi.jibri.StreamingParams
import org.jitsi.jibri.service.impl.SipGatewayServiceParams
import org.jitsi.jibri.sipgateway.SipClientParams
import org.jitsi.jibri.util.extensions.debug
import org.jitsi.jibri.util.extensions.error
import java.util.logging.Logger
import javax.ws.rs.Consumes
import javax.ws.rs.GET
import javax.ws.rs.POST
import javax.ws.rs.Path
import javax.ws.rs.Produces
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

// TODO: this needs to include usageTimeout
data class StartServiceParams(
    val callParams: CallParams,
    val sinkType: RecordingSinkType,
    val youTubeStreamKey: String? = null
    /**
     * Params to be used if [RecordingSinkType] is [RecordingSinkType.GATEWAY]
     */
    val sipClientParams: SipClientParams? = null
)

/**
 * The [HttpApi] is for starting and stopping the various Jibri services via the
 * [JibriManager], as well as retrieving the health and status of this Jibri
 */
@Path("/jibri/api/v1.0")
class HttpApi(private val jibriManager: JibriManager) {
    private val logger = Logger.getLogger(this::class.qualifiedName)

    /**
     * Get the health of this Jibri in the format of a json-encoded
     * [org.jitsi.jibri.health.JibriHealth] object
     */
    @GET
    @Path("health")
    @Produces(MediaType.APPLICATION_JSON)
    fun health(): Response {
        logger.debug("Got health request")
        return Response.ok(jibriManager.healthCheck()).build()
    }

    /**
     * [startService] will start a new service using the given [StartServiceParams].
     * Returns a response with [Response.Status.OK] on success, [Response.Status.PRECONDITION_FAILED]
     * if this Jibri is already busy and [Response.Status.INTERNAL_SERVER_ERROR] on error
     */
    @POST
    @Path("startService")
    @Consumes(MediaType.APPLICATION_JSON)
    fun startService(serviceParams: StartServiceParams): Response {
        logger.debug("Got a start service request with params $serviceParams")
        val result: StartServiceResult = when (serviceParams.sinkType) {
            RecordingSinkType.FILE -> run {
                jibriManager.startFileRecording(
                    ServiceParams(usageTimeoutMinutes = 0),
                    FileRecordingParams(startServiceParams.callParams)
                )
            }
            RecordingSinkType.STREAM -> run {
                val youTubeStreamKey = serviceParams.youTubeStreamKey ?: return@run StartServiceResult.ERROR
                jibriManager.startStreaming(
                    ServiceParams(usageTimeoutMinutes = 0),
                    StreamingParams(startServiceParams.callParams, youTubeStreamKey),
                    environmentContext = null
                )
            }
            RecordingSinkType.GATEWAY -> {
                startServiceParams.sipClientParams?.let {
                    jibriManager.startSipGateway(
                        ServiceParams(usageTimeoutMinutes = 0),
                        SipGatewayServiceParams(
                            startServiceParams.callParams,
                            it)
                    )
                } ?: run {
                    logger.error("SipGatewayService requested, but no SIP params passed")
                    StartServiceResult.ERROR
                }
            }
        }
        return when (result) {
            StartServiceResult.SUCCESS -> Response.ok().build()
            StartServiceResult.BUSY -> Response.status(Response.Status.PRECONDITION_FAILED).build()
            StartServiceResult.ERROR -> Response.status(Response.Status.INTERNAL_SERVER_ERROR).build()
        }
    }

    /**
     * [stopService] will stop the current service immediately
     */
    @POST
    @Path("stopService")
    fun stopService(): Response {
        logger.debug("Got stop service request")
        jibriManager.stopService()
        return Response.ok().build()
    }
}
