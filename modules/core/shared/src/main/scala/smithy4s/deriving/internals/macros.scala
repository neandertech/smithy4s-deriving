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
package internals

import scala.compiletime.*
import scala.quoted.*
import scala.deriving.*

import smithy4s.Hints
import smithy4s.Hint
import smithy4s.ShapeId
import smithy4s.kinds.PolyFunction5
import smithy4s.schema.Schema
import smithy4s.schema.OperationSchema

import scala.annotation.experimental
import smithy4s.schema.EnumValue
import smithy4s.schema.EnumTag
import smithy4s.Document
import scala.util.control.NonFatal

// formatting config needs refining to work nicely with macros
// format: off

def derivedSchemaImpl[T: Type](using q: Quotes): Expr[Schema[T]] = {
  import quotes.reflect.*

  val ev: Expr[Mirror.Of[T]] = Expr.summon[Mirror.Of[T]].getOrElse {
    report.errorAndAbort(s"Could not find a suitable Mirror")
  }

  val docs = TypeRepr.of[T].classSymbol.flatMap(_.docstring).map(Docs.parse)
  val ns = Expr(getNamespace[T])
  ev match
    case '{
          $mirror: Mirror.ProductOf[T] {
            type MirroredLabel = name
            type MirroredElemTypes = elementTypes;
            type MirroredElemLabels = elementLabels
          }
        } =>

      val name = stringFromSingleton[name]
      val elemSchemas = summonSchemas[T, elementTypes]
      val labels = stringsFromTupleOfSingletons[elementLabels]
      val structHints = maybeAddDocs(hintsForType[T], docs.map(_.main))
      val fieldDocs = docs.map(_.params).getOrElse(Map.empty)
      val fieldHints = fieldHintsMap[T](fieldDocs)
      val defaults = defaultValues[T]
      val fieldsExpr = fieldsExpression(elemSchemas, labels, fieldHints, defaults)

      if (isSmithyWrapper[T]){
        val smithyWrapper = TypeRepr.of[wrapper].typeSymbol.name
        val arity = tupleSize[elementTypes]
        Type.of[elementTypes] match {
          case '[head *: EmptyTuple] =>
            val schema = elemSchemas.head.asInstanceOf[Expr[Schema[head]]]
            val hints = fieldHints(labels.head)
            '{
              $schema
                .addHints($hints)
                .addHints($structHints)
                .biject[T]((value: head) => $mirror.fromProduct(SingletonProduct(value)))((t: T) =>
                  ${ 't.asExprOf[Product] }.productElement(0).asInstanceOf[head]
                )
            }
          case _ =>
            report.errorAndAbort(s"$smithyWrapper can only be used on case classes of size 1. $name has $arity fields.")
        }
      } else {
        '{
          // TODO : find a way to compute whether it's possible to compute whether a type is recursive or not
          Schema.recursive {
            val fields = $fieldsExpr.toVector
            Schema
              .struct(fields)(seq => $mirror.fromProduct(IndexedSeqProduct(seq)))
              .addHints($structHints)
              .withId($ns, ${ Expr(name) })
          }
        }
      }

    case '{
          $mirror: Mirror.SumOf[T] {
            type MirroredLabel = name
            type MirroredElemTypes = elementTypes;
            type MirroredElemLabels = elementLabels
          }
        } =>
      val maybeEnumSchemaExpr = enumSchemaExpression[T, elementTypes]
      val shapeHints = maybeAddDocs(hintsForType[T], docs.map(_.main))
      val name = stringFromSingleton[name]
      maybeEnumSchemaExpr match {
        case Some(enumSchemaExpr) =>
          '{
            $enumSchemaExpr
              .addHints($shapeHints)
              .withId($ns, ${ Expr(name) })
          }
        case None =>
          val elemSchemas = summonSchemas[T, elementTypes]
          val labels = stringsFromTupleOfSingletons[elementLabels]
          val altsExpr = altsExpression(elemSchemas, labels)
          '{
            val alternatives = $altsExpr.toVector
            Schema
              .union[T](alternatives)((t: T) => $mirror.ordinal(t))
              .addHints($shapeHints)
              .withId($ns, ${ Expr(name) })
          }
      }
}

