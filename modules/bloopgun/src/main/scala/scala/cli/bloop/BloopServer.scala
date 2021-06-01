package scala.cli.bloop

import java.io.{ByteArrayInputStream, InputStream, IOException}
import java.net.{ConnectException, Socket}
import java.nio.file.Path
import java.util.concurrent.{Future => JFuture, ScheduledExecutorService}

import ch.epfl.scala.bsp4j
import org.eclipse.lsp4j.jsonrpc

import scala.annotation.tailrec
import scala.cli.bloop.bloop4j.BloopExtraBuildParams
import scala.cli.bloop.bloopgun.internal.Constants
import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.cli.bloop.bloopgun.BloopgunLogger

trait BloopServer {
  def server: BuildServer
  def listeningFuture: JFuture[Void]
  def socket: Socket

  def shutdown(): Unit
}

object BloopServer {

  private case class BloopServerImpl(
    server: BuildServer,
    listeningFuture: JFuture[Void],
    socket: Socket
  ) extends BloopServer {
    def shutdown(): Unit = {
      // Close the jsonrpc thread listening to input messages
      // First line makes jsonrpc discard the closed connection exception.
      listeningFuture.cancel(true)
      socket.close()
    }
  }

  private def emptyInputStream: InputStream =
    new ByteArrayInputStream(Array.emptyByteArray)

  private def ensureBloopRunning(
    config: bloopgun.BloopgunConfig,
    startServerChecksPool: ScheduledExecutorService,
    logger: BloopgunLogger
  ): Unit = {

    val isBloopRunning = bloopgun.Bloopgun.check(config, logger)

    logger.debug(
      if (isBloopRunning) s"Bloop is running on ${config.host}:${config.port}"
      else s"No bloop daemon found on ${config.host}:${config.port}"
    )

    if (!isBloopRunning) {
      logger.debug(s"Starting bloop server version ${config.version}")
      val serverStartedFuture = bloopgun.Bloopgun.startServer(
        config,
        startServerChecksPool,
        100.millis,
        1.minute,
        logger
      )

      Await.result(serverStartedFuture, Duration.Inf)
      logger.debug("Bloop server started")
    }

  }

  private def connect(
    conn: bloopgun.BspConnection,
    period: FiniteDuration = 100.milliseconds,
    timeout: FiniteDuration = 5.seconds
  ): Socket = {

    @tailrec
    def create(stopAt: Long): Socket = {
      val maybeSocket =
        try Right(conn.openSocket())
        catch {
          case e: ConnectException => Left(e)
        }
      maybeSocket match {
        case Right(socket) => socket
        case Left(e) =>
          if (System.currentTimeMillis() >= stopAt)
            throw new IOException(s"Can't connect to ${conn.address}", e)
          else {
            Thread.sleep(period.toMillis)
            create(stopAt)
          }
      }
    }

    create(System.currentTimeMillis() + timeout.toMillis)
  }

  def buildServer(
    clientName: String,
    clientVersion: String,
    workspace: Path,
    classesDir: Path,
    buildClient: bsp4j.BuildClient,
    threads: BloopThreads,
    logger: BloopgunLogger,
    period: FiniteDuration = 100.milliseconds,
    timeout: FiniteDuration = 5.seconds
  ): BloopServer = {

    val config = bloopgun.BloopgunConfig.default.copy(
      bspStdout = logger.bloopBspStdout,
      bspStderr = logger.bloopBspStderr
    )

    ensureBloopRunning(config, threads.startServerChecks, logger)

    logger.debug("Opening BSP connection with bloop")
    val conn = bloopgun.Bloopgun.bsp(
      config,
      workspace.resolve(".scala"),
      logger
    )
    logger.debug(s"Bloop BSP connection waiting at ${conn.address}")

    val socket = connect(conn, period, timeout)

    logger.debug(s"Connected to Bloop via BSP at ${conn.address}")

    val launcher = new jsonrpc.Launcher.Builder[BuildServer]()
      .setExecutorService(threads.jsonrpc)
      .setInput(socket.getInputStream)
      .setOutput(socket.getOutputStream)
      .setRemoteInterface(classOf[BuildServer])
      .setLocalService(buildClient)
      .create()
    val server = launcher.getRemoteProxy
    buildClient.onConnectWithServer(server)

    val f = launcher.startListening()

    val initParams = new bsp4j.InitializeBuildParams(
      clientName,
      clientVersion,
      Constants.bspVersion,
      workspace.resolve(".scala").toUri.toASCIIString,
      new bsp4j.BuildClientCapabilities(List("scala", "java").asJava)
    )
    val bloopExtraParams = new BloopExtraBuildParams
    bloopExtraParams.setClientClassesRootDir(classesDir.toUri.toASCIIString)
    bloopExtraParams.setOwnsBuildFiles(true)
    initParams.setData(bloopExtraParams)
    logger.debug("Sending buildInitialize BSP command to Bloop")
    server.buildInitialize(initParams).get()

    server.onBuildInitialized()
    BloopServerImpl(server, f, socket)
  }

}
