package cromwell.backend.sfs.config

import common.assertion.CromwellTimeoutSpec
import cromwell.backend.impl.sfs.config.DeclarationValidation
import cromwell.backend.validation.ValidatedRuntimeAttributes
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineMV
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import wdl.draft2.model._
import wom.types.WomIntegerType
import wom.values.WomInteger

class DeclarationValidationSpec
    extends AnyFlatSpec
    with CromwellTimeoutSpec
    with Matchers
    with TableDrivenPropertyChecks {
  behavior of "DeclarationValidation"

  def validateCpu(key: String) = {
    val expression = WdlExpression.fromString("5")
    val declarationValidation = DeclarationValidation.fromDeclaration(callCachedRuntimeAttributesMap = Map.empty)(
      Declaration(WomIntegerType, key, Option(expression), None, null)
    )
    declarationValidation.extractWdlValueOption(
      ValidatedRuntimeAttributes(Map(key -> refineMV[Positive](5)))
    ) shouldBe Some(WomInteger(5))
  }

  it should "validate cpu attributes" in {
    val keys = Table(
      "key",
      "cpu",
      "cpuMin",
      "cpuMax"
    )

    forAll(keys)(validateCpu)
  }
}