@experimental
def derivedAPIImpl[T: Type, F[_]: Type](
    mirror: Expr[InterfaceMirror.Of[T]]
)(using q: Quotes): Expr[API.Aux[T, [I, E, O, SI, SO] =>> F[O]]] = {
  import quotes.reflect.*

  val tpe = TypeRepr.of[T]
  val cls = tpe.classSymbol
  val serviceDocs: Option[String] = cls.flatMap(_.docstring).map(Docs.parse).map(_.main)
  val serviceHints = maybeAddDocs(hintsForType[T], serviceDocs).maybeAddPos(cls)
  val methodDocs = cls.toList.flatMap(_.declarations.flatMap {
    sym => sym.docstring.map(docs => sym.name -> Docs.parse(docs))
  }).toMap
  val methodSymbols = cls.toList.flatMap(_.declarations.map {
    sym => sym.name -> sym
  }).toMap

  mirror match {
    case '{
          $m: InterfaceMirror.Of[T] {
            type MirroredNamespace = ns
            type MirroredLabel = label
            type MirroredMethods = operations
            type MirroredMethodLabels = operationLabels
          }
        } =>
      val serviceNamespace = stringFromSingleton[ns]
      val serviceName = stringFromSingleton[label]
      val opSchemas = operationSchemasExpression[operations, operationLabels, F](serviceNamespace, serviceName, methodDocs, methodSymbols)
      '{
        new DynamicAPI[T] {
          type Effect[I, E, O, SI, SO] = F[O]
          def id = ShapeId(${Expr(serviceNamespace)}, ${ Expr(serviceName) })
          def hints = $serviceHints
          def operationSchemas: IndexedSeq[OperationSchema[?, ?, ?, ?, ?]] = $opSchemas.toIndexedSeq
          def toPolyFunction(impl: T): PolyFunction5[Operation, Effect] = new PolyFunction5[Operation, Effect] {
            val functions = ${ interfaceToFunctions[T, F]('impl) }.toIndexedSeq
            def apply[I, E, O, SI, SO](op: Operation[I, E, O, SI, SO]): Effect[I, E, O, SI, SO] = {
              functions(op.ordinal).apply(op.input.asInstanceOf[Tuple].toIArray).asInstanceOf[F[O]]
            }
          }

          def fromPolyFunction(interp: PolyFunction5[Operation, Effect]): T = {
            val function: (Int, Tuple) => F[Any] = (ordinal, input) =>
              interp(Operation(ordinal, input)).asInstanceOf[F[Any]]
            ${ interfaceFromFunction[T, F]('function) }
          }
        }
      }
  }
}

private def uncapitalise(str: String) = if (str.nonEmpty) str.head.toLower.toString + str.drop(1) else str
private def getNamespace[T: Type](using Quotes) : String = {
  import quotes.reflect.*
  val tpe = TypeRepr.of[T]
  val cls = tpe.classSymbol.getOrElse(report.errorAndAbort(s"type ${tpe.show} is not a class"))
  val result = cls.fullName.split('.').dropRight(1).map(_.filter(_.isLetterOrDigit)).map(uncapitalise).mkString(".")
  if (result.isEmpty) "default" else result
}

private def summonSchemas[T: Type, Elems: Type](using
    Quotes
): List[Expr[Schema[?]]] =
  Type.of[Elems] match
    case '[elem *: elems] =>
      deriveOrSummon[T, elem] :: summonSchemas[T, elems]
    case '[EmptyTuple] => Nil

private def deriveOrSummon[T: Type, Elem: Type](using Quotes): Expr[Schema[Elem]] =
  Type.of[Elem] match
    case '[T] => deriveRec[T, Elem]
    case _    => '{ summonInline[Schema[Elem]] }

private def deriveRec[T: Type, Elem: Type](using Quotes): Expr[Schema[Elem]] =
  Type.of[T] match
    case '[Elem] => '{ error("infinite recursive derivation") }
    case _       => derivedSchemaImpl[Elem] // recursive derivation

private def tupleSize[Ts: Type](using Quotes): Int =
  Type.of[Ts] match
    case '[EmptyTuple] => 0
    case '[t *: ts] => 1 + tupleSize[ts]

private def typesFromTuple[Ts: Type](using Quotes): List[Type[?]] =
  Type.of[Ts] match
    case '[t *: ts] => Type.of[t] :: typesFromTuple[ts]
    case '[EmptyTuple] => Nil

private def namesFromTupleOfTypes[Ts: Type](using Quotes): List[String] = {
  import quotes.reflect._
  Type.of[Ts] match
    case '[t *: ts] => TypeRepr.of[t].typeSymbol.name :: namesFromTupleOfTypes[ts]
    case '[EmptyTuple] => Nil
}

private def stringsFromTupleOfSingletons[Ts: Type](using Quotes): List[String] =
  typesFromTuple[Ts].map:
    case '[t] => stringFromSingleton[t]

private def stringFromSingleton[T: Type](using Quotes): String =
  import quotes.reflect.*
  TypeRepr.of[T] match
    case ConstantType(StringConstant(label)) => label
    case _ => report.errorAndAbort(s"expected a constant string, got ${TypeRepr.of[T]}")

private def typesToTuple(list: List[Type[?]])(using Quotes): Type[?] = list match
  case '[t] :: ts => typesToTuple(ts) match
    case '[type ts <: Tuple; ts] => Type.of[t *: ts]
  case _ => Type.of[EmptyTuple]

private def extractAnnotationsFromTuple[Annotations: Type](using Quotes): List[List[Expr[Any]]] =
  typesFromTuple[Annotations].map:
    case '[m] => extractAnnotationFromType[m]

private def extractAnnotationFromType[Annotations: Type](using Quotes): List[Expr[Any]] = {
  import quotes.reflect.*
  typesFromTuple[Annotations].map:
    case '[m] => TypeRepr.of[m] match
      case AnnotatedType(_, annot) =>
        annot.asExpr
      case tpe =>
        report.errorAndAbort(s"got the annotations element ${tpe.show}")
}

private def fieldsExpression[T: Type](
    schemaInstances: List[Expr[Schema[?]]],
    labels: List[String],
    hintsMap: Map[String, Expr[Hints]],
    defaultValues: Map[String, Expr[Any]]
)(using Quotes): Expr[Seq[smithy4s.schema.Field[T, ?]]] =
  Expr.ofSeq(schemaInstances.zipWithIndex.zip(labels).map { case (('{ $elem: Schema[t] }, index), label) =>
    val indexExpr = Expr(index)
    val labelExpr = Expr(label)
    val getter: Expr[T => t] = '{ (t: T) =>
      ${ 't.asExprOf[Product] }
        .productElement($indexExpr)
        .asInstanceOf[t]
    }
    Type.of[t] match {
      case '[Option[tt]] => '{
        $elem
          .field($labelExpr, $getter)
          .addHints(${ hintsMap(label) }): smithy4s.schema.Field[T, ?]
      }
      case _ =>
        val base = '{
              $elem
                .required($labelExpr, $getter)
                .addHints(${ hintsMap(label) }): smithy4s.schema.Field[T, t]
            }
        defaultValues.get(label) match {
          case Some(default) if default.isExprOf[t] =>
          '{$base.withDefault(${default.asExprOf[t]})}
          case _ => base
        }
    }
  })

private def altsExpression[T: Type](
    schemaInstances: List[Expr[Schema[?]]],
    labels: List[String]
)(using Quotes): Expr[Seq[smithy4s.schema.Alt[T, ?]]] =
  Expr.ofSeq(schemaInstances.zipWithIndex.zip(labels).map { case (('{ $elem: Schema[tt] }, index), label) =>
    val labelExpr = Expr(label)
    val inject: Expr[tt => T] = '{ (t: tt) => t.asInstanceOf[T] }
    val project: Expr[PartialFunction[T, tt]] = '{
      { case (t: tt) => t }
    }
    val alt = '{
      $elem.oneOf[T]($labelExpr, $inject)($project): smithy4s.schema.Alt[T, ?]
    }
    alt
  })

private def operationHints(annotations: List[Expr[Any]])(using Quotes): Expr[Hints] =
  annotations
    .collectFirst { case '{ $smithyAnnotation: HintsProvider } =>
      '{ $smithyAnnotation.hints }
    }
    .getOrElse('{ Hints.empty })

private def paramHintsMap(annotations: List[List[Expr[Any]]], labels: List[String], paramDocs: Map[String, String])(using
    Quotes
): Map[String, Expr[Hints]] = {
  annotations
    .zip(labels)
    .map { case (paramAnnotations, paramName) =>
      val hintsExpr = paramAnnotations
        .collectFirst { case '{ $smithyAnnotation: HintsProvider } => '{ $smithyAnnotation.hints } }
        .getOrElse('{ Hints.empty })
      paramName -> maybeAddDocs(hintsExpr, paramDocs.get(paramName), member = true)
    }
    .toMap
}

private def checkAllAreThrowable[T <: Tuple : Type](using Quotes) : Unit = {
  import quotes.reflect._
  Type.of[T] match {
    case '[type head <: Throwable; head *: tail] => checkAllAreThrowable[tail]
    case '[type head ; head *: tail] =>
      val name = TypeRepr.of[head].typeSymbol.name
      report.errorAndAbort(s"Error $name does not extend Throwable")
    case '[EmptyTuple] => ()
  }
}

private def errorUnionRepr[U : Type](using quotes: Quotes) : quotes.reflect.TypeRepr = {
  import quotes.reflect._
  Type.of[U] match {
    case '[type head <: Throwable; head *: tail] => OrType(TypeRepr.of[head], errorUnionRepr[tail])
    case '[type head ; head *: tail] =>
      val name = TypeRepr.of[head].typeSymbol.name
      report.errorAndAbort(s"Error $name does not extend Throwable")
    case '[EmptyTuple] => TypeRepr.of[Nothing]
  }
}

private def maybeAddDocs(expr: Expr[Hints], docs: Option[String], member: Boolean = false)(using Quotes): Expr[Hints] = {
  docs match {
    case Some(doc) if member => '{ $expr.addMemberHints(smithy.api.Documentation(${Expr(doc)}))}
    case Some(doc) => '{ $expr.addTargetHints(smithy.api.Documentation(${Expr(doc)}))}
    case None => expr
  }
}

extension(expr: Expr[Hints]){
  private[internals] def maybeAddPos(using Quotes)(symbol: Option[quotes.reflect.Symbol]) : Expr[Hints] = {
    symbol.flatMap(_.pos) match {
      case None => expr
      case Some(pos) =>
        val sourceLoc = '{
          SourcePosition(
            path = ${Expr(pos.sourceFile.path)},
            start = ${Expr(pos.start)},
            startLine = ${Expr(pos.startLine)},
            startColumn = ${Expr(pos.startColumn)},
            end = ${Expr(pos.end)},
            endLine = ${Expr(pos.endLine)},
            endColumn = ${Expr(pos.endColumn)}
          ) : Hints.Binding
        }
        '{$expr.add($sourceLoc)}
    }
  }
}

private def fieldHintsMap[T: Type](docs: Map[String, String])(using
    Quotes
): Map[String, Expr[Hints]] = {
  import quotes.reflect.*
  def maybeSmithyAnnotation(term: Term): List[Expr[HintsProvider]] = {
    val smithyTpe = TypeRepr.of[HintsProvider]
    if (term.tpe <:< smithyTpe) List {
      term.asExprOf[HintsProvider]
    }
    else Nil
  }

  TypeRepr
    .of[T]
    .typeSymbol
    .primaryConstructor
    .paramSymss
    .flatten
    .map { sym =>
      val hintsExprs = sym.annotations.flatMap(maybeSmithyAnnotation)
      val hintsExpr = Expr.ofSeq(hintsExprs)
      sym.name -> maybeAddDocs('{ $hintsExpr.map(_.hints).fold(Hints.empty)(_ ++ _) }, docs.get(sym.name), member = true)
    }
    .toMap
}

private def operationSchemasExpression[Ts: Type, OpLabels: Type, F[_]: Type](using Quotes)(
    serviceNamespace: String,
    serviceName: String,
    methodDocs: Map[String, Docs],
    methodSymbols: Map[String, quotes.reflect.Symbol]
): Expr[List[OperationSchema[?, ?, ?, ?, ?]]] = {
  val expressionList = typesFromTuple[Ts]
    .zip(stringsFromTupleOfSingletons[OpLabels])
    .map { case ('[op], opName) =>
      Type.of[op] match {
        case '[type errorTypes <: Tuple; MethodMirror {
              type InputLabels = inputLabels
              type InputTypes = inputTypes
              type ErrorTypes = errorTypes
              type OutputType = F[outputType]
              type Annotations = annotations
              type InputAnnotations = inputAnnotations
            }] =>
          val paramSchemas = summonSchemas[inputTypes, inputTypes]
          val labels = stringsFromTupleOfSingletons[inputLabels]
          val opAnnotations = extractAnnotationFromType[annotations]
          val opDocs = methodDocs.get(opName).map(_.main)
          val outputDocs = Expr(methodDocs.get(opName).flatMap(_.output))
          val methodSymbol = methodSymbols.get(opName)
          val opHints = maybeAddDocs(operationHints(opAnnotations), opDocs).maybeAddPos(methodSymbol)
          val paramDocs = methodDocs.get(opName).map(_.params).getOrElse(Map.empty)
          val paramAnnotations = extractAnnotationsFromTuple[inputAnnotations]
          val fieldHints = paramHintsMap(paramAnnotations, labels, paramDocs)
          val defaults = Map.empty[String, Expr[Any]]
          val fieldsExpr = fieldsExpression[inputTypes](paramSchemas, labels, fieldHints, defaults)
          val errorSchemas = summonSchemas[errorTypes, errorTypes]

          import quotes.reflect._
          val nestedNs = Expr(serviceNamespace + "." + uncapitalise(serviceName))
          val opInputNameExpr = Expr(opName + "Input")
          val opOutputNameExpr = Expr(opName + "Output")
          val inputSchema = '{
            val fields = $fieldsExpr.toVector
            Schema
              .struct(fields)(seq => Tuple.fromArray(seq.toArray).asInstanceOf[inputTypes])
              .withId($nestedNs, $opInputNameExpr)
              .addHints(ShapeId("smithy.api", "input") -> Document.obj())
          }
          val outputSchema = '{ summonInline[Schema[outputType]].compile(wrapOutputSchema(ShapeId($nestedNs, $opOutputNameExpr), $outputDocs))}
          val opSchemaWithoutError = '{
            Schema
              .operation(ShapeId($nestedNs, ${ Expr(opName) }))
              .withInput($inputSchema)
              .withOutput($outputSchema)
              .withHints($opHints)
            }

          errorUnionRepr[errorTypes].asType match {
            case '[Nothing] => opSchemaWithoutError
            case '[union] =>
              val typeNames = namesFromTupleOfTypes[errorTypes]
              val errorAltsExpr = altsExpression[union](errorSchemas, typeNames)
              '{
                val errorUnion = Schema.union[union](${errorAltsExpr}.toVector.map(addErrorHint(_))).reflective
                val errorSchema = errorUnion.error(_.asInstanceOf[Throwable]) { case e : union => e}
                $opSchemaWithoutError.withError(errorSchema)
              }
          }
      }
    }
  Expr.ofList(expressionList)
}

private def hintsForType[T: Type](using Quotes): Expr[Hints] = {
  import quotes.reflect.*
  val sym = TypeRepr.of[T].typeSymbol
  hintsFor(sym)
}

private def hintsFor(using quotes:Quotes)(sym: quotes.reflect.Symbol) : Expr[Hints] = {
  import quotes.reflect._
  def maybeSmithyAnnotation(term: Term): List[Expr[HintsProvider]] = {
    val smithyTpe = TypeRepr.of[HintsProvider]
    if (term.tpe <:< smithyTpe) List {
      term.asExprOf[HintsProvider]
    }
    else Nil
  }
  val hintsExprs = sym.annotations.flatMap(maybeSmithyAnnotation)
  val hintsExpr = Expr.ofSeq(hintsExprs)
  '{ ($hintsExpr.map(_.hints).fold(Hints.empty)(_ ++ _)) }
}

private def isSmithyWrapper[T: Type](using quotes: Quotes) : Boolean = {
  import quotes.reflect._
  val sym = TypeRepr.of[T].typeSymbol
  val wrapperType = TypeRepr.of[wrapper]
  sym.annotations.exists { annotation =>
    annotation.tpe =:= wrapperType
  }
}

@experimental
private def interfaceToFunctions[T: Type, F[_]: Type](
    algExpr: Expr[T]
)(using Quotes): Expr[Seq[IArray[Any] => F[Any]]] = {
  import quotes.reflect._
  val algTpe = TypeRepr.of[T]
  Expr.ofSeq {
    algTpe.typeSymbol.declaredMethods.filterNot(encodesDefaultParameter).map { meth =>
      val selectMethod = algExpr.asTerm.select(meth)

      meth.paramSymss.match {
        case Nil :: Nil =>
          // special-case: nullary method (one, zero-parameter list)
          '{ Function.const(${ selectMethod.appliedToNone.asExprOf[F[Any]] }) }

        case _ =>
          val types = meth.paramSymss.map(_.map(_.info.asType))

          '{ (input: IArray[Any]) =>
            ${
              selectMethod
                .appliedToArgss {
                  types.map { tpeList =>
                    // We assume a single list of parameters
                    tpeList.zipWithIndex.map { (tpe, idx1) =>
                      tpe match {
                        case '[t] =>
                          '{ input(${ Expr(idx1) }).asInstanceOf[t] }.asTerm
                      }
                    }
                  }.toList
                }
                .asExprOf[F[Any]]
            }
          }
      }
    }
  }
}

