package firepile.compiler

import scala.collection.mutable.Queue
import scala.collection.mutable.HashSet
import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer

import soot.jimple.toolkits.callgraph.CallGraphBuilder
import soot.jimple.toolkits.invoke.StaticInliner
import soot.jimple.toolkits.invoke.StaticMethodBinder
import soot.options.Options


import soot.Body
import soot.{Unit => SootUnit}
import soot.Scene
import soot.Value
import soot.ValueBox
import soot.Local
import soot.SootClass
import soot.SootMethod
import soot.SootMethodRef
import soot.Modifier
import soot.toolkits.graph.ExceptionalUnitGraph
import soot.toolkits.graph.UnitGraph
import soot.jimple.JimpleBody
import soot.jimple.Jimple
import soot.grimp.Grimp
import soot.grimp.GrimpBody
import soot.Type
import soot.jimple.Stmt
import soot.{VoidType => SootVoidType}
import soot.{BooleanType => SootBooleanType}
import soot.{ByteType => SootByteType}
import soot.{ShortType => SootShortType}
import soot.{CharType => SootCharType}
import soot.{IntType => SootIntType}
import soot.{LongType => SootLongType}
import soot.{FloatType => SootFloatType}
import soot.{DoubleType => SootDoubleType}
import soot.{RefType => SootRefType}
import soot.{ArrayType => SootArrayType}
import soot.{NullType => SootNullType}

import firepile.tree.Trees._
import soot.jimple.{ FloatConstant,
                     DoubleConstant,
                     IntConstant,
                     LongConstant,
                     StringConstant }
import firepile.compiler.GrimpUnapply._

import scala.collection.mutable.HashMap

object JVM2CL {
  def main(args: Array[String]) = {
    if (args.length != 2) {
      println("usage: sooty.Main className methodSig")
      exit(1)
    }

    val className = args(0)
    val methodSig = args(1)

    compileRoot(className, methodSig)
  }

  setup

  def compileRoot(className: String, methodSig: String, self: AnyRef = null): List[Tree] = {
    println("compiling " + className + "." + methodSig)
    try {
      addRootMethodToWorklist(className, methodSig, self)
      if (makeCallGraph) {
        buildCallGraph
        optimizeCallGraph
      }
      processWorklist
    } catch {
      case e: ClassNotFoundException => {
        println("Class not found: " + e.getMessage)
        Nil
      }
    }
  }

  private def setup = {
    // java.class.path is broken in Scala, especially when running under sbt
    Scene.v.setSootClassPath(Scene.v.defaultClassPath
      + ":/Users/nystrom/uta/funicular/funicular/firepile/target/scala_2.8.0.RC3/classes"
      + ":/Users/nystrom/uta/funicular/funicular/firepile/target/scala_2.8.0.RC3/test-classes"
      + ":.:tests:bin:lib/soot-2.4.0.jar:/opt/local/share/scala-2.8/lib/scala-library.jar")

    // might be useful if you want to relate back to source code
    Options.v.set_keep_line_number(true)
    Options.v.setPhaseOption("jb", "use-original-names:true")
    Options.v.setPhaseOption("cg", "safe-forname:false")
    // you can set context-sensitivity of call graph with this (more time consuming)
    // Options.v.setPhaseOption("cg", "context:1cfa") 
    Options.v.setPhaseOption("cg", "verbose:true")

    Options.v.set_allow_phantom_refs(true)
    // Options.v.set_whole_program(true)
  }

  def methodName(m: SootMethod): String = mangleName(m.getDeclaringClass.getName + m.getName)
  def methodName(m: java.lang.reflect.Method): String = mangleName(m.getDeclaringClass.getName + m.getName)
  def methodName(m: SootMethodRef): String = mangleName(m.declaringClass.getName + m.name)
  def mangleName(name: String) = name.replace('$', '_').replace('.', '_')

  private implicit def v2tree(v: Value): Tree = translateExp(v)

  class Worklist[A] extends Queue[A] {
    val inWorklist = new HashSet[A]

    override def +=(a: A) = {
      if (! inWorklist.contains(a)) {
        inWorklist += a
        super.+=(a)
      }
      else
        this
    }
  }

