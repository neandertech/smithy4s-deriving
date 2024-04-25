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

import scala.jdk.CollectionConverters._

private[deriving] case class Docs(main: String, params: Map[String, String], output: Option[String])

private[deriving] object Docs {

  def parse(str: String): Docs = {
    str
      .linesIterator
      .map(_.trim)
      .foldLeft(State.start) {
        case (state, s"/**$line*/") => state.addLine(line.trim).conclude
        case (state, s"/**$line")   => state.addLine(line.trim)
        case (state, s"* @param ${param(p)} $line") =>
          state.startSection(Section.Param(p)).addLine(line)
        case (state, s"* @param ${param(p)}") =>
          state.startSection(Section.Param(p))
        case (state, s"* @return $line") =>
          state.startSection(Section.Return).addLine(line)
        case (state, s"* @return") =>
          state.startSection(Section.Return)
        case (state, s"*/") =>
          state.conclude
        case (state, s"* $line") =>
          state.addLine(line)
        case (state, other) =>
          state
      }
      .toDocs

  }

  enum Section {
    case Main
    case Param(name: String)
    case Return
  }

  case class State(
      section: Section,
      main: Seq[String],
      params: Map[String, String],
      output: Seq[String],
      current: Seq[String]
  ) {
    def addLine(line: String) = copy(current = current :+ line)
    def startSection(newSection: Section) = conclude.copy(section = newSection)
    def conclude = section match {
      case Section.Main   => copy(main = current, current = Seq.empty)
      case Section.Return => copy(output = current, current = Seq.empty)
      case Section.Param(name) =>
        copy(params = params + (name -> current.mkString(System.lineSeparator)), current = Seq.empty)
    }
    def toDocs: Docs = {
      val maybeOutput = if (output.nonEmpty) Some(output.mkString(System.lineSeparator)) else None
      Docs(main.mkString(System.lineSeparator), params, maybeOutput)
    }
  }

  object State {
    def start = State(Section.Main, Seq.empty, Map.empty, Seq.empty, Seq.empty)
  }

  object whitespaces {
    def unapply(string: String): Boolean = string.forall(_.isWhitespace)
  }

  object param {
    def unapply(string: String): Option[String] = if (string.exists(_.isWhitespace)) None else Some(string)
  }

}
