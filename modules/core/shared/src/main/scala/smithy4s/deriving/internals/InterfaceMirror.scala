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

import quoted.*

import scala.annotation.implicitNotFound

// NB : this code is copied and slightly adapted from https://github.com/bishabosha/ops-mirror

open class MetaAnnotation extends scala.annotation.RefiningAnnotation
open class ErrorAnnotation[E] extends MetaAnnotation

// formatting config needs refining to work nicely with macros
// format: off
@implicitNotFound("No OpsMirror could be generated.\nDiagnose any issues by calling OpsMirror.reify[T] directly")
sealed trait InterfaceMirror:
  type Annotations <: Tuple
  type MirroredType
  type MirroredNamespace
  type MirroredLabel
  type MirroredMethods <: Tuple
  type MirroredMethodLabels <: Tuple

sealed trait MethodMirror:
  type Annotations <: Tuple
  type InputTypes <: Tuple
  type InputLabels <: Tuple
  type InputAnnotations <: Tuple
  type ErrorTypes <: Tuple
  type OutputType

sealed trait Meta

object InterfaceMirror:
  type Of[T] = InterfaceMirror { type MirroredType = T }

  transparent inline given reify[T]: Of[T] = ${ reifyImpl[T] }

  private def reifyImpl[T: Type](using Quotes): Expr[Of[T]] =
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val cls = tpe.classSymbol.get
    val decls = cls.declaredMethods.filterNot(encodesDefaultParameter)
    val labels = decls.map(m => ConstantType(StringConstant(m.name)))

    def isMeta(annot: Term): Boolean = annot.tpe <:< TypeRepr.of[MetaAnnotation]

    def encodeMeta(annot: Term): Type[?] = AnnotatedType(TypeRepr.of[Meta], annot).asType

    def extractErrorsAndMeta(annotations: List[Term]): (Type[? <: Tuple], List[Type[?]]) = {
      val annots = annotations.filter(isMeta)
      val (errorAnnots, metaAnnots) = annots.partition(annot => annot.tpe <:< TypeRepr.of[ErrorAnnotation[?]])
      val errorTpe =
        if errorAnnots.isEmpty then
          Type.of[EmptyTuple]
        else
          errorAnnots.map: annot =>
              annot.asExpr match
                case '{ $a: ErrorAnnotation[e] } => Type.of[e] match {
                  case '[type t <: Tuple; t] => Type.of[t]
                  case '[t] => Type.of[t *: EmptyTuple]
                }
            .head
      (errorTpe, metaAnnots.map(encodeMeta))
    }

    val (gErrorTypes, gmeta) = extractErrorsAndMeta(cls.annotations)

    val ops = decls
    .map(method =>
      val (errorTypes, metaAnnots) = extractErrorsAndMeta(method.annotations)
      val meta = typesToTuple(metaAnnots)
      val (inputTypes, inputLabels, inputMetas, output) =
        tpe.memberType(method) match
          case ByNameType(res) =>
            (Nil, Nil, Nil, res.asType)
          case MethodType(paramNames, paramTpes, res) =>
            val inputTypes = paramTpes.map(_.asType)
            val inputLabels = paramNames.map(l => ConstantType(StringConstant(l)).asType)
            val inputMetas = method.paramSymss.head.map(s => typesToTuple(s.annotations.filter(isMeta).map(encodeMeta)))
            val outputType = res match
              case _: MethodType => report.errorAndAbort(s"curried method ${method.name} is not supported")
              case _: PolyType => report.errorAndAbort(s"curried method ${method.name} is not supported")
              case other => other.asType
            (inputTypes, inputLabels, inputMetas, outputType)
          case _: PolyType => report.errorAndAbort(s"generic method ${method.name} is not supported")
      val inTup = typesToTuple(inputTypes)
      val inLab = typesToTuple(inputLabels)
      val inMet = typesToTuple(inputMetas)

      (meta, inTup, inLab, inMet, gErrorTypes, errorTypes, output) match
        case ('[m], '[i], '[l], '[iM], '[type ge <: Tuple; ge], '[type e <: Tuple; e],'[o]) =>
          Type.of[MethodMirror {
            type Annotations = m
            type InputTypes = i
            type InputLabels = l
            type InputAnnotations = iM
            type ErrorTypes = Tuple.Concat[ge, e]
            type OutputType = o
          }]

    )
    val clsMeta = typesToTuple(gmeta)

    val opsTup = typesToTuple(ops.toList)
    val labelsTup = typesToTuple(labels.map(_.asType))
    val name = ConstantType(StringConstant(cls.name)).asType
    val nsValue = getNamespace[T]
    val namespace = ConstantType(StringConstant(nsValue)).asType
    (clsMeta, opsTup, labelsTup, namespace, name) match
      case ('[type meta <: Tuple; meta], '[type ops <: Tuple; ops], '[type labels <: Tuple; labels], '[ns], '[label]) =>
        '{ (new InterfaceMirror {
        type Annotations = meta
        type MirroredType = T
        type MirroredNamespace = ns
        type MirroredLabel = label
        type MirroredMethods = ops
        type MirroredMethodLabels = labels
      }): InterfaceMirror.Of[T] {
        type MirroredNamespace = ns
        type MirroredLabel = label
        type MirroredMethods = ops
        type MirroredMethodLabels = labels
      }}
