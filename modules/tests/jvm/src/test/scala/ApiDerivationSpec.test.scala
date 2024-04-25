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

package smithy4s.deriving

import munit._
import scala.annotation.experimental
import smithy4s.schema.Schema
import smithy4s.ShapeId
import smithy.api.Documentation
import smithy.api.JsonName
import smithy4s.Hints
import smithy4s.Document
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.Future
import smithy4s.deriving.EffectMirror

@experimental
class ApiDerivationSpec() extends APISuite {

  case class Bar(x: Int) derives Schema
  final case class FooError(x: Int) extends Throwable derives Schema
  final case class BarError(y: String) extends Throwable derives Schema

  case object Boom extends RuntimeException

  /** Foo */
  @errors[FooError]
  class Foo() derives API {

    @errors[BarError]
    @hints(Documentation("Bar"))
    def bar(@hints(JsonName("X")) x: Int, y: String): Future[Bar] = {
      if (x == 0) Future.failed(BarError("X was 0"))
      else if (x == -1) Future.failed(Boom)
      else Future.successful(Bar(x + y.size))
    }

    /** baz
      * @param x
      *   the x
      * @return
      *   an x
      */
    def baz(x: Int): Future[Int] = Future.successful(x)
  }

  // scalafmt: {maxColumn = 140}
  test("API derivation") {

    val expectedModel = """|$version: "2"
                           |
                           |namespace smithy4s.deriving.apiDerivationSpec
                           |
                           |/// Foo
                           |service Foo {
                           |  operations: [
                           |    Foo_bar
                           |    Foo_baz
                           |  ]
                           |}
                           |
                           |/// Bar
                           |operation Foo_bar {
                           |  input := {
                           |    @jsonName("X")
                           |    @required
                           |    x: Integer
                           |    @required
                           |    y: String
                           |  }
                           |  output: Bar
                           |  errors: [
                           |      BarError
                           |      FooError
                           |  ]
                           |}
                           |
                           |
                           |/// baz
                           |operation Foo_baz {
                           |  input := {
                           |    ///   the x
                           |    @required
                           |    x: Integer
                           |  }
                           |  output :=
                           |    ///   an x
                           |    {
                           |       @httpPayload
                           |       @required
                           |       value: Integer
                           |    }
                           |  errors: [
                           |      FooError
                           |  ]
                           |}
                           |
                           |structure Bar {
                           |  @required
                           |  x: Integer
                           |}
                           |
                           |@error("client")
                           |structure BarError {
                           |  @required
                           |  y: String
                           |}
                           |
                           |@error("client")
                           |structure FooError {
                           |  @required
                           |  x: Integer
                           |}
                           |""".stripMargin

    checkAPI[Foo](expectedModel)

    checkDynamic[Foo](new Foo()) { foo =>
      val result = Await.result(foo.baz(1), 1.second)
      assertEquals(result, 1)
    }

    checkDynamic[Foo](new Foo()) { foo =>
      val result = Await.result(foo.bar(2, "four"), 1.second)
      assertEquals(result, Bar(6))
    }

    checkDynamic[Foo](new Foo()) { foo =>
      val result1 = scala.util.Try(Await.result(foo.bar(0, "0"), 1.second))
      // the fact that we get a "caught-known-error" proves that the derived toPolyFunction/fromPolyFunction
      // implementation work as expected, and that the derived error schema is working as expected too
      assertEquals(result1, scala.util.Failure(CaughtKnownError(BarError("X was 0"))))

      // Here we're expecting the error to be unwrapped, as it was not captured in the schemas.
      val result2 = scala.util.Try(Await.result(foo.bar(-1, "0"), 1.second))
      assertEquals(result2, scala.util.Failure(Boom))
    }
  }

  case class CaughtKnownError(throwable: Throwable) extends Throwable

  // helps asserting that `toPolyFunction` and `fromPolyFunction` work as expected, and that
  // errors are captured correctly, by running data through a round-trip of error-surfacing
  // and error-absorptions, wrapping "recognised" errors in the process
  def checkDynamic[A](api: A)(using
      ev: API[A],
      effect: EffectMirror.Of[A]
  )(using SameType[effect.Effect, Future])(f: Location ?=> A => Unit)(using Location): Unit = {

    val surfaceError = new smithy4s.Transformation.SurfaceError[Future, Either] {
      def apply[E, A](fa: Future[A], projectError: Throwable => Option[E]): Either[E, A] = {
        try {
          Right(Await.result(fa, 1.second))
        } catch {
          case e: Throwable => Left(projectError(e).getOrElse(throw e))
        }
      }
    }

    val absorbError = new smithy4s.Transformation.AbsorbError[Either, Future] {
      def apply[E, A](fa: Either[E, A], injectError: E => Throwable): Future[A] = fa match {
        case Left(value)  => Future.failed(CaughtKnownError(injectError(value)))
        case Right(value) => Future.successful(value)
      }
    }

    val surfaced = smithy4s.Transformation.of(api.liftService[Future])(surfaceError)
    val absorbed = smithy4s.Transformation.of(surfaced)(absorbError)
    val roundTripped = absorbed.unliftService
    f(roundTripped)
  }

}
