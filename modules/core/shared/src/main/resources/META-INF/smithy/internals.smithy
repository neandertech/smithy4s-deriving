$version: "2"

namespace smithy4s.deriving.internals

@trait()
structure SourcePosition {
    @required
    path: String
    @required
    start: Integer
    @required
    startLine: Integer
    @required
    startColumn: Integer
    @required
    end: Integer
    @required
    endLine: Integer
    @required
    endColumn: Integer
}
