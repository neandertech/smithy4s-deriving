package smithy4s.deriving.internals

import smithy4s.schema.Schema

private[deriving] object copyPositionToMember {

  def apply[A](schema: Schema[A]): Schema[A] = {
    schema.hints.get(SourcePosition) match
      case None           => schema
      case Some(position) => schema.addMemberHints(position)
  }

}