  trait Task {
    def run: List[Tree]
    def method: SootMethod
  }

  def mapDef(m: SootMethod, t: Tree) = t
  def reduceDef(m: SootMethod, t: Tree) = t

  object CompileMethodTask {
    def apply(m: SootMethodRef, self: AnyRef = null): CompileMethodTask = CompileMethodTask(m.resolve, self)
  }
  
  case class CompileMethodTask(method: SootMethod, self: AnyRef) extends Task {
    def run = compileMethod(method, self) match {
      case null => Nil
      case t => t::Nil
    }
  }

  private val worklist = new Worklist[Task]

  private val makeCallGraph = false

  private def addRootMethodToWorklist(className: String, methodSig: String, self: AnyRef): Unit = {
    // Set up the class we're working with
    val c = Scene.v.loadClassAndSupport(className)
    if (makeCallGraph) {
      Scene.v.loadNecessaryClasses
      c.setApplicationClass
    }

    // Retrieve the method and its body
    val i = c.methodIterator
    while (i.hasNext) {
      val m = i.next

      val sig = m.getName + soot.AbstractJasminClass.jasminDescriptorOf(m.makeRef)
      if (sig.equals(methodSig)) {
        worklist += CompileMethodTask(m, self)
      }
    }
  }

  private def findSelf(v: Value, self: AnyRef): AnyRef = null

  private def buildCallGraph = {
    Scene.v.setEntryPoints(worklist.toList.map(p => p.method))
    println("entry points " + Scene.v.getEntryPoints)
    (new CallGraphBuilder).build
    println(Scene.v.getCallGraph)
  }

  private def optimizeCallGraph = {
    // Bind methods statically.
    StaticMethodBinder.v.transform
    StaticInliner.v.transform
  }

  private def processWorklist = {
    val results = ListBuffer[Tree]()

    while (! worklist.isEmpty) {
      val task = worklist.dequeue
      val ts = task.run
      results ++= ts
    }
    results.toList
  }

  private def compileMethod(m: SootMethod, self: AnyRef): Tree = {
    symtab = new SymbolTable(self)

    println("-------------------------------------------------------")
    println(m)

    if (m.isAbstract)
        return null
    if (m.isNative)
        return null

    val b = m.retrieveActiveBody
    val gb = Grimp.v.newBody(b, "gb")

    val unitBuffer = ListBuffer[SootUnit]()
      for (u <- gb.getUnits) {
      unitBuffer += u
    }

    val units = unitBuffer.toList
    println("Grimp method body:")
    println(units.mkString("\n"))

    val body = translateUnits(units, Nil)
    val labeled = insertLabels(units, body, Nil)
    val fun = makeFunction(m, removeThis(labeled))

      // TODO: don't removeThis for normal methods; do removeThis for closures
      // TODO: verify that this is not used in the body of the method

    println()
    println("Result tree:")
    println(fun)

    println()
    println("Result CL:")
    println(fun.toCL)

    fun
  }

  private def fold(f: Tree => Tree)(t: Tree): Tree = f(t match {
    case Call(fun, args) => Call(fold(f)(fun), args.map(a => fold(f)(a)))
    case Bin(op1, op, op2) => Bin(fold(f)(op1), op, fold(f)(op2))
    case Un(op, op2) => Un(op, fold(f)(op2))
    case Eval(e) => Eval(fold(f)(e))
    case Assign(op1, op2) => Assign(fold(f)(op1), fold(f)(op2))
    case Select(op1, name) => Select(fold(f)(op1), name)
    case Ref(op1) => Ref(fold(f)(op1))
    case Deref(op1) => Deref(fold(f)(op1))
    case ArrayAccess(op1, op2) => ArrayAccess(fold(f)(op1), fold(f)(op2))
    case Cast(t, e) => Cast(t, fold(f)(e))
    case If(e, s1, s2) => If(fold(f)(e), fold(f)(s1), fold(f)(s2))
    case While(e, s) => While(fold(f)(e), fold(f)(s))
    case DoWhile(s, e) => DoWhile(fold(f)(s), fold(f)(e))
    case Return(e) => Return(fold(f)(e))
    case t => t
  })

