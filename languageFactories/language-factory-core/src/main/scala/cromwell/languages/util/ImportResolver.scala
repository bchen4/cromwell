package cromwell.languages.util

import java.net.{URI, URL}
import java.nio.file.Paths
import better.files.File
import cats.data.NonEmptyList
import cats.effect.{ContextShift, IO}
import cats.syntax.either._
import cats.syntax.validated._
import com.softwaremill.sttp._
import com.softwaremill.sttp.asynchttpclient.cats.AsyncHttpClientCatsBackend
import com.typesafe.config.ConfigFactory
import common.Checked
import common.transforms.CheckedAtoB
import common.validation.ErrorOr._
import common.validation.Checked._
import common.validation.Validation._
import cromwell.core.path.{DefaultPathBuilder, Path}
import net.ceedubs.ficus.Ficus._

import java.nio.file.{Path => NioPath}
import java.security.MessageDigest
import cromwell.core.WorkflowId
import wom.ResolvedImportRecord
import wom.core.WorkflowSource
import wom.values._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success, Try}

object ImportResolver {

  case class ImportResolutionRequest(toResolve: String, currentResolvers: List[ImportResolver])
  case class ResolvedImportBundle(source: WorkflowSource,
                                  newResolvers: List[ImportResolver],
                                  resolvedImportRecord: ResolvedImportRecord
  )

  trait ImportResolver {
    def name: String
    protected def innerResolver(path: String, currentResolvers: List[ImportResolver]): Checked[ResolvedImportBundle]
    def resolver: CheckedAtoB[ImportResolutionRequest, ResolvedImportBundle] = CheckedAtoB.fromCheck { request =>
      innerResolver(request.toResolve, request.currentResolvers).contextualizeErrors(
        s"resolve '${request.toResolve}' using resolver: '$name'"
      )
    }
    def cleanupIfNecessary(): ErrorOr[Unit]

    // Used when checking that imports are unchanged when caching parse results.
    // If it's impossible or infeasible to guarantee that imports are unchanged, returns an invalid response.
    def hashKey: ErrorOr[String]
  }

  object DirectoryResolver {
    private def apply(directory: Path,
                      allowEscapingDirectory: Boolean,
                      customName: Option[String]
    ): DirectoryResolver = {
      val dontEscapeFrom = if (allowEscapingDirectory) None else Option(directory.toJava.getCanonicalPath)
      DirectoryResolver(directory, dontEscapeFrom, customName, deleteOnClose = false, directoryHash = None)
    }

    def localFilesystemResolvers(baseWdl: Option[Path]) = List(
      DirectoryResolver(
        DefaultPathBuilder.build(Paths.get(".")),
        allowEscapingDirectory = true,
        customName = None
      ),
      DirectoryResolver(
        DefaultPathBuilder.build(Paths.get("/")),
        allowEscapingDirectory = false,
        customName = Some("entire local filesystem (relative to '/')")
      )
    ) ++ baseWdl.toList.map { rt =>
      DirectoryResolver(
        DefaultPathBuilder.build(Paths.get(rt.toAbsolutePath.toFile.getParent)),
        allowEscapingDirectory = true,
        customName = None
      )
    }
  }

