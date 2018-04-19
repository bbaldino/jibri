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

import org.jitsi.jibri.health.EnvironmentContext
import org.jitsi.jibri.manager.JibriManager
import org.jitsi.jibri.service.JibriService
import org.jitsi.jibri.service.JibriServiceFactory
import org.jitsi.jibri.service.JibriServiceStatusHandler
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

class StateFactory {
    fun createIdleState(
        jibriManager: JibriManager,
        idleFunc: () -> Unit = {},
        serviceFactory: JibriServiceFactory = JibriServiceFactory()
    ) = Idle(jibriManager, idleFunc, serviceFactory)

    @Throws(StateTransitionException::class)
    fun createBusyState(
        jibriManager: JibriManager,
        activeService: JibriService,
        usageTimeoutMinutes: Int,
        environmentContext: EnvironmentContext? = null,
        serviceStatusHandlers: List<JibriServiceStatusHandler>,
        executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    ) = Busy(jibriManager, activeService, usageTimeoutMinutes, environmentContext, serviceStatusHandlers, executor)
}
