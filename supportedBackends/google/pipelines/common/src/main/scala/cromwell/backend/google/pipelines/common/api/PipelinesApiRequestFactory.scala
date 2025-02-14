package cromwell.backend.google.pipelines.common.api

import com.google.api.client.http.HttpRequest
import cromwell.backend.BackendJobDescriptor
import cromwell.backend.google.pipelines.common.PipelinesApiConfigurationAttributes.VirtualPrivateCloudConfiguration
import cromwell.backend.google.pipelines.common._
import cromwell.backend.google.pipelines.common.api.PipelinesApiRequestFactory.CreatePipelineParameters
import cromwell.backend.google.pipelines.common.io.PipelinesApiAttachedDisk
import cromwell.backend.google.pipelines.common.monitoring.{CheckpointingConfiguration, MonitoringImage}
import cromwell.backend.standard.StandardAsyncJob
import cromwell.core.logging.JobLogger
import cromwell.core.path.Path
import wom.runtime.WomOutputRuntimeExtractor

import scala.concurrent.duration._

/**
  * The PipelinesApiRequestFactory defines the HttpRequests needed to run jobs
  */
trait PipelinesApiRequestFactory {
  def runRequest(createPipelineParameters: CreatePipelineParameters, jobLogger: JobLogger): HttpRequest
  def getRequest(job: StandardAsyncJob): HttpRequest
  def cancelRequest(job: StandardAsyncJob): HttpRequest
}

object PipelinesApiRequestFactory {

  type MountsToEnv = List[String] => Map[String, String]

  /**
    * Input parameters that are not strictly needed by the user's command but are Cromwell byproducts.
    */
  case class DetritusInputParameters(
    executionScriptInputParameter: PipelinesApiFileInput,
    monitoringScriptInputParameter: Option[PipelinesApiFileInput]
  ) {
    def all: List[PipelinesApiFileInput] = List(executionScriptInputParameter) ++ monitoringScriptInputParameter
  }

  /**
    * Output parameters that are not produced by the user's command but are Cromwell byproducts.
    */
  case class DetritusOutputParameters(
    monitoringScriptOutputParameter: Option[PipelinesApiFileOutput],
    rcFileOutputParameter: PipelinesApiFileOutput,
    memoryRetryRCFileOutputParameter: PipelinesApiFileOutput
  ) {
    def all: List[PipelinesApiFileOutput] =
      memoryRetryRCFileOutputParameter :: List(rcFileOutputParameter) ++ monitoringScriptOutputParameter
  }

  /**
    * Bundle containing all input and output parameters to a PAPI job
    * Detrituses and actual inputs / outputs to the job are separated for more clarity and to leave open the possibility
    * to treat them differently.
    */
  case class InputOutputParameters(
    detritusInputParameters: DetritusInputParameters,
    jobInputParameters: List[PipelinesApiInput],
    jobOutputParameters: List[PipelinesApiOutput],
    detritusOutputParameters: DetritusOutputParameters,
    literalInputParameters: List[PipelinesApiLiteralInput]
  ) {
    lazy val fileInputParameters: List[PipelinesApiInput] = jobInputParameters ++ detritusInputParameters.all
    lazy val fileOutputParameters: List[PipelinesApiOutput] = detritusOutputParameters.all ++ jobOutputParameters
  }

  case class CreatePipelineDockerKeyAndToken(key: String, encryptedToken: String)

  case class CreatePipelineParameters(jobDescriptor: BackendJobDescriptor,
                                      runtimeAttributes: PipelinesApiRuntimeAttributes,
                                      dockerImage: String,
                                      cloudWorkflowRoot: Path,
                                      cloudCallRoot: Path,
                                      commandScriptContainerPath: Path,
                                      logGcsPath: Path,
                                      inputOutputParameters: InputOutputParameters,
                                      projectId: String,
                                      computeServiceAccount: String,
                                      googleLabels: Seq[GoogleLabel],
                                      preemptible: Boolean,
                                      pipelineTimeout: FiniteDuration,
                                      jobShell: String,
                                      privateDockerKeyAndEncryptedToken: Option[CreatePipelineDockerKeyAndToken],
                                      womOutputRuntimeExtractor: Option[WomOutputRuntimeExtractor],
                                      adjustedSizeDisks: Seq[PipelinesApiAttachedDisk],
                                      virtualPrivateCloudConfiguration: VirtualPrivateCloudConfiguration,
                                      retryWithMoreMemoryKeys: Option[List[String]],
                                      fuseEnabled: Boolean,
                                      referenceDisksForLocalizationOpt: Option[List[PipelinesApiAttachedDisk]],
                                      monitoringImage: MonitoringImage,
                                      checkpointingConfiguration: CheckpointingConfiguration,
                                      enableSshAccess: Boolean,
                                      vpcNetworkAndSubnetworkProjectLabels: Option[VpcAndSubnetworkProjectLabelValues],
                                      dockerImageCacheDiskOpt: Option[String]
  ) {
    def literalInputs = inputOutputParameters.literalInputParameters
    def inputParameters = inputOutputParameters.fileInputParameters
    def outputParameters = inputOutputParameters.fileOutputParameters
    def allParameters = inputParameters ++ outputParameters
  }
}