@experimental
private def interfaceFromFunction[T: Type, F[_]: Type](fExpr: Expr[(Int, Tuple) => F[Any]])(using Quotes): Expr[T] = {
  import quotes.reflect._
  val algType = TypeRepr.of[T]
  val algIsTrait = algType.classSymbol.exists(_.flags.is(Flags.Trait))

  val parents = if (algIsTrait) List(TypeTree.of[Object], TypeTree.of[T]) else List(TypeTree.of[T])
  val meths = algType.typeSymbol.declaredMethods.filterNot(encodesDefaultParameter)

  def decls(cls: Symbol): List[Symbol] = meths.map { method =>
    val methodType = algType.memberType(method)

    Symbol.newMethod(
      cls,
      method.name,
      methodType,
      flags = Flags.Override,
      privateWithin = method.privateWithin.fold(Symbol.noSymbol)(_.typeSymbol)
    )
  }

  val cls = Symbol.newClass(Symbol.spliceOwner, "proxy", parents.map(_.tpe), decls, selfType = None)

  val body: List[DefDef] = cls.declaredMethods.filterNot(encodesDefaultParameter).zipWithIndex.map { case (sym, index) =>
    def impl(argss: List[List[Tree]]) = {
      algType.memberType(sym) match {
        case MethodType(_, _, res) =>
          res.asType match {
            case '[out] =>
              argss match {
                case Nil :: Nil => '{ ${ fExpr }.apply(${ Expr(index) }, EmptyTuple).asInstanceOf[out] }
                case _ =>
                  val args = Expr.ofTupleFromSeq(argss.head.map(_.asExprOf[Any]))
                  '{ ${ fExpr }.apply(${ Expr(index) }, $args).asInstanceOf[out] }
              }
          }
      }
    }.asTerm

    DefDef(sym, argss => Some(impl(argss)))
  }

  val clsDef = ClassDef(cls, parents, body)
  val newCls = Typed(Apply(Select(New(TypeIdent(cls)), cls.primaryConstructor), Nil), TypeTree.of[T])
  Block(List(clsDef), newCls).asExprOf[T]
}

