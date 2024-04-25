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

package smithy4s.deriving.internals

import smithy4s.schema.Schema
import smithy4s.kinds.PolyFunction
import smithy4s.ShapeId
import smithy4s.Document
import smithy4s.Hints

// Wrap non-structure schemas
private[deriving] case class wrapOutputSchema(id: ShapeId, maybeDoc: Option[String])
    extends PolyFunction[Schema, Schema] {
  def apply[A](schema: Schema[A]): Schema[A] = schema match {
    case Schema.LazySchema(suspend) => Schema.LazySchema(suspend.map(this(_)))
    case _: Schema.StructSchema[A]  => schema
    case _ =>
      val field = schema
        .field[A]("value", identity[A])
        .addHints(smithy.api.HttpPayload())
      val maybeRequired = if (schema.isOption) field else field.addHints(smithy.api.Required())
      val docHints = maybeDoc.map(smithy.api.Documentation(_)).map(Hints(_)).getOrElse(Hints.empty)
      Schema
        .struct[A](maybeRequired)(identity)
        .withId(id)
        .addHints(ShapeId("smithy.api", "output") -> Document.obj())
        .addHints(docHints)
  }
}