  private def removeThis(body: List[Tree]): List[Tree] = {
    symtab.locals.remove(Id("this"))
    body match {
      case Eval(Assign(Id("this"), _))::ts => removeThis(ts)
      case t::ts => t::removeThis(ts)
      case Nil => Nil
    }
  }

  private class SymbolTable(val self: AnyRef) {
    val labels = new HashMap[SootUnit, String]()
    val params = new HashMap[Int, (Id, Type)]()
    val locals = new HashMap[Id, Type]()
    val arrays = new HashMap[Id, (Type, Tree)]()
    var thisParam: (Id, Type) = null

    def addThisParam(typ: Type, id: Id) = {
      thisParam = (id, typ)
    }

    def addParamVar(typ: Type, index: Int, id: Id) = {
      params += index -> (id, typ)
    }

    def addLocalVar(typ: Type, id: Id) = {
      assert(!params.contains(id))
      // assert(!locals.contains(id))
      locals += id -> typ
    }

    def addArrayDef(typ: Type, id: Id, size: Tree) = {
      assert(!locals.contains(id))
      arrays += id -> (typ, size)
    }

    var next = -1

    def nextLabel = {
      next += 1
      next
    }
  }

  private var symtab: SymbolTable = null

  private def translateLabel(u: SootUnit): String = u match {
    case target : Stmt => {
      symtab.labels.get(target) match {
        case Some(label) => label
        case None => {
          val label = "lbl_" + symtab.nextLabel
          symtab.labels += target -> label
          label
        }
      }
    }
    case _ => "Label"
  }

  private def translateType(t: Type): Tree = t match {
      case t : SootVoidType => ValueType("void")
      case t : SootBooleanType => ValueType("int")
      case t : SootByteType => ValueType("char")
      case t : SootShortType => ValueType("short")
      case t : SootCharType => ValueType("ushort")
      case t : SootIntType => ValueType("int")
      case t : SootLongType => ValueType("long")
      case t : SootFloatType => ValueType("float")
      case t : SootDoubleType => ValueType("double")
      case t : SootRefType => PtrType(ValueType(mangleName(t.toString)))
      case t : SootArrayType => PtrType(translateType(t.getArrayElementType))
      case t : SootNullType => PtrType(ValueType("void"))
      // TODO: array types
      case _ => ValueType(t.toString)
  }

  object MathCall {
      def unapply(v: Value): Option[(String,List[Value])] = {
        v match {
        // scala.Math.sin(x)  [deprecated]
        case GVirtualInvoke(GStaticFieldRef(SFieldRef(SClassName("scala.Math$"), "MODULE$", _, _)), SMethodRef(SClassName("scala.MathCommon"), name, _, _, _), args) => Some((name, args))
        case GVirtualInvoke(GStaticFieldRef(SFieldRef(SClassName("scala.Math$"), "MODULE$", _, _)), SMethodRef(SClassName("scala.Math$"), name, _, _, _), args) => Some((name, args))
        // scala.math.package$.sin(x)
        case GVirtualInvoke(GStaticFieldRef(SFieldRef(SClassName("scala.math.package$"), "MODULE$", _, _)), SMethodRef(SClassName("scala.MathCommon"), name, _, _, _), args) => Some((name, args))
        case GVirtualInvoke(GStaticFieldRef(SFieldRef(SClassName("scala.math.package$"), "MODULE$", _, _)), SMethodRef(SClassName("scala.math.package$"), name, _, _, _), args) => Some((name, args))
        // firepile.util.Math.sin(x)  TODO: FIX THIS
        case GVirtualInvoke(GStaticFieldRef(SFieldRef(SClassName("firepile.util.Math$"), "MODULE$", _, _)), SMethodRef(SClassName("firepile.util.Math"), name, _, _, _), args) => Some((name, args))
        // java.lang.Math.sin(x)
        case GStaticInvoke(SMethodRef(SClassName("java.lang.Math"), name, _, _, _), args) => Some((name, args))
        case _ => None
      }
      }
  }

