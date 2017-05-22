// See LICENSE for license details.

package firrtlTests

import firrtl._
import firrtl.annotations._
import firrtl.transforms.core.passes.RemoveEmpty
import firrtl.transforms.mem.SimpleTransform
import firrtl.transforms.opt.OptimizableExtModuleAnnotation

class DCETests extends FirrtlFlatSpec {
  // Not using executeTest because it is for positive testing, we need to check that stuff got
  // deleted
  private val customTransforms = Seq(
    new LowFirrtlOptimization,
    new SimpleTransform(RemoveEmpty, LowForm)
  )
  private def exec(input: String, check: String, annos: Seq[Annotation] = List.empty): Unit = {
    val state = CircuitState(parse(input), ChirrtlForm, Some(AnnotationMap(annos)))
    val finalState = (new LowFirrtlCompiler).compileAndEmit(state, customTransforms)
    val res = finalState.getEmittedCircuit.value
    // Convert to sets for comparison
    val resSet = Set(parse(res).serialize.split("\n"):_*)
    val checkSet = Set(parse(check).serialize.split("\n"):_*)
    resSet should be (checkSet)
  }

  "Unread wire" should "be deleted" in {
    val input =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    wire a : UInt<1>
        |    z <= x
        |    a <= x""".stripMargin
    val check =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    z <= x""".stripMargin
    exec(input, check)
  }
  "Unread wire marked dont touch" should "NOT be deleted" in {
    val input =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    wire a : UInt<1>
        |    z <= x
        |    a <= x""".stripMargin
    val check =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    wire a : UInt<1>
        |    z <= x
        |    a <= x""".stripMargin
    exec(input, check, Seq(dontTouch("Top.a")))
  }
  "Unread register" should "be deleted" in {
    val input =
      """circuit Top :
        |  module Top :
        |    input clk : Clock
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    reg a : UInt<1>, clk
        |    a <= x
        |    node y = asUInt(clk)
        |    z <= or(x, y)""".stripMargin
    val check =
      """circuit Top :
        |  module Top :
        |    input clk : Clock
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    node y = asUInt(clk)
        |    z <= or(x, y)""".stripMargin
    exec(input, check)
  }
  "Unread node" should "be deleted" in {
    val input =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    node a = not(x)
        |    z <= x""".stripMargin
    val check =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    z <= x""".stripMargin
    exec(input, check)
  }
  "Unused ports" should "be deleted" in {
    val input =
      """circuit Top :
        |  module Sub :
        |    input x : UInt<1>
        |    input y : UInt<1>
        |    output z : UInt<1>
        |    z <= x
        |  module Top :
        |    input x : UInt<1>
        |    input y : UInt<1>
        |    output z : UInt<1>
        |    inst sub of Sub
        |    sub.x <= x
        |    z <= sub.z""".stripMargin
    val check =
      """circuit Top :
        |  module Sub :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    z <= x
        |  module Top :
        |    input x : UInt<1>
        |    input y : UInt<1>
        |    output z : UInt<1>
        |    inst sub of Sub
        |    sub.x <= x
        |    z <= sub.z""".stripMargin
    exec(input, check)
  }
  "Chain of unread nodes" should "be deleted" in {
    val input =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    node a = not(x)
        |    node b = or(a, a)
        |    node c = add(b, x)
        |    z <= x""".stripMargin
    val check =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    z <= x""".stripMargin
    exec(input, check)
  }
  "Chain of unread wires and their connections" should "be deleted" in {
    val input =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    wire a : UInt<1>
        |    a <= x
        |    wire b : UInt<1>
        |    b <= a
        |    z <= x""".stripMargin
    val check =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    z <= x""".stripMargin
    exec(input, check)
  }
  "Read register" should "not be deleted" in {
    val input =
      """circuit Top :
        |  module Top :
        |    input clk : Clock
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    reg r : UInt<1>, clk
        |    r <= x
        |    z <= r""".stripMargin
    val check =
      """circuit Top :
        |  module Top :
        |    input clk : Clock
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    reg r : UInt<1>, clk with : (reset => (UInt<1>("h0"), r))
        |    r <= x
        |    z <= r""".stripMargin
    exec(input, check)
  }
  "Logic that feeds into simulation constructs" should "not be deleted" in {
    val input =
      """circuit Top :
        |  module Top :
        |    input clk : Clock
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    node a = not(x)
        |    stop(clk, a, 0)
        |    z <= x""".stripMargin
    val check =
      """circuit Top :
        |  module Top :
        |    input clk : Clock
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    node a = not(x)
        |    z <= x
        |    stop(clk, a, 0)""".stripMargin
    exec(input, check)
  }
  "Globally dead module" should "should be deleted" in {
    val input =
      """circuit Top :
        |  module Dead :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    z <= x
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    inst dead of Dead
        |    dead.x <= x
        |    z <= x""".stripMargin
    val check =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    z <= x""".stripMargin
    exec(input, check)
  }
  "Globally dead extmodule" should "NOT be deleted by default" in {
    val input =
      """circuit Top :
        |  extmodule Dead :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    inst dead of Dead
        |    dead.x <= x
        |    z <= x""".stripMargin
    val check =
      """circuit Top :
        |  extmodule Dead :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    inst dead of Dead
        |    dead.x <= x
        |    z <= x""".stripMargin
    exec(input, check)
  }
  "Extmodule with only inputs" should "NOT be deleted by default" in {
    val input =
      """circuit Top :
        |  extmodule InputsOnly :
        |    input x : UInt<1>
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    inst ext of InputsOnly
        |    ext.x <= x
        |    z <= x""".stripMargin
    val check =
      """circuit Top :
        |  extmodule InputsOnly :
        |    input x : UInt<1>
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    inst ext of InputsOnly
        |    ext.x <= x
        |    z <= x""".stripMargin
    exec(input, check)
  }
  "Globally dead extmodule marked optimizable" should "be deleted" in {
    val input =
      """circuit Top :
        |  extmodule Dead :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    inst dead of Dead
        |    dead.x <= x
        |    z <= x""".stripMargin
    val check =
      """circuit Top :
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    z <= x""".stripMargin
    val doTouchAnno = OptimizableExtModuleAnnotation(ModuleName("Dead", CircuitName("Top")))
    exec(input, check, Seq(doTouchAnno))
  }
  "Analog ports of extmodules" should "count as both inputs and outputs" in {
    val input =
      """circuit Top :
        |  extmodule BB1 :
        |    output bus : Analog<1>
        |  extmodule BB2 :
        |    output bus : Analog<1>
        |    output out : UInt<1>
        |  module Top :
        |    output out : UInt<1>
        |    inst bb1 of BB1
        |    inst bb2 of BB2
        |    attach (bb1.bus, bb2.bus)
        |    out <= bb2.out
        """.stripMargin
    exec(input, input)
  }
  // bar.z is not used and thus is dead code, but foo.z is used so this code isn't eliminated
  "Module deduplication" should "should be preserved despite unused output of ONE instance" in {
    val input =
      """circuit Top :
        |  module Child :
        |    input x : UInt<1>
        |    output y : UInt<1>
        |    output z : UInt<1>
        |    y <= not(x)
        |    z <= x
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    inst foo of Child
        |    inst bar of Child
        |    foo.x <= x
        |    bar.x <= x
        |    node t0 = or(foo.y, foo.z)
        |    z <= or(t0, bar.y)""".stripMargin
    val check =
      """circuit Top :
        |  module Child :
        |    input x : UInt<1>
        |    output y : UInt<1>
        |    output z : UInt<1>
        |    y <= not(x)
        |    z <= x
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    inst foo of Child
        |    inst bar of Child
        |    foo.x <= x
        |    bar.x <= x
        |    node t0 = or(foo.y, foo.z)
        |    z <= or(t0, bar.y)""".stripMargin
    exec(input, check)
  }
  // This currently does NOT work
  behavior of "Single dead instances"
  ignore should "should be deleted" in {
    val input =
      """circuit Top :
        |  module Child :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    z <= x
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    inst foo of Child
        |    inst bar of Child
        |    foo.x <= x
        |    bar.x <= x
        |    z <= foo.z""".stripMargin
    val check =
      """circuit Top :
        |  module Child :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    z <= x
        |  module Top :
        |    input x : UInt<1>
        |    output z : UInt<1>
        |    inst foo of Child
        |    skip
        |    foo.x <= x
        |    skip
        |    z <= foo.z""".stripMargin
    exec(input, check)
  }
}