/*
 * Copyright 2024 Neandertech
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
 */

package examples

import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.ModelSerializer
import smithy4s.dynamic.DynamicSchemaIndex
import smithy4s.deriving.API
import software.amazon.smithy.model.shapes.SmithyIdlModelSerializer
import scala.jdk.CollectionConverters._
import scala.annotation.experimental

/**
  * Prints a smithy representation of the derived service. Requires the "smithy-model" library (JVM only)
  */
@experimental
object printSpecs {

  def main(args: Array[String]): Unit = {

    val helloWorldAPI = API[HelloWorldService]
    val unvalidatedModel = DynamicSchemaIndex.builder.addService[helloWorldAPI.Free].build().toSmithyModel
    // Workaround for https://github.com/smithy-lang/smithy/issues/2224 : round-tripping the produced model
    // via Json to force validation.
    val modelJson = ModelSerializer.builder().build().serialize(unvalidatedModel)
    val validModel = Model
      .assembler()
      .discoverModels() // loads the model found in the classpath for validation (including alloy)
      .addDocumentNode(modelJson)
      .assemble()
      .unwrap()

    // Serialising the model to smithy syntax
    val smithyMap = SmithyIdlModelSerializer
      .builder()
      .metadataFilter(_ => false)
      .build()
      .serialize(validModel)

    smithyMap.asScala.toVector.filter(_._1.toString.startsWith("examples")).foreach { case (path, content) =>
      println(s"*" * 20 + " " + path.toString())
      println(content)
    }
  }

}
