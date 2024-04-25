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
import smithy4s.kinds._
import smithy4s.schema._
import smithy4s.deriving.internals.*
import scala.annotation.experimental
import smithy4s.Transformation

/** Conceptually similar to `smithy4s.Service`, but the effect type is fixed.
  */
trait API[Alg] { api =>
  type Operation[_, _, _, _, _]
  def endpoints: IndexedSeq[Endpoint[Operation, ?, ?, ?, ?, ?]]

  def id: ShapeId
  def hints: Hints
  def input[I, E, O, SI, SO](op: Operation[I, E, O, SI, SO]): I
  def ordinal[I, E, O, SI, SO](op: Operation[I, E, O, SI, SO]): Int

  type Effect[_, _, _, _, _]
  def toPolyFunction(alg: Alg): PolyFunction5[Operation, Effect]
  def fromPolyFunction(polyFunction: PolyFunction5[Operation, Effect]): Alg

  def default[F[+_]](stub: => F[Nothing])(using mirror: EffectMirror.Of[Alg])(using SameType[mirror.Effect, F]): Alg =
    fromPolyFunction(new PolyFunction5[Operation, Effect] {
      def apply[I, E, O, SI, SO](fa: Operation[I, E, O, SI, SO]): Effect[I, E, O, SI, SO] =
        stub.asInstanceOf[Effect[I, E, O, SI, SO]]
    })

  extension (alg: Alg) {

    /**
      * Allows to apply various transformations to the algebra. This is useful, but not limited, to
      * smithy4s.kinds.PolyFunction
      */
    def transform[F[_], T](function: T)(using
        mirror: EffectMirror.Of[Alg],
        transformation: Transformation[T, Free[Kind1[F]#toKind5], Free[Kind1[F]#toKind5]]
    )(using SameType[mirror.Effect, F]): Alg =
      transformation.apply(function, liftService).unliftService

    def liftService[F[_]](using mirror: EffectMirror.Of[Alg])(using
        SameType[mirror.Effect, F]
    ): Free[Kind1[F]#toKind5] =
      Free.LiftedAlgebra(alg, PolyFunction5.identity.asInstanceOf[PolyFunction5[Effect, Kind1[F]#toKind5]])

    def liftServiceWith[F[_], G[_]](f: [A] => F[A] => G[A])(using
        mirror: EffectMirror.Of[Alg]
    )(using SameType[mirror.Effect, F]): Free[Kind1[G]#toKind5] =
      Free.LiftedAlgebra(
        alg,
        new PolyFunction5[Effect, Kind1[G]#toKind5] {
          def apply[I, E, O, SI, SO](effect: Effect[I, E, O, SI, SO]): G[O] = f(effect.asInstanceOf[F[O]])
        }
      )

  }

  enum Free[TargetEffect[_, _, _, _, _]] {
    case LiftedAlgebra(alg: Alg, fk: PolyFunction5[Effect, TargetEffect])
    case LiftedPolyFunction(fk: PolyFunction5[Operation, TargetEffect])

    def unliftService(using
        mirror: EffectMirror.Of[Alg]
    )(using SameType[Kind1[mirror.Effect]#toKind5, TargetEffect]): Alg =
      this match {
        case LiftedAlgebra(alg, fk) =>
          fromPolyFunction(toPolyFunction(alg).andThen(fk.asInstanceOf[PolyFunction5[Effect, Effect]]))
        case LiftedPolyFunction(fk) =>
          fromPolyFunction(fk.asInstanceOf[PolyFunction5[Operation, Effect]])
      }

  }

  object Free {
    given Service[Free] = new Service[Free] {
      type Operation[I, E, O, SI, SO] = api.Operation[I, E, O, SI, SO]
      def endpoints: IndexedSeq[Endpoint[?, ?, ?, ?, ?]] = api.endpoints
      def hints: Hints = api.hints
      def id: ShapeId = api.id
      def input[I, E, O, SI, SO](op: Operation[I, E, O, SI, SO]): I = api.input(op)
      def ordinal[I, E, O, SI, SO](op: Operation[I, E, O, SI, SO]): Int = api.ordinal(op)
      def version: String = "1"
      def mapK5[F[_, _, _, _, _], G[_, _, _, _, _]](
          lifted: api.Free[F],
          function: PolyFunction5[F, G]
      ): api.Free[G] = lifted match
        case api.Free.LiftedAlgebra(alg, fk) => api.Free.LiftedAlgebra(alg, fk.andThen(function))
        case api.Free.LiftedPolyFunction(fk) => api.Free.LiftedPolyFunction(fk.andThen(function))

      def reified: api.Free[Operation] = api.Free.LiftedPolyFunction(PolyFunction5.identity)
      def toPolyFunction[P[_, _, _, _, _]](lifted: api.Free[P]): PolyFunction5[Operation, P] =
        lifted match
          case api.Free.LiftedAlgebra(alg, fk) => api.toPolyFunction(alg).andThen(fk)
          case api.Free.LiftedPolyFunction(fk) => fk

      def fromPolyFunction[P[_, _, _, _, _]](function: PolyFunction5[Operation, P]): api.Free[P] =
        api.Free.LiftedPolyFunction(function)
    }
  }
}

@experimental
object API {

  def apply[Alg](implicit ev: API[Alg]): ev.type = ev
  def service[Alg](using ev: API[Alg]): Service[ev.Free] = summon[Service[ev.Free]]

  type Aux[Alg, F[_, _, _, _, _]] = API[Alg] { type Effect[I, E, O, SI, SO] = F[I, E, O, SI, SO] }

  transparent inline def derived[T](using m: InterfaceMirror.Of[T], em: EffectMirror.Of[T]) = ${
    derivedAPIImpl[T, em.Effect]('m)
  }

}

private[deriving] trait DynamicAPI[Alg] extends API[Alg] {
  final case class Endpoint[I, E, O, SI, SO](ordinal: Int, schema: OperationSchema[I, E, O, SI, SO])
      extends smithy4s.Endpoint[Operation, I, E, O, SI, SO] {
    def wrap(input: I): Operation[I, E, O, SI, SO] = Operation(ordinal, input)
  }
  def operationSchemas: IndexedSeq[OperationSchema[?, ?, ?, ?, ?]]
  final def endpoints: IndexedSeq[Endpoint[?, ?, ?, ?, ?]] = operationSchemas.zipWithIndex.map { case (opSchema, i) =>
    Endpoint(i, opSchema)
  }
  final case class Operation[I, E, O, SI, SO](ordinal: Int, input: I)
  final def input[I, E, O, SI, SO](op: Operation[I, E, O, SI, SO]): I = op.input
  final def ordinal[I, E, O, SI, SO](op: Operation[I, E, O, SI, SO]): Int = op.ordinal
}
