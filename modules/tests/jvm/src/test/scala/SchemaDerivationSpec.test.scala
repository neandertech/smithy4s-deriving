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
import smithy4s.schema.Schema
import smithy4s.Document.syntax.*
import smithy.api.JsonName
import smithy.api.Documentation
import smithy4s.Hints
import smithy.api.EnumValue
import smithy4s.Document
import scala.annotation.experimental

// scalafmt: { maxColumn = 140}
@experimental
class SchemaDerivationSpec() extends SchemaSuite {

  test("case class") {

    /** Foo
      * @param x
      *   some param x
      */
    case class Foo(@hints(JsonName("X")) x: Int, @hints(JsonName("Y")) y: Option[String]) derives Schema

    val expected = """|$version: "2"
                      |
                      |namespace smithy4s.deriving.schemaDerivationSpec
                      |
                      |/// Foo
                      |structure Foo {
                      |  ///   some param x
                      |  @jsonName("X")
                      |  @required
                      |  x: Integer
                      |  @jsonName("Y")
                      |  y: String
                      |}
                      |""".stripMargin

    checkSchema[Foo](expected)
    checkDynamic(Foo(1, Some("y")))
  }

  test("case class (defaults)") {

    case class Foo(x: Int = 1, y: Option[String] = None) derives Schema

    val expected = """|$version: "2"
                      |
                      |namespace smithy4s.deriving.schemaDerivationSpec
                      |
                      |structure Foo {
                      |  @required
                      |  x: Integer = 1
                      |  y: String
                      |}
                      |""".stripMargin

    checkSchema[Foo](expected)
    checkDynamic(Foo(1, Some("y")))
  }

  test("case class (recursive)") {

    case class Foo(foo: Option[Foo]) derives Schema

    val expected = """|$version: "2"
                      |
                      |namespace smithy4s.deriving.schemaDerivationSpec
                      |
                      |structure Foo {
                      |  foo: Foo
                      |}
                      |""".stripMargin

    checkSchema[Foo](expected)
    checkDynamic(Foo(Some(Foo(None))))
  }

  test("ADT") {
    @hints(Documentation("Foo"))
    enum Foo derives Schema {
      @hints.onMember(JsonName("X")) case Bar(x: Int)
      @hints.onMember(JsonName("Y")) case Baz(y: String)
    }

    val expected = """|$version: "2"
                      |
                      |namespace smithy4s.deriving.schemaDerivationSpec
                      |
                      |use smithy4s.deriving.schemaDerivationSpec.foo#Bar
                      |use smithy4s.deriving.schemaDerivationSpec.foo#Baz
                      |
                      |/// Foo
                      |union Foo {
                      |  @jsonName("X")
                      |  Bar: Bar
                      |  @jsonName("Y")
                      |  Baz: Baz
                      |}
                      |
                      |""".stripMargin

    val expectedNested = """|$version: "2"
                            |
                            |namespace smithy4s.deriving.schemaDerivationSpec.foo
                            |
                            |structure Bar {
                            |  @required
                            |  x: Integer
                            |}
                            |
                            |structure Baz {
                            |  @required
                            |  y: String
                            |}
                            |""".stripMargin

    checkSchema[Foo](expected, expectedNested)

    checkDynamic(Foo.Bar(1))
    checkDynamic(Foo.Baz("y"))
  }

  test("ADT (wrappers)") {
    @hints(Documentation("Foo"))
    enum Foo derives Schema {
      @wrapper case Bar(x: Int)
      @wrapper case Baz(y: String)
    }

    val expected = """|$version: "2"
                      |
                      |namespace smithy4s.deriving.schemaDerivationSpec
                      |
                      |/// Foo
                      |union Foo {
                      |  Bar: Integer
                      |  Baz: String
                      |}
                      |""".stripMargin

    checkSchema[Foo](expected)

    checkDynamic(Foo.Bar(1))
    checkDynamic(Foo.Baz("y"))
  }

  test("Enumeration") {
    @hints(Documentation("Foo"))
    enum Foo derives Schema {

      /** Bar x */
      @hints(smithy.api.EnumValue("BAR")) case Bar

      /** Baz y */
      @hints(smithy.api.EnumValue("BAZ")) case Baz
    }

    val expected = """|$version: "2"
                      |
                      |namespace smithy4s.deriving.schemaDerivationSpec
                      |
                      |/// Foo
                      |enum Foo {
                      |  /// Bar x
                      |  Bar = "BAR"
                      |  /// Baz y
                      |  Baz = "BAZ"
                      |}
                      |""".stripMargin

    checkSchema[Foo](expected)

    checkDynamic(Foo.Bar)
    checkDynamic(Foo.Baz)
  }

  test("Int Enumeration") {
    @hints(Documentation("Foo"))
    enum Foo derives Schema {
      @hints(smithy.api.EnumValue(5)) case Bar
      @hints(smithy.api.EnumValue(6)) case Baz
    }

    val expected = """|$version: "2"
                      |
                      |namespace smithy4s.deriving.schemaDerivationSpec
                      |
                      |/// Foo
                      |intEnum Foo {
                      |  Bar = 5
                      |  Baz = 6
                      |}
                      |""".stripMargin

    checkSchema[Foo](expected)
    checkDynamic(Foo.Bar)
    checkDynamic(Foo.Baz)
  }

  // asserts that the "dynamic" components from the schema (accessors, dispatchers, constructors, ...) are captured correctly
  def checkDynamic[A: Schema](a: A)(using Location): Unit = {
    val document = Document.encode(a)
    val result = Document.decode[A](document)
    assertEquals(result, Right(a))
  }
}
