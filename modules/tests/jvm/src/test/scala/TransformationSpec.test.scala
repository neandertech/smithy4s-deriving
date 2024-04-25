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
import scala.util._
import smithy4s.kinds.PolyFunction
import scala.annotation.experimental

@experimental
class TransformationSpec() extends FunSuite {

  trait Foo() derives API {
    def foo(x: Int): Try[Int]

    def bar(x: Int): Try[Int]
  }
  case object Boom extends Throwable

  test("Instances of classes deriving API can be transformed") {
    val instance = new Foo {
      def foo(x: Int): Try[Int] = Success(x)
      def bar(x: Int): Try[Int] = Success(x + 1)
    }

    val transformed = instance.transform(new PolyFunction[Try, Try] {
      def apply[A](fa: Try[A]): Try[A] = Failure(Boom)
    })

    assertEquals(transformed.foo(1), Failure(Boom))
  }

  test("Stubs can be produced from derived API instances") {
    val stub = API[Foo].default[Try](Failure(Boom))
    val instance = new Foo {
      export stub.{foo => _, *}
      override def foo(x: Int): Try[Int] = Success(x + 2)
    }

    assertEquals(instance.foo(1), Success(3))
    assertEquals(instance.bar(1), Failure(Boom))
  }

}