  object FloatMathCall {
    def unapply(v: Value): Option[(String,List[Value])] = v match {
      // (float) sin((double) x) --> sin(x)
      case GCast(DoubleMathCall(name, args), f) if f.equals(SootFloatType.v) => Some((name, args))
      case _ => None
    }
  }

  object DoubleMathCall {
    def unapply(v: Value): Option[(String,List[Value])] =
      v match {
      // sin((double) x) --> (double) sin(x)
      case MathCall(name, List(GCast(FloatTyped(x), d))) if d.equals(SootDoubleType.v) => Some((name, List(x)))
      case _ => None
    }
  }

  // Split library calls into separate object
  // This avoids an OutOfMemory error in scalac.
  object MathLibraryCall {
    def unapply(v: Value) = {
      val t: Tree = v match {
        // [NN] Many of these translations aren't right!  I was
        // translating to functions in math.h, but these are often
        // different than the math functions supported by OpenCL.
        case MathCall("toRadians", List(DoubleTyped(x))) => Bin(Bin(x, "*", Id("M_PI")), "/", DoubleLit(180.))
        case MathCall("toDegrees", List(DoubleTyped(x))) => Bin(Bin(x, "*", DoubleLit(180.)), "/", Id("M_PI"))
        case MathCall("exp", List(FloatTyped(x))) => Call(Id("exp"), x)
        // case MathCall("exp", List(DoubleTyped(x))) => Call(Id("exp"), x)
        case MathCall("log", List(FloatTyped(x))) => Call(Id("log"), x)
        // case MathCall("log", List(DoubleTyped(x))) => Call(Id("log"), x)
        case MathCall("sqrt", List(FloatTyped(x))) => Call(Id("sqrt"), x)
        // case MathCall("sqrt", List(DoubleTyped(x))) => Call(Id("sqrt"), x)
        case MathCall("IEEEremainder", List(FloatTyped(x), FloatTyped(y))) => Call(Id("remainder"), y, x)
        // case MathCall("IEEEremainder", List(DoubleTyped(x), DoubleTyped(y))) => Call(Id("remainder"), y, x)
        case MathCall("ceil", List(FloatTyped(x))) => Call(Id("ceil"), x)
        // case MathCall("ceil", List(DoubleTyped(x))) => Call(Id("ceil"), x)
        case MathCall("floor", List(FloatTyped(x))) => Call(Id("floor"), x)
        // case MathCall("floor", List(DoubleTyped(x))) => Call(Id("floor"), x)
        case MathCall("rint", List(FloatTyped(x))) => Call(Id("rint"), x)
        // case MathCall("rint", List(DoubleTyped(x))) => Call(Id("rint"), x)
        case MathCall("atan2", List(FloatTyped(y), FloatTyped(x))) => Call(Id("atan2"), y, x)
        // case MathCall("atan2", List(DoubleTyped(y), DoubleTyped(x))) => Call(Id("atan2"), y, x)
        case MathCall("pow", List(FloatTyped(y), FloatTyped(x))) => Call(Id("pow"), y, x)
        // case MathCall("pow", List(DoubleTyped(y), DoubleTyped(x))) => Call(Id("pow"), y, x)
        case MathCall("round", List(FloatTyped(x))) => Cast(ValueType("int"), Call(Id("round"), x))
        // case MathCall("round", List(DoubleTyped(x))) => Call(Id("round"), x)

        case MathCall("abs", List(IntTyped(x))) => Call(Id("abs"), x)
        case MathCall("abs", List(FloatTyped(x))) => Call(Id("fabs"), x)
        case MathCall("abd", List(LongTyped(x))) => Call(Id("abs"), x)
        // case MathCall("abs", List(DoubleTyped(x))) => Call(Id("fabs"), x)

        case MathCall("max", List(IntTyped(y), IntTyped(x))) => Call(Id("max"), y, x)
        case MathCall("max", List(LongTyped(y), LongTyped(x))) => Call(Id("max"), y, x)
        case MathCall("max", List(FloatTyped(y), FloatTyped(x))) => Call(Id("fmax"), y, x)
        // case MathCall("max", List(DoubleTyped(y), DoubleTyped(x))) => Call(Id("fmax"), y, x)

        case MathCall("min", List(IntTyped(y), IntTyped(x))) => Call(Id("min"), y, x)
        case MathCall("min", List(LongTyped(y), LongTyped(x))) => Call(Id("min"), y, x)
        case MathCall("min", List(FloatTyped(y), FloatTyped(x))) => Call(Id("fmin"), y, x)
        //  case MathCall("min", List(DoubleTyped(y), DoubleTyped(x))) => Call(Id("fmin"), y, x)

        case MathCall("signum", List(x)) => Call(Id("SIGNUM"), x)

        case _ => null
      }
      if (t == null) None else Some(t)
    }
  }
        object FloatMath {
          def unapply(v: Value) = {
            val t: Tree = v match {
              case FloatMathCall("sin", List(x)) => Call(Id("sin"), x)
              case FloatMathCall("cos", List(x)) => Call(Id("cos"), x)
              case FloatMathCall("tan", List(x)) => Call(Id("tan"), x)
              case FloatMathCall("asin", List(x)) => Call(Id("asin"), x)
              case FloatMathCall("acos", List(x)) => Call(Id("acos"), x)
              case FloatMathCall("atan", List(x)) => Call(Id("atan"), x)
              case _ => null
            }
            if (t == null) None else Some(t)
          }
        }

