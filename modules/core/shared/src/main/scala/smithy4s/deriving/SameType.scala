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

import scala.annotation.implicitNotFound
import smithy4s.kinds.Kind1

@implicitNotFound("Could not prove that types ${A} and ${B} are the same type")
sealed trait SameType[A <: AnyKind, B <: AnyKind]
object SameType {
  private val singleton = new SameType[Any, Any] {}
  given refl[A <: AnyKind]: SameType[A, A] = singleton.asInstanceOf[SameType[A, A]]

  given lifted[F[_], G[_]](using SameType[F, G]): SameType[Kind1[F]#toKind5, Kind1[G]#toKind5] =
    singleton.asInstanceOf[SameType[Kind1[F]#toKind5, Kind1[G]#toKind5]]
}
