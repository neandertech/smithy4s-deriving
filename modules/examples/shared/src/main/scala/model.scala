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

import smithy4s.*
import smithy4s.deriving.{given, *}
import smithy.api.*
import alloy.*
import cats.effect.IO
import scala.annotation.experimental

// Just making it simpler to construct hints
given [A, B](using Bijection[A, B]): Conversion[A, B] with {
  def apply(a: A): B = summon[Bijection[A, B]](a)
}

@hints(HttpError(503))
case class LocationNotRecognised(errorMessage: String) extends Throwable derives Schema {
  override def getMessage(): String = errorMessage
}

@experimental
@hints(SimpleRestJson())
class HelloWorldService() derives API {

  @errors[LocationNotRecognised]
  @hints(Http(method = "GET", uri = "/hello/{namea}"))
  def hello(
      @hints(HttpLabel()) name: String,
      @hints(HttpQuery("from")) from: Option[String]
  ): IO[String] = from match {
    case None                       => IO(s"Hello $name!")
    case Some(loc) if loc.isEmpty() => IO.raiseError(LocationNotRecognised("Empty location"))
    case Some(loc)                  => IO(s"Hello $name from $loc")
  }

}