        object DoubleMath {
          def unapply(v: Value) = {
            val t: Tree = v match {
              case DoubleMathCall("sin", List(x)) => Cast(ValueType("double"), Call(Id("sin"), x))
              case DoubleMathCall("cos", List(x)) => Cast(ValueType("double"), Call(Id("cos"), x))
              case DoubleMathCall("tan", List(x)) => Cast(ValueType("double"), Call(Id("tan"), x))
              case DoubleMathCall("asin", List(x)) => Cast(ValueType("double"), Call(Id("asin"), x))
              case DoubleMathCall("acos", List(x)) => Cast(ValueType("double"), Call(Id("acos"), x))
              case DoubleMathCall("atan", List(x)) => Cast(ValueType("double"), Call(Id("atan"), x))
              case _ => null
            }
            if (t == null) None else Some(t)
          }
        }

  // Split library calls into separate objects.
  // This avoids an OutOfMemory error in scalac.
  object LibraryCall {
    def unapply(v: Value) = {
      val t: Tree = v match {
        // These are just here as examples for matching Array.ofDim.  We need a realy strategy for translating newarray.
        case GVirtualInvoke(GStaticFieldRef(SFieldRef(_, "MODULE$", _, _)), SMethodRef(_, "ofDim", _, _, _),
                List(size, GVirtualInvoke(GStaticFieldRef(SFieldRef(_, "MODULE$", _, _)), SMethodRef(_, "Int", _, _, _), Nil))) =>
                  Call(Id("newIntArray"), List[Tree](size))

        case GVirtualInvoke(GStaticFieldRef(SFieldRef(_, "MODULE$", _, _)), SMethodRef(_, "ofDim", _, _, _),
                List(size, GVirtualInvoke(GStaticFieldRef(SFieldRef(_, "MODULE$", _, _)), SMethodRef(_, "Float", _, _, _), Nil))) =>
                  Call(Id("newFloatArray"), List[Tree](size))


        case DoubleMath(t) => t
        case FloatMath(t) => t
        // case MathLibraryCall(t) => t

        case _ => null
      }
      if (t == null) None else Some(t)
    }
  }

  object IntTyped {
    def unapply(v: AnyRef) = v match {
      case x : Value if SootIntType.v.equals(x.getType) => Some(x)
      case x : ValueBox if SootIntType.v.equals(x.getValue.getType) => Some(x.getValue)
      case _ => None
    }
  }

  object LongTyped {
    def unapply(v: AnyRef) = v match {
      case x : Value if SootLongType.v.equals(x.getType) => Some(x)
      case x : ValueBox if SootLongType.v.equals(x.getValue.getType) => Some(x.getValue)
      case _ => None
    }
  }