  case class DirectoryResolver(directory: Path,
                               dontEscapeFrom: Option[String] = None,
                               customName: Option[String],
                               deleteOnClose: Boolean,
                               directoryHash: Option[String]
  ) extends ImportResolver {
    lazy val absolutePathToDirectory: String = directory.toJava.getCanonicalPath

    override def innerResolver(path: String, currentResolvers: List[ImportResolver]): Checked[ResolvedImportBundle] = {

      def updatedResolverSet(oldRootDirectory: Path,
                             newRootDirectory: Path,
                             current: List[ImportResolver]
      ): List[ImportResolver] =
        current map {
          case d if d == this =>
            DirectoryResolver(newRootDirectory, dontEscapeFrom, customName, deleteOnClose = false, directoryHash = None)
          case other => other
        }

      def fetchContentFromAbsolutePath(absolutePathToFile: NioPath): ErrorOr[String] =
        checkLocation(absolutePathToFile, path) flatMap { _ =>
          val file = File(absolutePathToFile)
          if (file.exists) {
            File(absolutePathToFile).contentAsString.validNel
          } else {
            s"File not found: $path".invalidNel
          }
        }

      val errorOr = for {
        resolvedPath <- resolvePath(path)
        absolutePathToFile <- makeAbsolute(resolvedPath)
        fileContents <- fetchContentFromAbsolutePath(absolutePathToFile)
        updatedResolvers = updatedResolverSet(directory, resolvedPath.parent, currentResolvers)
      } yield ResolvedImportBundle(fileContents, updatedResolvers, ResolvedImportRecord(absolutePathToFile.toString))

      errorOr.toEither
    }

    private def resolvePath(path: String): ErrorOr[Path] = Try(directory.resolve(path)).toErrorOr
    private def makeAbsolute(resolvedPath: Path): ErrorOr[NioPath] = Try(
      Paths.get(resolvedPath.toFile.getCanonicalPath)
    ).toErrorOr
    private def checkLocation(absoluteNioPath: NioPath, reportedPathIfBad: String): ErrorOr[Unit] =
      if (dontEscapeFrom.forall(absoluteNioPath.startsWith))
        ().validNel
      else
        s"$reportedPathIfBad is not an allowed import path".invalidNel

    def resolveAndMakeAbsolute(path: String): ErrorOr[NioPath] = for {
      resolved <- resolvePath(path)
      abs <- makeAbsolute(resolved)
      _ <- checkLocation(abs, path)
    } yield abs

    override lazy val name: String = (customName, dontEscapeFrom) match {
      case (Some(custom), _) => custom
      case (None, Some(dontEscapePath)) =>
        val dontEscapeFromDirname = Paths.get(dontEscapePath).getFileName.toString
        val shortPathToDirectory = absolutePathToDirectory.stripPrefix(dontEscapePath)

        val relativePathToDontEscapeFrom = s"[...]/$dontEscapeFromDirname"
        val relativePathToDirectory = s"$relativePathToDontEscapeFrom$shortPathToDirectory"

        s"relative to directory $relativePathToDirectory (without escaping $relativePathToDontEscapeFrom)"
      case (None, None) =>
        val shortPathToDirectory =
          Paths.get(absolutePathToDirectory).toFile.getCanonicalFile.toPath.getFileName.toString
        s"relative to directory [...]/$shortPathToDirectory (escaping allowed)"
    }

    override def cleanupIfNecessary(): ErrorOr[Unit] =
      if (deleteOnClose)
        Try {
          directory.delete(swallowIOExceptions = false)
          ()
        }.toErrorOr
      else
        ().validNel

    override def hashKey: ErrorOr[String] =
      directoryHash.map(_.validNel).getOrElse("No hashKey available for directory importer".invalidNel)
  }

  def zippedImportResolver(zippedImports: Array[Byte], workflowId: WorkflowId): ErrorOr[DirectoryResolver] = {

    val zipHash = new String(MessageDigest.getInstance("MD5").digest(zippedImports))
    LanguageFactoryUtil.createImportsDirectory(zippedImports, workflowId) map { dir =>
      DirectoryResolver(dir,
                        Option(dir.toJava.getCanonicalPath),
                        None,
                        deleteOnClose = true,
                        directoryHash = Option(zipHash)
      )
    }
  }

