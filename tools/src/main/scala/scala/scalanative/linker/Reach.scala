package scala.scalanative
package linker

import java.nio.file.{Path, Paths}
import scala.annotation.tailrec
import scala.collection.mutable
import scalanative.nir._

class Reach(
    protected val config: build.Config,
    entries: Seq[Global],
    loader: ClassLoader
) extends LinktimeValueResolver {
  import Reach._

  val loaded = mutable.Map.empty[Global.Top, mutable.Map[Global, Defn]]
  val unreachable = mutable.Map.empty[Global, UnreachableSymbol]
  val unsupported = mutable.Map.empty[Global, UnsupportedFeature]
  val enqueued = mutable.Set.empty[Global]
  var todo = List.empty[Global]
  val done = mutable.Map.empty[Global, Defn]
  var stack = List.empty[Global]
  val links = mutable.Set.empty[Attr.Link]
  val infos = mutable.Map.empty[Global, Info]
  val from = mutable.Map.empty[Global, ReferencedFrom]

  val dyncandidates = mutable.Map.empty[Sig, mutable.Set[Global.Member]]
  val dynsigs = mutable.Set.empty[Sig]
  val dynimpls = mutable.Set.empty[Global.Member]

  private case class DelayedMethod(owner: Global.Top, sig: Sig, pos: Position)
  private val delayedMethods = mutable.Set.empty[DelayedMethod]

  if (injects.nonEmpty) {
    injects.groupBy(_.name.top).foreach {
      case (owner, defns) =>
        val buf = mutable.Map.empty[Global, Defn]
        loaded.update(owner, buf)
        defns.foreach(defn => buf.update(defn.name, defn))
    }
    injects.foreach(reachDefn)
  }

  entries.foreach(reachEntry(_)(nir.Position.NoPosition))

  // Internal hack used inside linker tests, for more information
  // check out comment in scala.scalanative.linker.ReachabilitySuite
  val reachStaticConstructors = sys.props
    .get("scala.scalanative.linker.reachStaticConstructors")
    .flatMap(v => scala.util.Try(v.toBoolean).toOption)
    .forall(_ == true)

  loader.classesWithEntryPoints.foreach { clsName =>
    if (reachStaticConstructors) reachClinit(clsName)(nir.Position.NoPosition)
    config.compilerConfig.buildTarget match {
      case build.BuildTarget.Application => ()
      case _                             => reachExported(clsName)
    }
  }

  def result(): ReachabilityAnalysis = {
    cleanup()

    val defns = mutable.UnrolledBuffer.empty[Defn]
    defns.sizeHint(done.size)
    // drop the null values that have been introduced
    // in reachUnavailable
    done.valuesIterator.filter(_ != null).foreach(defns += _)

    if (unreachable.isEmpty && unsupported.isEmpty)
      new ReachabilityAnalysis.Result(
        infos = infos,
        entries = entries,
        links = links.toSeq,
        defns = defns.toSeq,
        dynsigs = dynsigs.toSeq,
        dynimpls = dynimpls.toSeq,
        resolvedVals = resolvedNirValues
      )
    else
      new ReachabilityAnalysis.Failure(
        defns = defns.toSeq,
        unreachable = unreachable.values.toSeq,
        unsupportedFeatures = unsupported.values.toSeq
      )
  }

  def cleanup(): Unit = {
    // Remove all unreachable methods from the
    // responds and defaultResponds of every class.
    // Optimizer and codegen may never increase reachability
    // past what's known now, so it's safe to do this.
    infos.foreach {
      case (_, cls: Class) =>
        val responds = cls.responds.toArray
        responds.foreach {
          case (sig, name) =>
            if (!done.contains(name)) {
              cls.responds -= sig
            }
        }

        val defaultResponds = cls.defaultResponds.toArray
        defaultResponds.foreach {
          case (sig, name) =>
            if (!done.contains(name)) {
              cls.defaultResponds -= sig
            }
        }

      case _ => ()
    }
  }

  def lookup(global: Global): Option[Defn] =
    lookup(global, ignoreIfUnavailable = false)

  private def lookup(
      global: Global,
      ignoreIfUnavailable: Boolean
  ): Option[Defn] = {
    val owner = global.top
    if (!loaded.contains(owner) && !unreachable.contains(owner)) {
      loader
        .load(owner)
        .fold[Unit] {
          if (!ignoreIfUnavailable) addMissing(global)
        } { defns =>
          val scope = mutable.Map.empty[Global, Defn]
          defns.foreach { defn => scope(defn.name) = defn }
          loaded(owner) = scope
        }
    }

    loaded
      .get(owner)
      .flatMap(_.get(global))
      .orElse {
        if (!ignoreIfUnavailable) addMissing(global)
        None
      }
  }

  def process(): Unit =
    while (todo.nonEmpty) {
      val name = todo.head
      todo = todo.tail
      if (!done.contains(name)) {
        reachDefn(name)
      }
    }

  @tailrec
  final def processDelayed(): Unit = {
    // Recursively iterate delayed methods - processing delayed method that has existing implementation
    // might result in calling other delayed method. Loop until no more delayedMethods are found
    if (delayedMethods.nonEmpty) {
      /*  Check methods that were marked to not have any defined targets yet when processing loop.
       *  At this stage they should define at least 1 target, or should be marked as a missing symbol.
       */
      delayedMethods.foreach {
        case DelayedMethod(top, sig, position) =>
          def addMissing() = this.addMissing(top.member(sig))
          scopeInfo(top)(position).fold(addMissing()) { info =>
            val wasAllocated = info match {
              case value: Trait => value.implementors.exists(_.allocated)
              case clazz: Class => clazz.allocated
            }
            val targets = info.targets(sig)
            if (targets.isEmpty && wasAllocated) {
              addMissing()
            } else {
              todo ++= targets
            }
          }
      }

      delayedMethods.clear()
      process()
      processDelayed()
    }
  }

  def reachDefn(name: Global): Unit = {
    stack ::= name
    lookup(name).fold[Unit] {
      reachUnavailable(name)
    } { defn =>
      if (defn.attrs.isStub && !config.linkStubs) {
        reachUnavailable(name)
      } else {
        val maybeFixedDefn = defn match {
          case defn: Defn.Define => resolveLinktimeDefine(defn)
          case _                 => defn
        }
        reachDefn(maybeFixedDefn)
      }
    }
    stack = stack.tail
  }

  def reachDefn(defn: Defn): Unit = {
    implicit val srcPosition = defn.pos
    defn match {
      case defn: Defn.Var =>
        reachVar(defn)
      case defn: Defn.Const =>
        reachConst(defn)
      case defn: Defn.Declare =>
        reachDeclare(defn)
      case defn: Defn.Define =>
        val Global.Member(_, sig) = defn.name
        if (Rt.arrayAlloc.contains(sig)) {
          classInfo(Rt.arrayAlloc(sig)).foreach(reachAllocation)
        }
        reachDefine(resolveLinktimeDefine(defn))
      case defn: Defn.Trait =>
        reachTrait(defn)
      case defn: Defn.Class =>
        reachClass(defn)
      case defn: Defn.Module =>
        reachModule(defn)
    }
    done(defn.name) = defn
  }

  def reachEntry(name: Global)(implicit srcPosition: nir.Position): Unit = {
    if (!name.isTop) {
      reachEntry(name.top)
    }
    from.getOrElseUpdate(name, ReferencedFrom.Root)
    reachGlobalNow(name)
    infos.get(name) match {
      case Some(cls: Class) =>
        if (!cls.attrs.isAbstract) {
          reachAllocation(cls)(cls.position)
          if (cls.isModule) {
            val init = cls.name.member(Sig.Ctor(Seq.empty))
            if (loaded(cls.name).contains(init)) {
              reachGlobal(init)(cls.position)
            }
          }
        }
      case _ =>
        ()
    }
  }

  def reachClinit(
      clsName: Global.Top
  )(implicit srcPosition: nir.Position): Unit = {
    reachGlobalNow(clsName)
    infos.get(clsName).foreach { cls =>
      val clinit = clsName.member(Sig.Clinit)
      if (loaded(clsName).contains(clinit)) {
        reachGlobal(clinit)(cls.position)
      }
    }
  }

  def reachExported(name: Global.Top): Unit = {
    def isExported(defn: Defn) = defn match {
      case Defn.Define(attrs, Global.Member(_, sig), _, _, _) =>
        attrs.isExtern || sig.isExtern
      case _ => false
    }

    for {
      cls <- infos.get(name).collect { case info: ScopeInfo => info }
      defns <- loaded.get(cls.name)
      (name, defn) <- defns
    } if (isExported(defn)) reachGlobal(name)(defn.pos)
  }

  def reachGlobal(name: Global)(implicit srcPosition: nir.Position): Unit =
    if (!enqueued.contains(name) && name.ne(Global.None)) {
      enqueued += name
      track(name)
      todo ::= name
    }

  def reachGlobalNow(name: Global)(implicit srcPosition: nir.Position): Unit =
    if (done.contains(name)) {
      ()
    } else if (!stack.contains(name)) {
      enqueued += name
      track(name)
      reachDefn(name)
    } else {
      val lines = (s"cyclic reference to ${name.show}:" +:
        stack.map(el => s"* ${el.show}"))
      fail(lines.mkString("\n"))
    }

  def newInfo(info: Info): Unit = {
    infos(info.name) = info
    info match {
      case info: MemberInfo =>
        info.owner match {
          case owner: ScopeInfo =>
            owner.members += info
          case _ =>
            ()
        }
      case info: Trait =>
        // Register given trait as a subtrait of
        // all its transitive parent traits.
        def loopTraits(traitInfo: Trait): Unit = {
          traitInfo.subtraits += info
          traitInfo.traits.foreach(loopTraits)
        }
        info.traits.foreach(loopTraits)

        // Initialize default method implementations that
        // can be resolved on a given trait. It includes both
        // all of its parent default methods and any of the
        // non-abstract method declared directly in this trait.
        info.linearized.foreach {
          case parentTraitInfo: Trait =>
            info.responds ++= parentTraitInfo.responds
          case _ =>
            util.unreachable
        }
        loaded(info.name).foreach {
          case (_, defn: Defn.Define) =>
            val Global.Member(_, sig) = defn.name
            info.responds(sig) = defn.name
          case _ =>
            ()
        }
      case info: Class =>
        // Register given class as a subclass of all
        // transitive parents and as an implementation
        // of all transitive traits.
        def loopParent(parentInfo: Class): Unit = {
          parentInfo.implementors += info
          parentInfo.subclasses += info
          parentInfo.parent.foreach(loopParent)
          parentInfo.traits.foreach(loopTraits)
        }
        def loopTraits(traitInfo: Trait): Unit = {
          traitInfo.implementors += info
          traitInfo.traits.foreach(loopTraits)
        }
        info.parent.foreach(loopParent)
        info.traits.foreach(loopTraits)

        // Initialize responds map to keep track of all
        // signatures this class responds to and its
        // corresponding implementation. Some of the entries
        // may end up being not reachable, we remove those
        // in the cleanup right before we return the result.
        info.parent.foreach { parentInfo =>
          info.responds ++= parentInfo.responds
        }
        loaded(info.name).foreach {
          case (_, defn: Defn.Define) =>
            val Global.Member(_, sig) = defn.name
            def update(sig: Sig): Unit = {
              info.responds(sig) = lookup(info, sig)
                .getOrElse(
                  fail(s"Required method ${sig} not found in ${info.name}")
                )
            }
            sig match {
              case Rt.JavaEqualsSig =>
                update(Rt.ScalaEqualsSig)
                update(Rt.JavaEqualsSig)
              case Rt.JavaHashCodeSig =>
                update(Rt.ScalaHashCodeSig)
                update(Rt.JavaHashCodeSig)
              case sig
                  if sig.isMethod || sig.isCtor || sig.isClinit || sig.isGenerated =>
                update(sig)
              case _ =>
                ()
            }
          case _ =>
            ()
        }

        // Initialize the scope of the default methods that can
        // be used as a fallback if no method implementation is given
        // in a given class.
        info.linearized.foreach {
          case traitInfo: Trait =>
            info.defaultResponds ++= traitInfo.responds
          case _ =>
            ()
        }
      case _ =>
        ()
    }
  }

  def reachAllocation(info: Class)(implicit srcPosition: nir.Position): Unit =
    if (!info.allocated) {
      info.allocated = true

      // Handle all class and trait virtual calls
      // on this class. This includes virtual calls
      // on the traits that this class implements and
      // calls on all transitive parents.
      val calls = mutable.Set.empty[Sig]
      calls ++= info.calls
      def loopParent(parentInfo: Class): Unit = {
        calls ++= parentInfo.calls
        parentInfo.parent.foreach(loopParent)
        parentInfo.traits.foreach(loopTraits)
      }
      def loopTraits(traitInfo: Trait): Unit = {
        calls ++= traitInfo.calls
        traitInfo.traits.foreach(loopTraits)
      }
      info.parent.foreach(loopParent)
      info.traits.foreach(loopTraits)
      calls.foreach { sig =>
        def respondImpl = info.responds.get(sig)
        def defaultImpl = info.defaultResponds.get(sig)
        respondImpl
          .orElse(defaultImpl)
          .foreach(reachGlobal)
      }

      // 1. Handle all dynamic methods on this class.
      //    Any method that implements a known dynamic
      //    signature becomes reachable. The others are
      //    stashed as dynamic candidates.
      // 2. FuncPtr extern forwarder becomes reachable if
      //    class itself is reachable.
      info.responds.foreach {
        case (sig, impl) if sig.isMethod =>
          val dynsig = sig.toProxy
          if (!dynsigs.contains(dynsig)) {
            val buf =
              dyncandidates.getOrElseUpdate(
                dynsig,
                mutable.Set.empty[Global.Member]
              )
            buf += impl
          } else {
            dynimpls += impl
            reachGlobal(impl)
          }
        case (sig, impl)
            if sig.isGenerated
              && sig.unmangled
                .asInstanceOf[Sig.Generated]
                .id == "$extern$forwarder" =>
          reachGlobal(impl)
        case _ =>
          ()
      }
    }

  def scopeInfo(
      name: Global.Top
  )(implicit srcPosition: nir.Position): Option[ScopeInfo] = {
    reachGlobalNow(name)
    infos(name) match {
      case info: ScopeInfo => Some(info)
      case _               => None
    }
  }

  def scopeInfoOrUnavailable(
      name: Global.Top
  )(implicit srcPosition: nir.Position): Info = {
    reachGlobalNow(name)
    infos(name) match {
      case info: ScopeInfo   => info
      case info: Unavailable => info
      case _                 => util.unreachable
    }
  }

  def classInfo(
      name: Global.Top
  )(implicit srcPosition: nir.Position): Option[Class] = {
    reachGlobalNow(name)
    infos(name) match {
      case info: Class => Some(info)
      case _           => None
    }
  }

  def classInfoOrObject(
      name: Global.Top
  )(implicit srcPosition: nir.Position): Class =
    classInfo(name)
      .orElse(classInfo(Rt.Object.name))
      .getOrElse(fail(s"Class info not available for $name"))

  def traitInfo(
      name: Global.Top
  )(implicit srcPosition: nir.Position): Option[Trait] = {
    reachGlobalNow(name)
    infos(name) match {
      case info: Trait => Some(info)
      case _           => None
    }
  }

  def methodInfo(
      name: Global
  )(implicit srcPosition: nir.Position): Option[Method] = {
    reachGlobalNow(name)
    infos(name) match {
      case info: Method => Some(info)
      case _            => None
    }
  }

  def fieldInfo(
      name: Global
  )(implicit srcPosition: nir.Position): Option[Field] = {
    reachGlobalNow(name)
    infos(name) match {
      case info: Field => Some(info)
      case _           => None
    }
  }

  def reachUnavailable(name: Global): Unit = {
    newInfo(new Unavailable(name))
    addMissing(name)
    // Put a null definition to indicate that name
    // is effectively done and doesn't need to be
    // visited any more. This saves us the need to
    // check the unreachable set every time we check
    // if something is truly handled.
    done(name) = null
  }

  def reachVar(defn: Defn.Var): Unit = {
    val Defn.Var(attrs, name, ty, rhs) = defn
    implicit val pos: nir.Position = defn.pos
    newInfo(
      new Field(
        attrs,
        scopeInfoOrUnavailable(name.top),
        name,
        isConst = false,
        ty,
        rhs
      )
    )
    reachAttrs(attrs)
    reachType(ty)
    reachVal(rhs)
  }

  def reachConst(defn: Defn.Const): Unit = {
    val Defn.Const(attrs, name, ty, rhs) = defn
    implicit val pos: nir.Position = defn.pos
    newInfo(
      new Field(
        attrs,
        scopeInfoOrUnavailable(name.top),
        name,
        isConst = true,
        ty,
        rhs
      )
    )
    reachAttrs(attrs)
    reachType(ty)
    reachVal(rhs)
  }

  def reachDeclare(defn: Defn.Declare): Unit = {
    val Defn.Declare(attrs, name, ty) = defn
    implicit val pos: nir.Position = defn.pos
    newInfo(
      new Method(
        attrs,
        scopeInfoOrUnavailable(name.top),
        name,
        ty,
        insts = Array(),
        debugInfo = Defn.Define.DebugInfo.empty
      )
    )
    reachAttrs(attrs)
    reachType(ty)
  }

  def reachDefine(defn: Defn.Define): Unit = {
    val Defn.Define(attrs, name, ty, insts, debugInfo) = defn
    implicit val pos: nir.Position = defn.pos
    newInfo(
      new Method(
        attrs,
        scopeInfoOrUnavailable(name.top),
        name,
        ty,
        insts.toArray,
        debugInfo
      )
    )
    reachAttrs(attrs)
    reachType(ty)
    reachInsts(insts)
  }

  def reachTrait(defn: Defn.Trait): Unit = {
    val Defn.Trait(attrs, name, traits) = defn
    implicit val pos: nir.Position = defn.pos
    newInfo(new Trait(attrs, name, traits.flatMap(traitInfo)))
    reachAttrs(attrs)
  }

  def reachClass(defn: Defn.Class): Unit = {
    val Defn.Class(attrs, name, parent, traits) = defn
    implicit val pos: nir.Position = defn.pos
    newInfo(
      new Class(
        attrs,
        name,
        parent.map(classInfoOrObject),
        traits.flatMap(traitInfo),
        isModule = false
      )
    )
    reachAttrs(attrs)
  }

  def reachModule(defn: Defn.Module): Unit = {
    val Defn.Module(attrs, name, parent, traits) = defn
    implicit val pos: nir.Position = defn.pos
    newInfo(
      new Class(
        attrs,
        name,
        parent.map(classInfoOrObject),
        traits.flatMap(traitInfo),
        isModule = true
      )
    )
    reachAttrs(attrs)
  }

  def reachAttrs(attrs: Attrs): Unit =
    links ++= attrs.links

  def reachType(ty: Type)(implicit srcPosition: nir.Position): Unit = ty match {
    case Type.ArrayValue(ty, n) =>
      reachType(ty)
    case Type.StructValue(tys) =>
      tys.foreach(reachType)
    case Type.Function(args, ty) =>
      args.foreach(reachType)
      reachType(ty)
    case Type.Ref(name, _, _) =>
      reachGlobal(name)
    case Type.Var(ty) =>
      reachType(ty)
    case Type.Array(ty, _) =>
      reachType(ty)
    case _ =>
      ()
  }

  def reachVal(value: Val)(implicit srcPosition: nir.Position): Unit =
    value match {
      case Val.Zero(ty)            => reachType(ty)
      case Val.StructValue(values) => values.foreach(reachVal)
      case Val.ArrayValue(ty, values) =>
        reachType(ty)
        values.foreach(reachVal)
      case Val.Local(_, ty) => reachType(ty)
      case Val.Global(n, ty) =>
        reachGlobal(n)
        reachType(ty)
      case Val.Const(v)     => reachVal(v)
      case Val.ClassOf(cls) => reachGlobal(cls)
      case _                => ()
    }

  def reachInsts(insts: Seq[Inst]): Unit =
    insts.foreach(reachInst)

  def reachInst(inst: Inst): Unit = {
    implicit val srcPosition: nir.Position = inst.pos
    inst match {
      case Inst.Label(n, params) =>
        params.foreach(p => reachType(p.ty))
      case Inst.Let(_, op, unwind) =>
        reachOp(op)(inst.pos)
        reachNext(unwind)
      case Inst.Ret(v) =>
        reachVal(v)
      case Inst.Jump(next) =>
        reachNext(next)
      case Inst.If(v, thenp, elsep) =>
        reachVal(v)
        reachNext(thenp)
        reachNext(elsep)
      case Inst.Switch(v, default, cases) =>
        reachVal(v)
        reachNext(default)
        cases.foreach(reachNext)
      case Inst.Throw(v, unwind) =>
        reachVal(v)
        reachNext(unwind)
      case Inst.Unreachable(unwind) =>
        reachNext(unwind)
      case _: Inst.LinktimeIf =>
        util.unreachable
    }
  }

  def reachOp(op: Op)(implicit pos: Position): Unit = op match {
    case Op.Call(ty, ptrv, argvs) =>
      reachType(ty)
      reachVal(ptrv)
      argvs.foreach(reachVal)
    case Op.Load(ty, ptrv, syncAttrs) =>
      reachType(ty)
      reachVal(ptrv)
    case Op.Store(ty, ptrv, v, syncAttrs) =>
      reachType(ty)
      reachVal(ptrv)
      reachVal(v)
    case Op.Elem(ty, ptrv, indexvs) =>
      reachType(ty)
      reachVal(ptrv)
      indexvs.foreach(reachVal)
    case Op.Extract(aggrv, indexvs) =>
      reachVal(aggrv)
    case Op.Insert(aggrv, v, indexvs) =>
      reachVal(aggrv)
      reachVal(v)
    case Op.Stackalloc(ty, v) =>
      reachType(ty)
      reachVal(v)
      ty match {
        case ref: Type.RefKind =>
          classInfo(ref.className).foreach(reachAllocation)
        case _ => ()
      }
    case Op.Bin(bin, ty, lv, rv) =>
      reachType(ty)
      reachVal(lv)
      reachVal(rv)
    case Op.Comp(comp, ty, lv, rv) =>
      reachType(ty)
      reachVal(lv)
      reachVal(rv)
    case Op.Conv(conv, ty, v) =>
      reachType(ty)
      reachVal(v)
    case Op.Fence(attrs) => ()

    case Op.Classalloc(n, zoneHandle) =>
      classInfo(n).foreach(reachAllocation)
      zoneHandle.foreach(reachVal)
    case Op.Fieldload(ty, v, n) =>
      reachType(ty)
      reachVal(v)
      reachGlobal(n)
    case Op.Fieldstore(ty, v1, n, v2) =>
      reachType(ty)
      reachVal(v1)
      reachGlobal(n)
      reachVal(v2)
    case Op.Field(obj, name) =>
      reachVal(obj)
      reachGlobal(name)
    case Op.Method(obj, sig) =>
      reachVal(obj)
      reachMethodTargets(obj.ty, sig)
    case Op.Dynmethod(obj, dynsig) =>
      reachVal(obj)
      reachDynamicMethodTargets(dynsig)
    case Op.Module(n) =>
      classInfo(n).foreach(reachAllocation)
      val init = n.member(Sig.Ctor(Seq.empty))
      loaded.get(n).fold(addMissing(n)) { defn =>
        if (defn.contains(init)) {
          reachGlobal(init)
        }
      }
    case Op.As(ty, v) =>
      reachType(ty)
      reachVal(v)
    case Op.Is(ty, v) =>
      reachType(ty)
      reachVal(v)
    case Op.Copy(v) =>
      reachVal(v)
    case Op.SizeOf(ty)      => reachType(ty)
    case Op.AlignmentOf(ty) => reachType(ty)
    case Op.Box(code, obj) =>
      reachVal(obj)
    case Op.Unbox(code, obj) =>
      reachVal(obj)
    case Op.Var(ty) =>
      reachType(ty)
    case Op.Varload(slot) =>
      reachVal(slot)
    case Op.Varstore(slot, value) =>
      reachVal(slot)
      reachVal(value)
    case Op.Arrayalloc(ty, init, zoneHandle) =>
      classInfo(Type.toArrayClass(ty)).foreach(reachAllocation)
      reachType(ty)
      reachVal(init)
      zoneHandle.foreach(reachVal)
    case Op.Arrayload(ty, arr, idx) =>
      reachType(ty)
      reachVal(arr)
      reachVal(idx)
    case Op.Arraystore(ty, arr, idx, value) =>
      reachType(ty)
      reachVal(arr)
      reachVal(idx)
      reachVal(value)
    case Op.Arraylength(arr) =>
      reachVal(arr)
  }

  def reachNext(next: Next)(implicit srcPosition: nir.Position): Unit =
    next match {
      case Next.Label(_, args) =>
        args.foreach(reachVal)
      case _ =>
        ()
    }

  def reachMethodTargets(ty: Type, sig: Sig)(implicit
      srcPosition: Position
  ): Unit =
    ty match {
      case Type.Array(ty, _) =>
        reachMethodTargets(Type.Ref(Type.toArrayClass(ty)), sig)
      case Type.Ref(name, _, _) =>
        scopeInfo(name).foreach { scope =>
          if (!scope.calls.contains(sig)) {
            scope.calls += sig
            val targets = scope.targets(sig)
            if (targets.nonEmpty) targets.foreach(reachGlobal)
            else {
              // At this stage we cannot tell if method target is not defined or not yet reached
              // We're delaying resolving targets to the end of Reach phase to check if this method is never defined in NIR
              track(name.member(sig))
              delayedMethods += DelayedMethod(name, sig, srcPosition)
            }
          }
        }
      case _ =>
        ()
    }

  def reachDynamicMethodTargets(
      dynsig: Sig
  )(implicit srcPosition: nir.Position) = {
    if (!dynsigs.contains(dynsig)) {
      dynsigs += dynsig
      if (dyncandidates.contains(dynsig)) {
        dyncandidates(dynsig).foreach { impl =>
          dynimpls += impl
          reachGlobal(impl)
        }
        dyncandidates -= dynsig
      }
    }
  }

  def lookup(cls: Class, sig: Sig): Option[Global.Member] = {
    assert(loaded.contains(cls.name))

    def lookupSig(cls: Class, sig: Sig): Option[Global.Member] = {
      val tryMember = cls.name.member(sig)
      if (loaded(cls.name).contains(tryMember)) {
        Some(tryMember)
      } else {
        cls.parent.flatMap(lookupSig(_, sig))
      }
    }

    def lookupRequired(sig: Sig) = lookupSig(cls, sig)
      .getOrElse(fail(s"Not found required definition ${cls.name} ${sig}"))

    sig match {
      // We short-circuit scala_== and scala_## to immeditately point to the
      // equals and hashCode implementation for the reference types to avoid
      // double virtual dispatch overhead. This optimization is *not* optional
      // as implementation of scala_== on java.lang.Object assumes it's only
      // called on classes which don't overrider java_==.
      case Rt.ScalaEqualsSig =>
        val scalaImpl = lookupRequired(Rt.ScalaEqualsSig)
        val javaImpl = lookupRequired(Rt.JavaEqualsSig)
        if (javaImpl.top != Rt.Object.name &&
            scalaImpl.top == Rt.Object.name) {
          Some(javaImpl)
        } else {
          Some(scalaImpl)
        }
      case Rt.ScalaHashCodeSig =>
        val scalaImpl = lookupRequired(Rt.ScalaHashCodeSig)
        val javaImpl = lookupRequired(Rt.JavaHashCodeSig)
        if (javaImpl.top != Rt.Object.name &&
            scalaImpl.top == Rt.Object.name) {
          Some(javaImpl)
        } else {
          Some(scalaImpl)
        }
      case _ =>
        lookupSig(cls, sig)
    }
  }

  protected def addMissing(global: Global): Unit =
    global match {
      case UnsupportedFeatureExtractor(details) =>
        unsupported.getOrElseUpdate(global, details)
      case _ =>
        unreachable.getOrElseUpdate(
          global, {
            val (kind, symbol) = parseSymbol(global)
            UnreachableSymbol(
              name = global,
              kind = kind,
              symbol = symbol,
              backtrace = getBackTrace(global)
            )
          }
        )
    }

  private def parseSymbol(name: Global): (String, String) = {
    def parseSig(owner: String, sig: Sig): (String, String) =
      sig.unmangled match {
        case Sig.Method(name, _, _) => "method" -> s"$owner.${name}"
        case Sig.Ctor(tys) =>
          val ctorTys = tys
            .map {
              case ty: Type.RefKind => ty.className.id
              case ty               => ty.show
            }
            .mkString(",")
          "constructor" -> s"$owner($ctorTys)"
        case Sig.Clinit         => "static constructor" -> owner
        case Sig.Field(name, _) => "field" -> s"$owner.$name"
        case Sig.Generated(name) =>
          "generated method" -> s"$owner.${name}"
        case Sig.Proxy(name, _) => "proxy method" -> s"$owner.$name"
        case Sig.Duplicate(sig, _) =>
          val (kind, name) = parseSig(owner, sig)
          s"duplicate $kind" -> s"$owner.name"
        case Sig.Extern(name) => s"extern method" -> s"$owner.$name"
      }

    name match {
      case Global.Member(owner, sig) => parseSig(owner.id, sig)
      case Global.Top(id)            => "type" -> id
      case _                         => util.unreachable
    }
  }

  private def getBackTrace(referencedFrom: Global): List[BackTraceElement] = {
    val buf = List.newBuilder[BackTraceElement]
    def loop(name: Global): List[BackTraceElement] = {
      // orElse just in case if we messed something up and failed to correctly track references
      // Accept possibly empty backtrace instead of crashing
      val current = from.getOrElse(name, ReferencedFrom.Root)
      if (current == ReferencedFrom.Root) buf.result()
      else {
        val file = current.srcPosition.filename.getOrElse("unknown")
        val line = current.srcPosition.line
        val (kind, symbol) = parseSymbol(current.referencedBy)
        buf += BackTraceElement(
          name = current.referencedBy,
          kind = kind,
          symbol = symbol,
          filename = file,
          line = line + 1
        )
        loop(current.referencedBy)
      }
    }
    loop(referencedFrom)
  }

  protected object UnsupportedFeatureExtractor {
    import UnsupportedFeature._
    val UnsupportedSymbol =
      Global.Top("scala.scalanative.runtime.UnsupportedFeature")

    // Add stubs for NIR when checkFeatures is disabled
    val injects: Seq[nir.Defn] =
      if (config.compilerConfig.checkFeatures) Nil
      else {
        import scala.scalanative.nir._
        implicit val srcPosition: nir.Position = nir.Position.NoPosition
        val stubMethods = for {
          methodName <- Seq("threads", "virtualThreads", "continuations")
        } yield {
          import scala.scalanative.codegen.Lower.{
            throwUndefined,
            throwUndefinedTy,
            throwUndefinedVal
          }
          implicit val scopeId: nir.ScopeId = nir.ScopeId.TopLevel
          Defn.Define(
            attrs = Attrs.None,
            name = UnsupportedSymbol.member(
              Sig.Method(methodName, Seq(Type.Unit), Sig.Scope.PublicStatic)
            ),
            ty = Type.Function(Nil, Type.Unit),
            insts = {
              implicit val fresh: Fresh = Fresh()
              val buf = new Buffer()
              buf.label(fresh(), Nil)
              buf.call(
                throwUndefinedTy,
                throwUndefinedVal,
                Seq(Val.Null),
                Next.None
              )
              buf.unreachable(Next.None)
              buf.toSeq
            }
          )
        }
        val stubType =
          Defn.Class(Attrs.None, UnsupportedSymbol, Some(Rt.Object.name), Nil)
        stubType +: stubMethods
      }

    private def details(sig: Sig): UnsupportedFeature.Kind = {
      sig.unmangled match {
        case Sig.Method("threads", _, _)        => SystemThreads
        case Sig.Method("virtualThreads", _, _) => VirtualThreads
        case Sig.Method("continuations", _, _)  => Continuations
        case _                                  => Other
      }
    }

    def unapply(name: Global): Option[UnsupportedFeature] = name match {
      case Global.Member(UnsupportedSymbol, sig) =>
        unsupported
          .get(name)
          .orElse(
            Some(
              UnsupportedFeature(
                kind = details(sig),
                backtrace = getBackTrace(name)
              )
            )
          )
      case _ => None
    }
  }

  private def fail(msg: => String): Nothing = {
    throw new LinkingException(msg)
  }

  protected def track(name: Global)(implicit srcPosition: nir.Position) =
    from.getOrElseUpdate(
      name,
      if (stack.isEmpty) ReferencedFrom.Root
      else ReferencedFrom(stack.head, srcPosition)
    )

  lazy val injects: Seq[nir.Defn] = UnsupportedFeatureExtractor.injects
}