  object DoubleTyped {
    def unapply(v: AnyRef) = v match {
      case x : Value if SootDoubleType.v.equals(x.getType) => Some(x)
      case x : ValueBox if SootDoubleType.v.equals(x.getValue.getType) => Some(x.getValue)
      case _ => None
    }
  }

  object FloatTyped {
    def unapply(v: AnyRef) = v match {
      case x : Value if SootFloatType.v.equals(x.getType) => Some(x)
      case x : ValueBox if SootFloatType.v.equals(x.getValue.getType) => Some(x.getValue)
      case _ => None
    }
  }

  private def translateExp(v: Value): Tree = v match {
    // Must be first
    case LibraryCall(t) => t

    case GNullConstant() => Id("NULL")

    case GIntConstant(value) => IntLit(value)
    case GLongConstant(value) => LongLit(value)
    case GFloatConstant(value) => FloatLit(value)
    case GDoubleConstant(value) => DoubleLit(value)

    case GXor(op1, op2) => Bin(op1, "^", op2)
    case GOr(op1, op2) => Bin(op1, "|", op2)
    case GAnd(op1, op2) => Bin(op1, "&", op2)

    case GUshr(op1, op2) => Bin(Cast(ValueType("unsigned"), op1), ">>", op2)
    case GShr(op1, op2) => Bin(op1, ">>", op2)
    case GShl(op1, op2) => Bin(op1, "<<", op2)

    case GAdd(op1, op2) => Bin(op1, "+", op2)
    case GSub(op1, op2) => Bin(op1, "-", op2)
    case GMul(op1, op2) => Bin(op1, "*", op2)
    case GDiv(op1, op2) => Bin(op1, "/", op2)
    case GRem(op1, op2) => Bin(op1, "%", op2)

    case GEq(GCmpg(op1, op2), GIntConstant(0)) => Bin(op1, "==", op2)
    case GEq(GCmpl(op1, op2), GIntConstant(0)) => Bin(op1, "==", op2)
    case GEq(GCmp(op1, op2), GIntConstant(0)) => Bin(op1, "==", op2)
    case GEq(op1, op2) => Bin(op1, "==", op2)

    case GNe(GCmpg(op1, op2), GIntConstant(0)) => Bin(op1, "!=", op2)
    case GNe(GCmpl(op1, op2), GIntConstant(0)) => Bin(op1, "!=", op2)
    case GNe(GCmp(op1, op2), GIntConstant(0)) => Bin(op1, "!=", op2)
    case GNe(op1, op2) => Bin(op1, "!=", op2)

    case GGt(GCmpg(op1, op2), GIntConstant(0)) => Bin(op1, ">", op2)
    case GGt(GCmpl(op1, op2), GIntConstant(0)) => Bin(op1, ">", op2)
    case GGt(GCmp(op1, op2), GIntConstant(0)) => Bin(op1, ">", op2)
    case GGt(op1, op2) => Bin(op1, ">", op2)

    case GLt(GCmpg(op1, op2), GIntConstant(0)) => Bin(op1, "<", op2)
    case GLt(GCmpl(op1, op2), GIntConstant(0)) => Bin(op1, "<", op2)
    case GLt(GCmp(op1, op2), GIntConstant(0)) => Bin(op1, "<", op2)
    case GLt(op1, op2) => Bin(op1, "<", op2)

    case GLe(GCmpg(op1, op2), GIntConstant(0)) => Bin(op1, "<=", op2)
    case GLe(GCmpl(op1, op2), GIntConstant(0)) => Bin(op1, "<=", op2)
    case GLe(GCmp(op1, op2), GIntConstant(0)) => Bin(op1, "<=", op2)
    case GLe(op1, op2) => Bin(op1, "<=", op2)

    case GGe(GCmpg(op1, op2), GIntConstant(0)) => Bin(op1, ">=", op2)
    case GGe(GCmpl(op1, op2), GIntConstant(0)) => Bin(op1, ">=", op2)
    case GGe(GCmp(op1, op2), GIntConstant(0)) => Bin(op1, ">=", op2)
    case GGe(op1, op2) => Bin(op1, ">=", op2)

    case GCmpg(op1, op2) => Id("unimplemented:cmpg")
    case GCmpl(op1, op2) => Id("unimplemented:cmpl")
    case GCmp(op1, op2) => Id("unimplemented:cmp")

    case GNeg(op) => Un("-", op)

    // TODO - What do we do with this?  Does it need a representation in the C AST?
    case GArrayLength(op) => Id("unimplemented:arraylength")

    case GCast(op, castTyp) => Cast(translateType(castTyp), op)

    // IGNORE
    case GInstanceof(op, instTyp) => Id("unimplemented:instanceof")

    // IGNORE
    case GNew(newTyp) => Id("unimplemented:new")

    // IGNORE
    case GNewArray(newTyp, size) => Id("unimplemented:newarray")
    // IGNORE
    case GNewMultiArray(newTyp, sizes) => Id("unimplemented:newmultiarray")

    case GNewInvoke(baseTyp, method @ SMethodRef(_, "<init>", _, _, _), args) => {
      worklist += CompileMethodTask(method)

      def isFunctionClass(k: SootClass): Boolean = {
        if (k.getName.equals("scala.runtime.AbstractFunction1"))
          true
        else if (k.getName.equals("scala.runtime.AbstractFunction2"))
          true
        else if (k.getName.equals("scala.runtime.AbstractFunction3"))
          true
        else if (k.hasSuperclass && isFunctionClass(k.getSuperclass))
          true
        else
          false
      }

      def isFunction(t: Type): Boolean = t match {
        case t : SootRefType => t.hasSootClass && isFunctionClass(t.getSootClass)
        case _ => false
      }

      if (isFunction(baseTyp))
        Call(Id("makeClosure"), args.map(a => translateExp(a)))
      else
        Call(Id("_init_"), Call(Id("new_" + mangleName(baseTyp.toString)), args.map(a => translateExp(a))))
    }
    case GStaticInvoke(method, args) => {
      worklist += CompileMethodTask(method)
      Call(Id(method.name), args.map(a => translateExp(a)))
    }
    case GSpecialInvoke(base, method, args) => {
      worklist += CompileMethodTask(method, findSelf(base, symtab.self))
      Call(Select(base, method.name), args.map(a => translateExp(a)))
    }
    case GVirtualInvoke(base, method, args) => {
      worklist += CompileMethodTask(method, findSelf(base, symtab.self))
      // need to find all subclasses of method.getDeclaringClass that override method (i.e., have the same _.getSignature)
      // Then generate a call to a dispatch method:
      // e.g.,
      // class A { def m = ... }
      // class B extends A { def m = ... }
      // class C extends A { def m = ... }
      // val x: A = new C
      // x.m   // invokevirtual(x, A.m, Nil)
      // -->
      // dispatch_m(x)
      // where:
      // void dispatch_m(A* x) {
      //    switch (x.type) {
      //       case TYPE_A: A_m((A*) x);
      //       case TYPE_B: B_m((B*) x);
      //       case TYPE_C: C_m((C*) x);
      //    }
      // }
      //
      // Also need to define structs for each class with a type field as the first word.
      //
      // For now: just handle calls x.m() where we know either the static type
      // of x (e.g., A) is final, or m is final in A, or we know that no
      // subclasses of A override m
      
      // rewrite to:

      if( Modifier.isFinal(method.declaringClass.getModifiers) || Modifier.isFinal(method.resolve.getModifiers))
        Call(Id(methodName(method)), args.map(a => translateExp(a)))
      else
        Call(Id("unimplemented: call to " + methodName(method)), Seq())
      //Call(Select(base, method.name), args.map(a => translateExp(a)))
    }
    case GInterfaceInvoke(base, method, args) => {
      worklist += CompileMethodTask(method, findSelf(base, symtab.self))
      // need to find all subclasses of method.getDeclaringClass that override method (i.e., have the same _.getSignature)
      Call(Select(base, method.name), args.map(a => translateExp(a)))
    }

    case GLocal(name, typ) => { symtab.addLocalVar(typ, Id(mangleName(name))); Id(mangleName(name)) }
    case GThisRef(typ) => { symtab.addThisParam(typ, Id("_this")); Id("_this") }
    case GParameterRef(typ, index) => { symtab.addParamVar(typ, index, Id("_arg" + index)); Id("_arg" + index) }
    case GStaticFieldRef(fieldRef) => Id("unimplemented:staticfield")

    case GInstanceFieldRef(base, fieldRef) => Select(base, fieldRef.name)
    case GArrayRef(base, index) => ArrayAccess(base, index)

    case v => Id("unsupported:" + v.getClass.getName)
  }

