package cloud.nio.impl.drs

import common.validation.ErrorOr.ErrorOr

case class EngineDrsPathResolver(drsConfig: DrsConfig, drsCredentials: DrsCredentials)
    extends DrsPathResolver(drsConfig) {

  override def getAccessToken: ErrorOr[String] = drsCredentials.getAccessToken
}
