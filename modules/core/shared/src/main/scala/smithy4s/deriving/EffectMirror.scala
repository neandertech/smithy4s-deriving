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

import quoted.*

import scala.annotation.implicitNotFound

@implicitNotFound(
  "No Effect mirror could be generated. It may indicate that the target type is not a class/trait, or that the methods it contains return heterogenous effects.\n\nDiagnose any issues by calling EffectMirror.reify[T] directly"
)
sealed trait EffectMirror:
  type Effect[_]
  type MirroredType

// formatting config needs refining to work nicely with macros
// format: off

object EffectMirror:
  type Of[T] = EffectMirror { type MirroredType = T }

  transparent inline given reify[T]: Of[T] = ${ reifyImpl[T] }

  private def reifyImpl[T: Type](using Quotes): Expr[Of[T]] =
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val cls = tpe.classSymbol.get
    val decls = cls.declaredMethods.filterNot(internals.encodesDefaultParameter)

    // Split a type between an effect and a container
    def splitType(tpe: TypeRepr): (TypeRepr, Type[?]) = {
      val Id = TypeRepr.of[[A] =>> A]
      tpe match {
        case AppliedType(outer, inner) =>
          // we assume that iterables are part of the metamodel
          if (outer <:< TypeRepr.of[IterableOnce]) (Id, tpe.asType)
          else
            outer.asType match {
              case '[type f[_]; f] =>
                (outer, inner(0).asType)
              case _ => report.errorAndAbort(s"Types with complex kinds are not allowed : ${outer.show}")
            }
        case _ => (Id, tpe.asType)
      }
    }

    val effectsAndOps = decls.map(method =>
      tpe.memberType(method) match
        case ByNameType(res) => splitType(res)
        case MethodType(paramNames, paramTpes, res) =>
          res match
            case _: MethodType => report.errorAndAbort(s"curried method ${method.name} is not supported")
            case _: PolyType   => report.errorAndAbort(s"curried method ${method.name} is not supported")
            case _             => splitType(res)
        case _: PolyType => report.errorAndAbort(s"generic method ${method.name} is not supported")
    )
    val (effects, ops) = effectsAndOps.unzip
    val distinctEffects = effects.distinct
    val effect =
      if (distinctEffects.size == 0) Type.of[[A] =>> A]
      else if (distinctEffects.size > 1) {
        report.errorAndAbort(s"Heterogenous effects are unsupported: ${distinctEffects.map(_.show).mkString("; ")}")
      } else {
        distinctEffects.head.asType
      }

    effect match
      case '[type f[_]; f] =>
        '{
          (new EffectMirror { type Effect[A] = f[A]; type MirroredType = T }): EffectMirror.Of[T] {
            type Effect[A] = f[A]
          }
        }