  private def translateUnits(units: List[SootUnit], result: List[Tree]): List[Tree] = units match {
    case u::us => {
      val tree: Tree = u match {
        case GIdentity(left, right) => Eval(Assign(left, right))
        case GAssignStmt(left: Local, GNewArray(typ: SootArrayType, size)) => { symtab.locals -= Id(left.getName); symtab.addArrayDef(typ.getElementType, Id(left.getName), translateExp(size)); Seq() }
        case GAssignStmt(left, right) => Eval(Assign(left, right))
        case GGoto(target) => GoTo(translateLabel(target))
        case GNop() => Nop
        case GReturnVoid() => Return
        case GReturn(op) => Return(translateExp(op))
        case GIf(cond, target) => If(translateExp(cond), GoTo(translateLabel(target)), Nop)
        case GInvokeStmt(invokeExpr) => Eval(translateExp(invokeExpr))

        // TODO
        case GTableSwitchStmt(key, lowIndex, highIndex, targets, defaultTarget) => Id("switch unsupported")
        case GLookupSwitchStmt(key: Local, lookupVals: List[Value], targets: List[Stmt], defaultTarget) => {
          val valsWithTargets: List[(Value, Stmt)] = lookupVals.zip(targets)
          Switch(translateExp(key), valsWithTargets.map(vt => Case(translateExp(vt._1), GoTo(translateLabel(vt._2)))) :::         List(Default(GoTo(translateLabel(defaultTarget)))))
          // Switch(Id(key.getName), valsWithTargets.map(vt => Case(translateExp(vt._1), Seq(translateUnits(List(vt._2), Nil)))) ::: List(Default(Seq(translateUnits(List(defaultTarget), Nil)))))
          //Id("switch unsupported")
        }
        // IGNORE
        case GThrow(op) => Id("throw unsupported")
        case GExitMonitor(op) => Id("monitors unsupported")
        case GEnterMonitor(op) => Id("monitors unsupported")

        case _ => { println("huh " + u); Id("unsupported") }
      }

      translateUnits(us, result ::: List[Tree](tree))
    }
    case Nil => result
  }