object Reach {
  def apply(
      config: build.Config,
      entries: Seq[Global],
      loader: ClassLoader
  ): ReachabilityAnalysis = {
    val reachability = new Reach(config, entries, loader)
    reachability.process()
    reachability.processDelayed()
    reachability.result()
  }

  private[scalanative] case class ReferencedFrom(
      referencedBy: nir.Global,
      srcPosition: nir.Position
  )
  object ReferencedFrom {
    final val Root = ReferencedFrom(nir.Global.None, nir.Position.NoPosition)
  }
  case class BackTraceElement(
      name: Global,
      kind: String,
      symbol: String,
      filename: String,
      line: Int
  )
  case class UnreachableSymbol(
      name: Global,
      kind: String,
      symbol: String,
      backtrace: List[BackTraceElement]
  )

  case class UnsupportedFeature(
      kind: UnsupportedFeature.Kind,
      backtrace: List[BackTraceElement]
  )
  object UnsupportedFeature {
    sealed abstract class Kind(val details: String)
    case object SystemThreads
        extends Kind(
          "Application linked with disabled multithreading support. Adjust nativeConfig and try again"
        )
    case object VirtualThreads
        extends Kind("VirtualThreads are not supported yet on this platform")
    case object Continuations
        extends Kind("Continuations are not supported yet on this platform")
    case object Other extends Kind("Other unsupported feature")
  }
}
