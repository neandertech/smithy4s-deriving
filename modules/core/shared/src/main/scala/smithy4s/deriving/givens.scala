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

import smithy4s._

import java.util.UUID

given Schema[String] = Schema.string
given Schema[Byte] = Schema.byte
given Schema[Boolean] = Schema.boolean
given Schema[Int] = Schema.int
given Schema[Short] = Schema.short
given Schema[Long] = Schema.long
given Schema[Float] = Schema.float
given Schema[Double] = Schema.double
given Schema[BigDecimal] = Schema.bigdecimal
given Schema[BigInt] = Schema.bigint
given Schema[Timestamp] = Schema.timestamp
given Schema[Blob] = Schema.blob
given Schema[Document] = Schema.document
given Schema[UUID] = Schema.uuid
given Schema[Unit] = Schema.unit

given [A: Schema]: Schema[Option[A]] = Schema.option(summon[Schema[A]])
given [A: Schema]: Schema[Nullable[A]] = Nullable.schema(summon[Schema[A]])

given [A: Schema]: Schema[List[A]] = {
  val schemaA = summon[Schema[A]]
  val idA = schemaA.shapeId
  val id = idA.copy(name = idA.name + "List")
  Schema.list(schemaA).withId(id)
}

given [A: Schema]: Schema[Vector[A]] = {
  val schemaA = summon[Schema[A]]
  val idA = schemaA.shapeId
  val id = idA.copy(name = idA.name + "Vector")
  Schema.vector(schemaA).withId(id)
}

given [A: Schema]: Schema[Set[A]] = {
  val schemaA = summon[Schema[A]]
  val idA = schemaA.shapeId
  val id = idA.copy(name = idA.name + "Set")
  Schema.set(schemaA).withId(id)
}

given [K: Schema, V: Schema]: Schema[Map[K, V]] = {
  val schemaK = summon[Schema[K]]
  val schemaV = summon[Schema[V]]
  val idV = schemaV.shapeId
  val id = idV.copy(name = idV.name + "Map")
  Schema.map(schemaK, schemaV).withId(id)
}