  private def insertLabels(units: List[SootUnit], result: List[Tree], resultWithLabels: List[Tree]) : List[Tree] = units match {
    case u::us => {
      symtab.labels.get(u) match {
        case Some(label) => insertLabels(us, result.tail, resultWithLabels ::: Label(label) :: result.head :: Nil)
        case None        => insertLabels(us, result.tail, resultWithLabels ::: result.head :: Nil)
      }
    }
   case Nil => resultWithLabels
  }

  private def makeFunction(m: SootMethod, result: List[Tree]) : Tree = {
    val paramTree = new ListBuffer[Tree]()
    val varTree = new ListBuffer[Tree]()

    /*
    if (! m.isStatic) {
      assert(symtab.thisParam != null)
      val (id,typ) = symtab.thisParam
      paramTree += Formal(translateType(typ), id)
    }
    */

    for (i <- 0 until m.getParameterCount) {
      symtab.params.get(i) match {
        case Some((id, typ)) => paramTree += Formal(translateType(typ), id)
        case None => throw new RuntimeException("crap")
      }
    }

    for((id: Id, typ: Type) <- symtab.locals) 
      varTree += VarDef(translateType(typ), id)

    for(((id: Id), (typ: Type, size: IntLit)) <- symtab.arrays)
      varTree += ArrayDef(id, translateType(typ), size)

    FunDef(translateType(m.getReturnType), Id(methodName(m)), paramTree.toList, (varTree.toList ::: result).toArray:_*)
  }
}