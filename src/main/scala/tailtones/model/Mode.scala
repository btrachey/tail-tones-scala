package tailtones.model

/** Sonification mode (plan §6.3): three policies over one mechanism. The gain
  * derivation (S3) only branches on `AlertOnly`; `Builtin` vs `Media` differ in
  * *what* the bed is (synth voices vs `BedLoop`), which the engine decides.
  *
  * Config parsing (S7) maps `"builtin" | "media" | "alert-only"` onto these; S9
  * wires config mode -> `tailtones.model.Mode`.
  */
enum Mode:
  case Builtin, Media, AlertOnly

object Mode:

  def fromId(id: String): Option[Mode] = id match
    case "builtin"    => Some(Mode.Builtin)
    case "media"      => Some(Mode.Media)
    case "alert-only" => Some(Mode.AlertOnly)
    case _            => None
