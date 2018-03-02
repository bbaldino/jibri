package org.jitsi.jibri

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import net.sourceforge.argparse4j.ArgumentParsers
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder
import org.glassfish.jersey.jackson.JacksonFeature
import org.glassfish.jersey.server.ResourceConfig
import org.glassfish.jersey.servlet.ServletContainer
import org.jitsi.jibri.api.http.HttpApi
import org.jitsi.jibri.api.http.internal.InternalHttpApi
import org.jitsi.jibri.api.xmpp.XmppApi
import org.jitsi.jibri.config.JibriConfig
import org.jitsi.jibri.util.extensions.error
import java.io.File
import java.util.logging.Logger
import javax.ws.rs.ext.ContextResolver

val logger: Logger = Logger.getLogger("org.jitsi.jibri.Main")

fun loadConfig(configFile: File): JibriConfig {
    val config: JibriConfig = jacksonObjectMapper()
        .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
        .readValue(configFile)
    logger.info("Parsed config:\n$config")
    return config
}

fun main(args: Array<String>) {
    val argParser = ArgumentParsers.newFor("Jibri").build()
            .defaultHelp(true)
            .description("Start Jibri")
    argParser.addArgument("-c", "--config")
            .required(true)
            .type(String::class.java)
            .help("Path to the jibri config file")

    val ns = argParser.parseArgs(args)
    val configFilePath = ns.getString("config")
    logger.info("Using config file $configFilePath")

    val jibriConfigFile = File(configFilePath)
    if (!jibriConfigFile.exists()) {
        logger.error("Error: Config file $configFilePath doesn't exist")
        System.exit(1)
    }
    val jibriConfig = loadConfig(jibriConfigFile)

    val jibri = JibriManager(jibriConfig)

    // InternalHttpApi
    val internalApiThread = Thread {
        val internalHttpApi = InternalHttpApi {
            logger.info("The config file has changed, waiting for Jibri to be idle before exiting")
            jibri.executeWhenIdle {
                logger.info("Jibri is idle and there are config file changes, exiting")
                // Exit so we can be restarted and load the new config
                System.exit(0)
            }
        }
        val jerseyConfig = ResourceConfig()
        jerseyConfig.register(internalHttpApi)
            .register(ContextResolver<ObjectMapper> { ObjectMapper().registerModule(KotlinModule()) })
            .register(JacksonFeature::class.java)

        val servlet = ServletHolder(ServletContainer(jerseyConfig))

        val server = Server(3333)
        val context = ServletContextHandler(server, "/*")
        context.addServlet(servlet, "/*")

        try {
            server.start()
            server.join()
        } catch (e: Exception) {
            logger.error("Error with internal HTTP API server: $e")
        } finally {
            server.destroy()
        }
    }
    internalApiThread.start()
    // XmppApi
    val xmppApi = XmppApi(jibriManager = jibri, xmppConfigs = jibriConfig.xmppEnvironments)
    xmppApi.start()

    // HttpApi
    Thread {
        val jerseyConfig = ResourceConfig()
        jerseyConfig.register(HttpApi(jibri))
            .register(ContextResolver<ObjectMapper> { ObjectMapper().registerModule(KotlinModule()) })
            .register(JacksonFeature::class.java)

        val servlet = ServletHolder(ServletContainer(jerseyConfig))

        val server = Server(2222)
        val context = ServletContextHandler(server, "/*")
        context.addServlet(servlet, "/*")

        try {
            server.start()
            server.join()
        } catch (e: Exception) {
            logger.error("Error with HTTP API server: $e")
        } finally {
            server.destroy()
        }
    }.start()

    // Wait on the internal API thread to prevent Main from exiting
    internalApiThread.join()
}
