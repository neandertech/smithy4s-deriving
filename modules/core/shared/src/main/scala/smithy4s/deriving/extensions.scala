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

import smithy4s.schema.Schema
import smithy4s.schema.Field
import smithy4s.Document
import smithy.api.Default

extension (schema: Schema.type) inline def derived[T]: Schema[T] = ${ internals.derivedSchemaImpl[T] }

extension [S, A](field: Field[S, A])
  def withDefault(a: A): Field[S, A] = {
    val doc = Document.Encoder.fromSchema(field.schema).encode(a)
    field.addHints(Default(doc))
  }
