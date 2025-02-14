package cromwell.languages.util

import com.softwaremill.sttp._

import java.nio.file.{Files, Paths}
import common.assertion.CromwellTimeoutSpec
import common.assertion.ErrorOrAssertions._
import cromwell.core.WorkflowId
import cromwell.core.path.DefaultPath
import cromwell.languages.util.ImportResolver.{DirectoryResolver, HttpResolver}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ImportResolverSpec extends AnyFlatSpec with CromwellTimeoutSpec with Matchers {
  behavior of "HttpResolver"

  val relativeToGithubRoot = "https://raw.githubusercontent.com/broadinstitute/cromwell/develop/"
  val resolvedHttpPath = relativeToGithubRoot + "centaur/src/main/resources/standardTestCases/hello/hello.wdl"

  val canon = Map(
    "http://abc.com:8000/blah?x=5&y=10" -> "http://abc.com:8000/blah?x=5&y=10",
    "http://abc.com:8000/bob/loblaw/law/blog/../../blah/blah" -> "http://abc.com:8000/bob/loblaw/blah/blah",
    "http://abc.com:8000/bob/loblaw/law/blog/..//../blah/blah" -> "http://abc.com:8000/bob/loblaw/blah/blah"
  )

  canon foreach { case (from, to) =>
    it should s"canonicalize '$from' correctly as $to" in {
      HttpResolver.canonicalize(from) should be(to)
    }
  }

  val roots = Map(
    "http://abc.com:8000/blah?x=5&y=10" -> "http://abc.com:8000",
    "https://raw.githubusercontent.com/broadinstitute/cromwell/1c41a263e9f251c9fedfec6e6e8cbce2dc3e61c6/centaur/src/main/resources/standardTestCases/biscayne_http_relative_imports.test" -> "https://raw.githubusercontent.com"
  )

  roots foreach { case (from, to) =>
    it should s"root '$from' correctly as $to" in {
      HttpResolver.root(from) should be(to)
    }
  }

  it should "resolve a path from no initial root" in {
    val resolver = HttpResolver(None, Map.empty, None)
    val toResolve = resolver.pathToLookup("http://abc.com:8000/blah1/blah2.wdl")
    toResolve shouldBeValid "http://abc.com:8000/blah1/blah2.wdl"
  }

  it should "resolve a path and store the import in ResolvedImportRecord" in {
    val resolver = HttpResolver(None, Map.empty, None)
    val importUri =
      "https://raw.githubusercontent.com/broadinstitute/cromwell/develop/centaur/src/main/resources/standardTestCases/hello/hello.wdl"
    val resolvedBundle = resolver.innerResolver(importUri, List(resolver))

    resolvedBundle.map(_.resolvedImportRecord) match {
      case Left(e) => fail(s"Expected ResolvedImportBundle but got $e")
      case Right(resolvedImport) => resolvedImport.importPath shouldBe resolvedHttpPath
    }
  }

  behavior of "HttpResolver with allowList"

  val allowList = Option(List("my.favorite.wdls.com", "anotherwdlsite.org"))
  val pathEnd = "bob/loblaw/blah/blah.wdl"

  it should "allow any import when there is no allow list" in {
    val resolver = HttpResolver(None, Map.empty, None)
    resolver.isAllowed(uri"https://my.favorite.wdls.com/$pathEnd") shouldBe true
    resolver.isAllowed(uri"http://some-garbage.whatever.eu/$pathEnd") shouldBe true
    resolver.isAllowed(uri"localhost:8080/my/secrets") shouldBe true
  }

  it should "allow any import that's on the allow list" in {
    val resolver = HttpResolver(None, Map.empty, allowList)
    resolver.isAllowed(uri"https://my.favorite.wdls.com/$pathEnd") shouldBe true
    resolver.isAllowed(uri"http://anotherwdlsite.org/$pathEnd") shouldBe true
    resolver.isAllowed(uri"https://yetanotherwdlsite.org/$pathEnd") shouldBe false
    resolver.isAllowed(uri"https://FOO.my.favorite.wdls.com/$pathEnd") shouldBe false
    resolver.isAllowed(uri"https://wdls.com/$pathEnd") shouldBe false
    resolver.isAllowed(uri"localhost:8080/my/secrets") shouldBe false
  }

  it should "allow nothing with an empty allow list" in {
    val resolver = HttpResolver(None, Map.empty, Option(List.empty))
    resolver.isAllowed(uri"https://my.favorite.wdls.com/$pathEnd") shouldBe false
    resolver.isAllowed(uri"http://anotherwdlsite.org/$pathEnd") shouldBe false
    resolver.isAllowed(uri"https://yetanotherwdlsite.org/$pathEnd") shouldBe false
    resolver.isAllowed(uri"https://FOO.my.favorite.wdls.com/$pathEnd") shouldBe false
    resolver.isAllowed(uri"https://wdls.com/$pathEnd") shouldBe false
    resolver.isAllowed(uri"localhost:8080/my/secrets") shouldBe false
  }

  behavior of "HttpResolver with a 'relativeTo' value"

  val relativeHttpResolver = HttpResolver(relativeTo = Some("http://abc.com:8000/blah1/blah2/"), Map.empty, None)
  val relativeToGithubHttpResolver = HttpResolver(relativeTo = Some(relativeToGithubRoot), Map.empty, None)

  it should "resolve an absolute path from a different initial root" in {
    val pathToLookup = relativeHttpResolver.pathToLookup("http://def.org:8080/blah3.wdl")
    pathToLookup shouldBeValid "http://def.org:8080/blah3.wdl"
  }

  it should "resolve an absolute path from a different initial root and store it in ResolvedImportRecord" in {
    val importUri = "https://github.com/DataBiosphere/job-manager/blob/master/CHANGELOG.md"
    val resolvedBundle = relativeToGithubHttpResolver.innerResolver(importUri, List(relativeToGithubHttpResolver))

    resolvedBundle.map(_.resolvedImportRecord) match {
      case Left(e) => fail(s"Expected ResolvedImportBundle but got $e")
      case Right(resolvedImport) => resolvedImport.importPath shouldBe importUri
    }
  }

  it should "resolve a relative path" in {
    val pathToLookup = relativeHttpResolver.pathToLookup("tools/cool_tool.wdl")
    pathToLookup shouldBeValid "http://abc.com:8000/blah1/blah2/tools/cool_tool.wdl"
  }

  it should "resolve a relative path and store it in ResolvedImportRecord" in {
    val importUri = "centaur/src/main/resources/standardTestCases/hello/hello.wdl"
    val resolvedBundle = relativeToGithubHttpResolver.innerResolver(importUri, List(relativeToGithubHttpResolver))

    resolvedBundle.map(_.resolvedImportRecord) match {
      case Left(e) => fail(s"Expected ResolvedImportBundle but got $e")
      case Right(resolvedImport) => resolvedImport.importPath shouldBe resolvedHttpPath
    }
  }

  it should "resolve a backtracking relative path" in {
    val pathToLookup = relativeHttpResolver.pathToLookup("../tools/cool_tool.wdl")
    pathToLookup shouldBeValid "http://abc.com:8000/blah1/tools/cool_tool.wdl"
  }

  it should "resolve a backtracking relative path and store it in ResolvedImportRecord" in {
    val importUri = "../develop/centaur/src/main/resources/standardTestCases/hello/hello.wdl"
    val resolvedBundle = relativeToGithubHttpResolver.innerResolver(importUri, List(relativeToGithubHttpResolver))

    resolvedBundle.map(_.resolvedImportRecord) match {
      case Left(e) => fail(s"Expected ResolvedImportBundle but got $e")
      case Right(resolvedImport) => resolvedImport.importPath shouldBe resolvedHttpPath
    }
  }

  it should "resolve from '/'" in {
    val pathToLookup = relativeHttpResolver.pathToLookup("/tools/cool_tool.wdl")
    pathToLookup shouldBeValid "http://abc.com:8000/tools/cool_tool.wdl"
  }

  it should "resolve from '/' and store it in ResolvedImportRecord" in {
    val importUri = "/broadinstitute/cromwell/develop/centaur/src/main/resources/standardTestCases/hello/hello.wdl"
    val resolvedBundle = relativeToGithubHttpResolver.innerResolver(importUri, List(relativeToGithubHttpResolver))

    resolvedBundle.map(_.resolvedImportRecord) match {
      case Left(e) => fail(s"Expected ResolvedImportBundle but got $e")
      case Right(resolvedImport) => resolvedImport.importPath shouldBe resolvedHttpPath
    }
  }

  behavior of "directory resolver from root"

  val workingDirectory = sys.props("user.dir")
  val rootDirectoryResolver =
    DirectoryResolver(DefaultPath(Paths.get("/")), customName = None, deleteOnClose = false, directoryHash = None)

  it should "resolve a random path" in {
    val pathToLookup = rootDirectoryResolver.resolveAndMakeAbsolute("/path/to/file.wdl")
    pathToLookup shouldBeValid Paths.get("/path/to/file.wdl")
  }

  it should "resolve sampleWorkflow path and store absolute path in ResolvedImportRecord" in {
    val path = s"$workingDirectory/languageFactories/language-factory-core/src/test/resources/sampleWorkflow.wdl"
    val resolvedBundle = rootDirectoryResolver.innerResolver(path, List(rootDirectoryResolver))

    resolvedBundle.map(_.resolvedImportRecord) match {
      case Left(e) => fail(s"Expected ResolvedImportBundle but got $e")
      case Right(resolvedImport) => resolvedImport.importPath shouldBe path
    }
  }

  behavior of "unprotected relative directory resolver"

  val relativeDirectoryResolver = DirectoryResolver(DefaultPath(Paths.get("/path/to/imports/")),
                                                    customName = None,
                                                    deleteOnClose = false,
                                                    directoryHash = None
  )

  val relativeDirForSampleWf = s"$workingDirectory/languageFactories/language-factory-core/src/test/"
  val relativeDirResolverForSampleWf = DirectoryResolver(DefaultPath(Paths.get(relativeDirForSampleWf)),
                                                         customName = None,
                                                         deleteOnClose = false,
                                                         directoryHash = None
  )

  it should "resolve an absolute path" in {
    val pathToLookup = relativeDirectoryResolver.resolveAndMakeAbsolute("/path/to/file.wdl")
    pathToLookup shouldBeValid Paths.get("/path/to/file.wdl")
  }

  it should "resolve an absolute path of sampleWorkflow" in {
    val path = relativeDirForSampleWf + "resources/sampleWorkflow.wdl"
    val resolvedBundle = relativeDirResolverForSampleWf.innerResolver(path, List(relativeDirResolverForSampleWf))

    resolvedBundle.map(_.resolvedImportRecord) match {
      case Left(e) => fail(s"Expected ResolvedImportBundle but got $e")
      case Right(resolvedImport) => resolvedImport.importPath shouldBe path
    }
  }

  it should "resolve a relative path" in {
    val pathToLookup = relativeDirectoryResolver.resolveAndMakeAbsolute("path/to/file.wdl")
    pathToLookup shouldBeValid Paths.get("/path/to/imports/path/to/file.wdl")
  }

  it should "resolve a relative path for SampleWorkflow" in {
    val path = "resources/sampleWorkflow.wdl"
    val resolvedBundle = relativeDirResolverForSampleWf.innerResolver(path, List(relativeDirResolverForSampleWf))

    resolvedBundle.map(_.resolvedImportRecord) match {
      case Left(e) => fail(s"Expected ResolvedImportBundle but got $e")
      case Right(resolvedImport) => resolvedImport.importPath shouldBe (relativeDirForSampleWf + path)
    }
  }

  behavior of "protected relative directory resolver"

  val protectedRelativeDirectoryResolver = DirectoryResolver(DefaultPath(Paths.get("/path/to/imports/")),
                                                             Some("/path/to/imports/"),
                                                             customName = None,
                                                             deleteOnClose = false,
                                                             directoryHash = None
  )
  val protectedRelativeDirResolverForSampleWf = DirectoryResolver(DefaultPath(Paths.get(relativeDirForSampleWf)),
                                                                  Some(relativeDirForSampleWf),
                                                                  customName = None,
                                                                  deleteOnClose = false,
                                                                  directoryHash = None
  )

  it should "resolve a good relative path" in {
    val pathToLookup = protectedRelativeDirectoryResolver.resolveAndMakeAbsolute("path/to/file.wdl")
    pathToLookup shouldBeValid Paths.get("/path/to/imports/path/to/file.wdl")
  }

  it should "resolve a good relative path to sampleWorkflow" in {
    val path = "resources/sampleWorkflow.wdl"
    val resolvedBundle =
      protectedRelativeDirResolverForSampleWf.innerResolver(path, List(protectedRelativeDirResolverForSampleWf))

    resolvedBundle.map(_.resolvedImportRecord) match {
      case Left(e) => fail(s"Expected ResolvedImportBundle but got $e")
      case Right(resolvedImport) => resolvedImport.importPath shouldBe (relativeDirForSampleWf + path)
    }
  }

  it should "not resolve an absolute path" in {
    val pathToLookup = protectedRelativeDirectoryResolver.resolveAndMakeAbsolute("/path/to/file.wdl")
    pathToLookup shouldBeInvalid "/path/to/file.wdl is not an allowed import path"
  }

  it should "not resolve outside of its protected directory" in {
    val pathToLookup = protectedRelativeDirectoryResolver.resolveAndMakeAbsolute("../file.wdl")
    pathToLookup shouldBeInvalid "../file.wdl is not an allowed import path"
  }

  it should "resolve paths in zips" in {
    val zipResource = getClass.getResource("/QC.zip").toURI
    val zipBytes = Files.readAllBytes(Paths.get(zipResource))
    val resolvers = ImportResolver.zippedImportResolver(zipBytes, WorkflowId.randomId())

    // Not the most elegant way to get the paths, but gets the job done.
    val resolver = resolvers.toList.head
    resolver.resolveAndMakeAbsolute("QC.wdl").map(Files.exists(_)).toOption shouldBe Some(true)
    resolver.resolveAndMakeAbsolute("tasks/cutadapt.wdl").map(Files.exists(_)).toOption shouldBe Some(true)
    resolver.resolveAndMakeAbsolute("tasks/fastqc.wdl").map(Files.exists(_)).toOption shouldBe Some(true)
    // Make sure above testing is correct by testing for a non-existent wdl.
    resolver.resolveAndMakeAbsolute("machine_learning_skynet.wdl").map(Files.exists(_)).toOption shouldBe Some(false)
  }

}