private def enumSchemaExpression[Enum: Type, Members: Type](using Quotes) : Option[Expr[Schema[Enum]]] = {
  val all = typesFromTuple[Members].zipWithIndex.map(enumValueExpression[Enum](_, _))
  if (all.nonEmpty && all.forall(_.isDefined)) Some {
    val enumValuesExpr = Expr.ofList(all.flatten)
    '{
      val (areInts, enumValues) = $enumValuesExpr.unzip
      val enumMap = enumValues.map(ev => ev.value -> ev).toMap
      val enumTag = if (areInts.reduce(_ && _)) EnumTag.ClosedIntEnum else EnumTag.ClosedStringEnum
      Schema.enumeration(enumMap, enumTag, enumValues)
    }
  }
  else None
}

private def enumValueExpression[Enum: Type](memberType: Type[?], index: Int)(using Quotes) = {
  import quotes.reflect._
  memberType match {
    case '[type mt <: Enum; mt] =>
      val ev: Expr[Mirror.Of[mt]] = Expr.summon[Mirror.Of[mt]].getOrElse {
        report.errorAndAbort(s"Could not find a suitable Mirror for ${TypeRepr.of[mt].show}")
      }
      val docs = TypeRepr.of[mt].termSymbol.docstring.map(Docs.parse)

      ev match {
        case '{$mirror: Mirror.Singleton {type MirroredLabel = label}} => Some{
          val hintsExpr = maybeAddDocs(hintsFor(TypeRepr.of[mt].termSymbol), docs.map(_.main))
          val labelExpr = Expr(stringFromSingleton[label])
          val indexExpr = Expr(index)
          '{
            val enumValueHints = $hintsExpr
            val (isInt, stringValue, intValue) = enumValueHints.get(smithy.api.EnumValue).map(_.value) match {
              case Some(Document.DNumber(value)) => (true, $labelExpr, value.toInt)
              case Some(Document.DString(value)) => (false, value, $indexExpr)
              case _ =>                             (false, $labelExpr, $indexExpr)
            }
            val instance = $mirror.asInstanceOf[Enum]
            (isInt, EnumValue(stringValue = stringValue, intValue = intValue, value = instance, name = $labelExpr, enumValueHints))
          }
        }
        case _ => None
      }
  }
}

