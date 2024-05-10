package smithy4s.deriving.internals

import smithy4s.deriving.{*, given}
import smithy4s.schema.Schema
import smithy4s.ShapeTag
import smithy4s.ShapeId

case class SourcePosition(
    path: String,
    start: Int,
    startLine: Int,
    startColumn: Int,
    end: Int,
    endLine: Int,
    endColumn: Int
) derives Schema

object SourcePosition extends ShapeTag.Companion[SourcePosition] {
  val id: ShapeId = ShapeId("smithy4s.deriving.internals", "SourcePosition")
  def schema: Schema[SourcePosition] = derived$Schema
}
