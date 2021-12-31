package scala.scalanative
package nir

import scala.annotation.tailrec
import scala.language.implicitConversions

final class Sig(val mangle: String) {
  final def toProxy: Sig =
    if (isMethod) {
      val Sig.Method(id, types, _) = this.unmangled
      Sig.Proxy(id, types.init).mangled
    } else {
      util.unsupported(
        s"can't convert non-method sig ${this.mangle} to proxy sig"
      )
    }
  final def show: String =
    Show(this)
  final override def equals(other: Any): Boolean =
    (this eq other.asInstanceOf[AnyRef]) || (other match {
      case other: Sig => other.mangle == mangle
      case _          => false
    })
  final override lazy val hashCode: Int =
    mangle.##
  final override def toString: String =
    mangle
  final def unmangled: Sig.Unmangled = Unmangle.unmangleSig(mangle)

  final def isField: Boolean = mangle(0) == 'F'
  final def isCtor: Boolean = mangle(0) == 'R'
  final def isClinit: Boolean = mangle(0) == 'I'
  final def isImplCtor: Boolean = mangle.startsWith("M6$init$")
  final def isMethod: Boolean = mangle(0) == 'D'
  final def isProxy: Boolean = mangle(0) == 'P'
  final def isExtern: Boolean = mangle(0) == 'C'
  final def isGenerated: Boolean = mangle(0) == 'G'
  final def isDuplicate: Boolean = mangle(0) == 'K'

  final def isVirtual = !(isCtor || isClinit || isImplCtor || isExtern)
  final def isPrivate: Boolean = privateIn.isDefined
  final def isStatic: Boolean = {
    def isPublicStatic = mangle.last == 'o'
    def isPrivateStatic = {
      val sigEnd = mangle.lastIndexOf('E')
      val scopeIdx = sigEnd + 1
      def hasScope = mangle.length() > scopeIdx
      sigEnd > 0 && hasScope && mangle(sigEnd + 1) == 'p'
    }
    isPublicStatic || isPrivateStatic
  }
  final lazy val privateIn: Option[Global.Top] = {
    val sigEnd = mangle.lastIndexOf('E')
    val scopeIdx = sigEnd + 1
    def hasScope = mangle.length() > scopeIdx
    def isPrivate = {
      val scopeIdent = mangle(scopeIdx)
      scopeIdent == 'p' || scopeIdent == 'P'
    }
    if (sigEnd > 0 && hasScope && isPrivate) {
      val global = Unmangle.unmangleGlobal(mangle.substring(sigEnd + 2))
      Some(global.top)
    } else None
  }
}
object Sig {
  sealed abstract class Scope(
      val isStatic: Boolean,
      val privateIn: Option[Global]
  ) {
    def isPublic: Boolean = privateIn.isEmpty
  }
  object Scope {
    case object Public extends Scope(false, None)
    case object PublicStatic extends Scope(true, None)
    final case class Private(in: Global) extends Scope(false, Some(in))
    final case class PrivateStatic(in: Global) extends Scope(true, Some(in))
  }

  sealed abstract class Unmangled {
    final def mangled: Sig = new Sig(Mangle(this))
    def sigScope: Scope = this match {
      case Field(_, scope)     => scope
      case Method(_, _, scope) => scope
      case Duplicate(of, _)    => of.unmangled.sigScope
      case _                   => Scope.Public
    }
  }

  final case class Field(id: String, scope: Scope = Scope.Public)
      extends Unmangled

  final case class Method(
      id: String,
      types: Seq[Type],
      scope: Scope = Scope.Public
  ) extends Unmangled

  final case class Ctor(types: Seq[Type]) extends Unmangled
  final case class Clinit() extends Unmangled
  final case class Proxy(id: String, types: Seq[Type]) extends Unmangled
  final case class Extern(id: String) extends Unmangled
  final case class Generated(id: String) extends Unmangled
  final case class Duplicate(of: Sig, types: Seq[Type]) extends Unmangled

  implicit def unmangledToMangled(sig: Sig.Unmangled): Sig = sig.mangled
}
