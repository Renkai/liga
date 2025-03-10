/*
 * Copyright 2022 Rikai authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.eto.rikai.sql.spark

import ai.eto.rikai.sql.model.{Catalog, ModelSpec, Registry}
import ai.eto.rikai.sql.spark.expressions.Predict
import ai.eto.rikai.sql.spark.parser.{RikaiExtSqlParser, RikaiSparkSQLParser}
import com.thoughtworks.enableIf
import com.thoughtworks.enableIf.classpathMatches
import org.apache.spark.sql.catalyst.FunctionIdentifier
import org.apache.spark.sql.catalyst.analysis.{
  UnresolvedAttribute,
  UnresolvedFunction
}
import org.apache.spark.sql.catalyst.expressions.{
  Expression,
  ExpressionInfo,
  Literal
}
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.{SparkSession, SparkSessionExtensions}

private class MlPredictRule(val session: SparkSession)
    extends Rule[LogicalPlan] {

  val modelCatalog = Catalog.getOrCreate(session)

  private def resolveModel(
      name: String,
      arguments: Seq[Expression]
  ): Expression = {
    if (arguments.size < 2) {
      throw new UnsupportedOperationException(
        s"${name.toUpperCase} requires at least 2 parameters" +
          s", got ${arguments.size}"
      )
    }

    val model_name = arguments.head
    val model = model_name match {
      case arg: UnresolvedAttribute =>
        modelCatalog.getModel(arg.name, session)
      case arg: Literal => {
        val model =
          Registry.resolve(
            session,
            ModelSpec(name = None, uri = Some(arg.toString))
          )
        Some(model)
      }
    }
    model match {
      case Some(r: SparkRunnable) => {
        r.asSpark(arguments.drop(1))
      }
      case _ =>
        throw new UnsupportedOperationException("Unsupported model")
    }
  }

  @enableIf(classpathMatches(".*spark-catalyst_2\\.\\d+-3\\.[^01]\\..*".r))
  private def getFuncName(f: UnresolvedFunction): String = {
    // After Spark 3.2
    f.nameParts.last
  }

  @enableIf(classpathMatches(".*spark-catalyst_2\\.\\d+-3\\.1\\..*".r))
  private def getFuncName(f: UnresolvedFunction): String = {
    f.name.funcName
  }

  override def apply(plan: LogicalPlan): LogicalPlan = {
    plan.resolveExpressions {
      case f: UnresolvedFunction => {
        val funcName = getFuncName(f)
        funcName.toLowerCase match {
          case "ml_predict" => resolveModel(funcName, f.arguments)
          case _            => f
        }
      }
    }
  }
}

/** Rikai SparkSession extensions to enable Spark SQL ML.
  */
class RikaiSparkSessionExtensions extends (SparkSessionExtensions => Unit) {

  override def apply(extensions: SparkSessionExtensions): Unit = {

    extensions.injectParser((session, parser) => {
      new RikaiExtSqlParser(
        session,
        new RikaiSparkSQLParser(session, parser)
      )
    })

    // We just use a placeholder so that later we can compile a `ML_PREDICT` expression
    // to use Models.
    extensions.injectFunction(Predict.functionDescriptor)

    extensions.injectResolutionRule(session => {
      new MlPredictRule(session)
    })

  }
}
