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

import cats.effect.IO
import cats.effect.IOApp
import com.comcast.ip4s._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.syntax.all._

import smithy4s.http4s.SimpleRestJsonBuilder

import scala.annotation.experimental
import scala.language.implicitConversions

@experimental
object server extends IOApp.Simple {

  val routesResource =
    SimpleRestJsonBuilder
      .routes(new HelloWorldService().liftService[IO])
      .resource
      .map(_.orNotFound)

  def run: IO[scala.Unit] = {
    routesResource
      .flatMap(
        EmberServerBuilder
          .default[IO]
          .withHttpApp(_)
          .withPort(port"8080")
          .withHost(host"localhost")
          .build
      )
      .useForever
      .productR(IO.println("Goodbye"))
  }

}