  case class HttpResolver(relativeTo: Option[String], headers: Map[String, String], hostAllowlist: Option[List[String]])
      extends ImportResolver {
    import HttpResolver._

    override def name: String = relativeTo match {
      case Some(relativeToPath) => s"http importer (relative to $relativeToPath)"
      case None => "http importer (no 'relative-to' origin)"

    }

    def newResolverList(newRoot: String): List[ImportResolver] = {
      val rootWithoutFilename = newRoot.split('/').init.mkString("", "/", "/")
      List(
        HttpResolver(relativeTo = Some(canonicalize(rootWithoutFilename)), headers, hostAllowlist)
      )
    }

    def pathToLookup(str: String): Checked[String] = relativeTo match {
      case Some(relativeToValue) =>
        if (str.startsWith("http")) canonicalize(str).validNelCheck
        else if (str.startsWith("/")) canonicalize(s"${root(relativeToValue).stripSuffix("/")}/$str").validNelCheck
        else canonicalize(s"${relativeToValue.stripSuffix("/")}/$str").validNelCheck
      case None =>
        if (str.startsWith("http")) canonicalize(str).validNelCheck
        else "Relative path".invalidNelCheck
    }

    def isAllowed(uri: Uri): Boolean = hostAllowlist match {
      case Some(hosts) => hosts.contains(uri.host)
      case None => true
    }

    override def innerResolver(str: String, currentResolvers: List[ImportResolver]): Checked[ResolvedImportBundle] =
      pathToLookup(str) flatMap { toLookup: WorkflowSource =>
        (Try {
          val uri: Uri = uri"$toLookup"

          if (isAllowed(uri)) {
            getUri(toLookup)
          } else {
            s"Disallowed domain in URI. ${uri.toString()}".invalidNelCheck
          }
        } match {
          case Success(result) => result
          case Failure(e) => s"HTTP resolver with headers had an unexpected error (${e.getMessage})".invalidNelCheck
        }).contextualizeErrors(s"download $toLookup")
      }

    private def getUri(toLookup: WorkflowSource): Either[NonEmptyList[WorkflowSource], ResolvedImportBundle] = {
      implicit val sttpBackend = HttpResolver.sttpBackend()
      val responseIO: IO[Response[WorkflowSource]] = sttp.get(uri"$toLookup").headers(headers).send()

      // temporary situation to get functionality working before
      // starting in on async-ifying the entire WdlNamespace flow
      val result: Checked[WorkflowSource] = Await.result(responseIO.unsafeToFuture(), 15.seconds).body.leftMap { e =>
        NonEmptyList(e.toString.trim, List.empty)
      }

      result map {
        ResolvedImportBundle(_, newResolverList(toLookup), ResolvedImportRecord(toLookup))
      }
    }

    override def cleanupIfNecessary(): ErrorOr[Unit] = ().validNel

    override def hashKey: ErrorOr[String] = relativeTo.toString.md5Sum.validNel
  }

  object HttpResolver {

    import common.util.IntrospectableLazy
    import common.util.IntrospectableLazy._

    def apply(relativeTo: Option[String] = None, headers: Map[String, String] = Map.empty): HttpResolver = {
      val config = ConfigFactory.load().getConfig("languages.WDL.http-allow-list")
      val allowListEnabled = config.as[Option[Boolean]]("enabled").getOrElse(false)
      val allowList: Option[List[String]] =
        if (allowListEnabled)
          config.as[Option[List[String]]]("allowed-http-hosts")
        else None

      new HttpResolver(relativeTo, headers, allowList)
    }

    val sttpBackend: IntrospectableLazy[SttpBackend[IO, Nothing]] = lazily {
      // 2.13 Beginning with sttp 1.6.x a `ContextShift` parameter is now required to construct an
      // `AsyncHttpClientCatsBackend`. There may be a more appropriate choice for backing this than the global
      // execution context, but even that appears to be a better option than the status quo in 1.5.x [2].
      import scala.concurrent.ExecutionContext.Implicits.global
      val ec: ExecutionContext = implicitly[ExecutionContext]
      implicit val cs: ContextShift[IO] = IO.contextShift(ec)

      // [1] https://github.com/softwaremill/sttp/releases/tag/v1.6.0
      // [2] https://github.com/softwaremill/sttp/issues/217#issuecomment-499874267
      AsyncHttpClientCatsBackend[IO]()
    }

    def closeBackendIfNecessary() = if (sttpBackend.exists) sttpBackend.close()

    def canonicalize(str: String): String = {
      val url = new URL(str)
      val uri = url.toURI.normalize
      uri.toString
    }

    def root(str: String): String = {
      val url = new URL(str)
      val uri = new URI(url.getProtocol, url.getUserInfo, url.getHost, url.getPort, null, null, null).normalize()
      uri.toString
    }
  }

}