// logic borrowed from DuckTape
private def defaultValues[T : Type](using Quotes): Map[String, Expr[Any]] = {
  import quotes.reflect.*

  val tpe = TypeRepr.of[T].widen
  val sym = tpe.typeSymbol
  val fieldNamesWithDefaults = sym.caseFields.filter(_.flags.is(Flags.HasDefault)).map(_.name)

  val companionBody = sym.companionClass.tree.asInstanceOf[ClassDef].body
  try {
    // This throws if the case class is defined in a method
    val companion = Ref(sym.companionModule)
    val defaultValues = companionBody.collect {
      case defaultMethod @ DefDef(name, _, _, _) if name.startsWith("$lessinit$greater$default") =>
        companion.select(defaultMethod.symbol).appliedToTypes(tpe.typeArgs).asExpr
    }

    fieldNamesWithDefaults.zip(defaultValues).toMap
  } catch {
    case NonFatal(_) => Map.empty
  }
}

// default parameters of methods encoded as methods on the class, following the
// `methodName$default$parameterIndex` pattern. We avoid to capture them.
private[deriving] def encodesDefaultParameter(using quotes: Quotes)(sym: quotes.reflect.Symbol): Boolean = {
  sym.name.contains("$default$")
}


/// MISC

def treeExpr[T](expr: Expr[T])(using Quotes): Expr[String] = {
  import quotes.reflect._
  Expr(expr.asTerm.show(using Printer.TreeStructure))
}

def docs[T: Type](using Quotes): Expr[Option[String]] = {
  import quotes.reflect.*
  Expr(TypeRepr.of[T].classSymbol.flatMap(_.docstring))
}

private def defaultMethodParams[T : Type](using Quotes): Expr[Map[(String, Int), String]] = {
  import quotes.reflect.*

  val tpe = TypeRepr.of[T].widen
  // tpe.classSymbol.get.docstring.foreach(str => report.error(str))
  val decl = tpe.typeSymbol.declaredMethods.map(_.tree)
  // report.error(tpe.classSymbol.get.companionClass.tree.show(using Printer.TreeStructure))
  val res = decl.collect {
    case DefDef(s"$methodName$$default$$$nb", _, _, expr) =>
      (methodName, nb.toInt) -> expr.map(t => t.show(using Printer.TreeCode)).getOrElse("NONE")
  }.toMap
  Expr(res)
}
