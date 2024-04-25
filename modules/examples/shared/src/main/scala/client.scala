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
import org.http4s.syntax.all._

import smithy4s.http4s.SimpleRestJsonBuilder

import scala.annotation.experimental
import org.http4s.ember.client.EmberClientBuilder
import scala.language.implicitConversions
import smithy4s.deriving.API

@experimental
object client extends IOApp.Simple {

  val clientResource =
    EmberClientBuilder.default[IO].build.flatMap { httpClient =>
      SimpleRestJsonBuilder(API.service[HelloWorldService])
        .client[IO](httpClient)
        .uri(uri"http://localhost:8080/")
        .resource
        .map(_.unliftService)
    }

  def run: IO[scala.Unit] = {
    clientResource
      .use { helloWorldService =>
        helloWorldService.hello("John Doe", Some("Nowhere")).flatMap(IO.println).handleErrorWith(IO.println)
      }
  }

}
