package Chisel

import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashMap
import scala.collection.mutable.HashSet
import scala.collection.mutable.Stack
import scala.math.pow
import scala.io.Source

trait CounterBackend extends Backend {
  val crosses = new ArrayBuffer[(Double, Array[Node])]

  val fires = new HashMap[Module, Bool]
  val daisyIns = new HashMap[Module, UInt]
  val daisyOuts = new HashMap[Module, DecoupledIO[UInt]]
  val daisyCtrls = new HashMap[Module, Bits]
  val counterCopy = new HashMap[Module, Bool]
  val counterRead = new HashMap[Module, Bool]
  val decoupledPins = new HashMap[Node, Bits]

  var counterIdx = -1

  override def backannotationTransforms {
    super.backannotationTransforms

    transforms += ((c: Module) => c bfs (_.addConsumers))

    if (Module.isBackannotating) {
      transforms += ((c: Module) => annotateSignals(c))
    }

    transforms += ((c: Module) => decoupleTarget(c))
    transforms += ((c: Module) => connectDaisyPins(c))
    transforms += ((c: Module) => generateCounters)
    transforms += ((c: Module) => generateDaisyChains)

    transforms += ((c: Module) => c.inferAll)
    transforms += ((c: Module) => c.forceMatchingWidths)
    transforms += ((c: Module) => c.removeTypeNodes)
    transforms += ((c: Module) => collectNodesIntoComp(initializeDFS))
  }

  /*
  override def getPseudoPath(c: Module, delim: String = "/"): String = {
    if (!(c.parent == null)) {
      c.parent match {
        case _: CounterWrapper => extractClassName(c)
        case _ => getPseudoPath(c.parent, delim) + delim + c.pName
      }
    } else ""
  }

  override def setPseudoNames(c: Module) {
    c match {
      case m: CounterWrapper => super.setPseudoNames(m.top)
    }
  }

  override def checkBackannotation(c: Module) {
    c match {
      case m: CounterWrapper => super.checkBackannotation(m.top)
    }
  }
  */

  def emitCounterIdx = {
    counterIdx = counterIdx + 1
    counterIdx
  }

  def addPin(m: Module, pin: Data, name: String) {
    pin.component = m
    pin.isIo = true
    pin setName name
    (m.io) match {
      case io: Bundle => io += pin
    }
    pin setName ("io_" + name)

    pin match {
      case dio: DecoupledIO[_] => {
        dio.ready.component = m
        dio.valid.component = m
        dio.bits.component = m
        dio.ready.isIo = true
        dio.valid.isIo = true
        dio.bits.isIo = true
        dio.ready setName ("io_" + name + "_ready")
        dio.valid setName ("io_" + name + "_valid")
        dio.bits setName ("io_" + name + "_bits")
      }
      case vio: ValidIO[_] => {
        vio.valid.component = m
        vio.bits.component = m
        vio.valid.isIo = true
        vio.bits.isIo = true
        vio.valid setName ("io_" + name + "_valid")
        vio.bits setName ("io_" + name + "_bits")
      }
      case _ =>
    } 
  }

  def wirePin(pin: Data, input: Node) {
    if (pin.inputs.isEmpty) pin.inputs += input
    else pin.inputs(0) = input
  }

  def addReg(m: Module, comp: proc, name: String = "", updates: Map[Bool, Node] = Map()) {
    val reg = comp match {
      case r: Reg => r
    }
    reg.component = m
    reg.clock = m.clock
    if (name != "") reg setName name
    reg.isEnable = !updates.isEmpty
    for ((cond, value) <- updates) {
      reg.enable = reg.enable || cond
    }
    reg.updates ++= updates
    reg genMuxes reg
    if (reg.isReset) 
      reg.inputs += m.reset
  }

  def connectConsumers(input: Node, via: Node) {
    for (consumer <- input.consumers) {
      val idx = consumer.inputs indexOf input
      consumer.inputs(idx) = via
    }
  }

  private def annotateSignals(c: Module) {
    ChiselError.info("[Backannotation] annotate signals")

    try {
      // Read the signal list file
      val lines = Source.fromFile(Module.model).getLines
      val TermRegex = """\s*([\w\._\:]+)\s+([\d\.\+-e]+)\s+([\d\.\+-e]+)\s+([\d\.\+-e]+)\s+([\d\.\+-e]+)""".r
      val signalNames = new HashSet[String]
      val signalNameMap = new HashMap[String, Node]
      val coeffs = new HashSet[(Double, Array[String])]

      for (line <- lines) {
        line match {
          case TermRegex(exp, coeff, se, tstat, pvalue) => {
            val vars = exp split ":"
            if (tstat != "NaN" && pvalue != "NaN") {
              signalNames ++= vars
              coeffs += ((coeff.toDouble, vars))
            }
          }
          case _ =>
        }
      }

      // Find correspoinding nodes
      for (m <- Module.sortedComps ; if m != c) {
        for (node <- m.nodes) {
          val signalName = getSignalPathName(node, ".")
          if (signalNames contains signalName){
            m.counter(node)
            m.debug(node)
            signalNameMap(signalName) = node
          }
        }
        for ((reset, pin) <- m.resets) {
          val resetPinName = getSignalPathName(pin, ".")
          if (signalNames contains resetPinName) {
            m.counter(pin)
            m.debug(pin)
            signalNameMap(resetPinName) = pin
          }
        }
      }
   
      for ((coeff, vars) <- coeffs) {
        val cross = vars map { x => signalNameMap getOrElse (x, null) }
        if (!(cross contains null)) {
          crosses += ((coeff, cross))
        }
      } 
    } catch {
      case ex: java.io.FileNotFoundException => 
        ChiselError.warning("[Backannotation] no signal file, no backannotation")
    }
  }

  def decoupleTarget(c: Module) {
    ChiselError.info("[CounterBackend] target decoupling")

    val clksPin = Decoupled(UInt(width = 32)).flip
    val clksReg = Reg(init = UInt(0, 32))
    val fired = clksReg.orR
    val notFired = !fired
    val fire = Bool(OUTPUT)

    addReg(c, clksReg.comp, "clks", Map(
      (clksPin.valid && notFired) -> clksPin.bits,
      fired                     -> (clksReg - UInt(1))
    )) 

    // wires -> insert buffers
    for ((n, pin) <- c.io.flatten) {
      pin match {
        case in: Bits if in.dir == INPUT => {
          val in_reg = Reg(UInt())
          addReg(c, in_reg.comp, in.pName + "_buf",
            Map(notFired -> in)
          )
          connectConsumers(in, in_reg)
          decoupledPins(in) = in_reg
        }
        case out: Bits if out.dir == OUTPUT => {
          val out_reg = Reg(UInt())
          addReg(c, out_reg.comp, out.pName + "_buf", 
            Map(fired -> out.inputs.head)
          )
          wirePin(out, out_reg)
          decoupledPins(out) = out_reg
        }
      }
    }

    addPin(c, clksPin, "clks")
    addPin(c, fire, "fire")
    wirePin(fire, fired)
    wirePin(clksPin.ready, notFired)
    fires(c) = fired

    val stack = new Stack[Module]
    stack push c

    while (!stack.isEmpty) {
      val top = stack.pop

      for (node <- top.nodes) {
        node match {
          case reg: Reg if this.isInstanceOf[VerilogBackend] && 
                           !Module.isBackannotating => {
            val enable = fires(top) && reg.enable
            reg.inputs(reg.enableIndex) = enable.getNode
          }
          case reg: Reg => {
            reg.inputs(0) = Multiplex(fires(top), reg.next, reg)
          }
          case mem: Mem[_] => {
            for (write <- mem.writeAccesses) {
              val en = UInt(write.inputs(1)).toBool
              val newEn = fires(top) && en
              if (Module.isBackannotating)
                newEn.getNode setName (en.getNode.pName + "_fire")
              write.inputs(1) = newEn
            }
          }
          case _ =>
        }
      }

      for (child <- top.children) {
        val fire = Bool(INPUT)
        addPin(child, fire, "fire")
        wirePin(fire, fires(top))
        fires(child) = fire
        stack push child
      }
    }
  }

  // Connect daisy pins of hierarchical modules
  def connectDaisyPins(c: Module) {
    ChiselError.info("[CounterBackend] connect daisy pins")

    val daisyOut = Decoupled(UInt(width = 32))
    val daisyCtrl = UInt(INPUT, 1)
    val daisyFire = daisyOut.ready && !fires(c)
    val copy = daisyFire && daisyCtrl === Bits(0)
    val read = daisyFire && daisyCtrl === Bits(1)
    copy.getNode setName "copy"
    read.getNode setName "read"

    addPin(c, daisyOut,  "daisy_out")
    addPin(c, daisyCtrl, "daisy_ctrl")
    wirePin(daisyOut.valid, daisyFire)

    daisyIns(c)    = UInt(0)
    daisyOuts(c)   = daisyOut
    daisyCtrls(c)  = daisyCtrl
    counterCopy(c) = copy
    counterRead(c) = read

    val stack = new Stack[Module]
    stack push c

    while (!stack.isEmpty) {
      val m = stack.pop

      for (child <- m.children) {
        val daisyIn = UInt(INPUT, 32)
        val daisyOut = Decoupled(UInt(width = 32))
        val daisyCtrl = UInt(INPUT, 1)
        val daisyFire = daisyOut.ready && !fires(child)
        val copy = daisyFire && daisyCtrl === Bits(0)
        val read = daisyFire && daisyCtrl === Bits(1)

        addPin(child, daisyIn,   "daisy_in")
        addPin(child, daisyOut,  "daisy_out")
        addPin(child, daisyCtrl, "daisy_ctrl")
        wirePin(daisyOut.valid, daisyFire)
        wirePin(daisyOut.ready, daisyOuts(m).ready)
        wirePin(daisyCtrl,      daisyCtrls(m))
        daisyIns(child)       = daisyIn
        daisyOuts(child)      = daisyOut
        daisyCtrls(child)     = daisyCtrl

        stack push child
      }

      if (!m.children.isEmpty && m != c) {
        val head = m.children.head
        wirePin(daisyIns(head), daisyIns(m))
      }

      for (i <- 0 until m.children.size - 1) {
        val cur = m.children(i)
        val next = m.children(i+1)
        wirePin(daisyOuts(next).bits, daisyIns(cur))
      }

      if (!m.children.isEmpty) {
        val last = m.children.last
        wirePin(daisyOuts(m).bits, daisyOuts(last).bits)
      }
    }
  }

  def generateCounters {
    ChiselError.info("[CounterBackend] generate counters")

    val stack = new Stack[Module]

    for (m <- Module.components) {
      val counterEnable = fires(m) || counterCopy(m)
      val shadowEnable = counterCopy(m) || counterRead(m)

      for (signal <- m.signals) {
        val signalWidth = signal.width
        val signalValue = 
          if (decoupledPins contains signal) decoupledPins(signal) 
          else UInt(signal)
        // Todo: configurable counter width
        val counter = Reg(Bits(width = 32))
        val shadow  = Reg(Bits(width = 32))
        signal.counter = counter
        signal.shadow = shadow
        signal.cntrIdx = emitCounterIdx

        val counterValue = {
          // Signal
          if (signalWidth == 1) {
            counter + signalValue
          // Bus
          } else {
            val buffer = Reg(UInt(width = signalWidth))
            val xor = signalValue ^ buffer
            xor.inferWidth = (x: Node) => signalWidth
            val hd = PopCount(xor)
            addReg(m, buffer.comp, "buffer_%d".format(signal.cntrIdx), 
              Map(fires(m) -> signalValue)
            )
            counter + hd
          }
        }
        counterValue.getNode.component = m
        counterValue.getNode setName "c_value_%d".format(signal.cntrIdx)

        addReg(m, counter.comp, "counter_%d".format(signal.cntrIdx), Map(
          fires(m)       -> counterValue,
          counterCopy(m) -> Bits(0)
        ))

        // for debugging
        if (Module.isBackannotating) {
          signal setName "signal_%d_%s".format(counterIdx, signal.pName)
        }
      }
    }
  }  
 
  def generateDaisyChains {
    ChiselError.info("[CounterBackend] daisy chaining")
 
    // Daisy chaining
    for (m <- Module.sortedComps) {
      // Copy logic
      if (!m.signals.isEmpty) {
        val head = m.signals.head
        val last = m.signals.last
        wirePin(daisyOuts(m).bits, head.shadow)
        addReg(m, last.shadow.comp, "shadow_%d".format(last.cntrIdx), Map(
          counterCopy(m) -> last.counter,
          counterRead(m) -> daisyIns(m)
        ))
      } else if (m.children.isEmpty) {
        wirePin(daisyOuts(m).bits, daisyIns(m))
      }

      for (i <- 0 until m.signals.size - 1) {
        val cur = m.signals(i)
        val next = m.signals(i+1)
        addReg(m, cur.shadow.comp, "shadow_%d".format(cur.cntrIdx), Map(
          counterCopy(m) -> cur.counter,
          counterRead(m) -> next.shadow
        ))
      
        // Signals are collected in order
        // so that counter values are verified 
        // and power numbers are calculated
        Module.signals += cur
      }
      if (!m.signals.isEmpty) {
        Module.signals += m.signals.last
      }
    }
  }
}

class CounterVBackend extends VerilogBackend with CounterBackend
class CounterCppBackend extends CppBackend with CounterBackend

abstract class CounterTester[+T <: Module](c: T) extends Tester(c) {
  val prevPeeks = new HashMap[Node, BigInt]
  val counts = new HashMap[Node, BigInt]

  def calcHD(a: BigInt, b: BigInt) = {
    var xor = a ^ b
    var hd: BigInt = 0
    while (xor > 0) {
      hd = hd + (xor & 1)
      xor = xor >> 1
    }
    hd
  }

  val clks = c.io("clks") match {
    case dio: DecoupledIO[_] => dio
  }
  val fire = c.io("fire") match {
    case bool: Bool => bool
  }
  val daisyCtrl = c.io("daisy_ctrl") match {
    case bits: Bits => bits
  }
  val daisyOut = c.io("daisy_out") match {
    case dio: DecoupledIO[_] => dio
  }
  val daisyOutBits = daisyOut.bits match {
    case bits: Bits => bits
  }

  def clock (n: Int) {
    val clk = emulatorCmd("step %d".format(n))
    println("  CLOCK %d".format(n))
  }

  override def reset(n: Int = 1) {
    super.reset(n)
    if (t >= 1) {
      for (signal <- Module.signals ; if signal.width > 1) {
        prevPeeks(signal) = 0
      }
    }
  }

  override def step (n: Int = 1) { 
    println("-------------------------")
    println("| Counter Strcture Step |")
    println("-------------------------")

    for (signal <- Module.signals) {
      counts(signal) = 0
    }

    // set clock register
    while(peek(clks.ready) == 0) {
      clock(1)
    }
  
    pokeBits(clks.bits, n)
    pokeBits(clks.valid, 1)
    clock(1)
    pokeBits(clks.valid, 0)

    // run the target until it is stalled
    println("*** RUN THE TAREGT / READ SIGNAL VALUES ***")
    for (i <- 0 until n) {
      for (signal <- Module.signals) {
        val curPeek = peekBits(signal)
        if (signal.width == 1) {
          counts(signal) += curPeek
        } else {
          counts(signal) += calcHD(curPeek, prevPeeks(signal))
          prevPeeks(signal) = curPeek
        }
      }
      clock(1)
    }
  
    println("*** CHECK COUNTER VALUES ***")
    for (signal <- Module.signals) {
      expect(signal.counter, counts(signal))
    }

    // daisy copy
    do {
      println("*** Daisy Copy ***")
      poke(daisyCtrl, 0)
      poke(daisyOut.ready, 1)
      clock(1)
    } while (peek(fire) == 1)

    println("--- CURRENT CHAIN ---")
    for (s <- Module.signals) {
      peek(s.shadow)
    }

    // daisy read
    for ((signal, i) <- Module.signals.zipWithIndex) {
      println("*** Daisy Output ***")
      do {
        poke(daisyCtrl, 1)
        poke(daisyOut.ready, 1)
        clock(1)
      } while (peek(fire) == 1)

      println("--- CURRENT CHAIN ---")
      for (s <- Module.signals) {
        peek(s.shadow)
      }
      println("---------------------")

      expect(daisyOutBits, counts(signal))
    }

    t += n
  }

  // initialization
  for (signal <- Module.signals ; if signal.width > 1) {
    prevPeeks(signal) = 0
  }
}

/*
abstract class CounterTester[+T <: CounterWrapper](c: T, val clks: Int = 1) extends Tester(c) {
  val prevPeeks = new HashMap[Node, BigInt]
  val counts = new HashMap[Node, BigInt]

  def calcHD(a: BigInt, b: BigInt) = {
    var xor = a ^ b
    var hd: BigInt = 0
    while (xor > 0) {
      hd = hd + (xor & 1)
      xor = xor >> 1
    }
    hd
  }

  def pokeClear {
    poke(c.io.addr, 0)
    poke(c.io.in.valid, 0)
    poke(c.io.in.bits, 0)
    poke(c.io.out.ready, 0)
    step(1)
  }

  def pokeReset {
    println("------------------------")
    println("|  Reset: write(31, 0) |")
    println("------------------------")
    // write (31, 0)
    while (peek(c.io.in.ready) == 0) {
      step(1)
    }
    poke(c.io.addr, 31)
    poke(c.io.in.valid, 1)
    poke(c.io.in.bits, 0)
    poke(c.io.out.ready, 0)
    step(1)
  }

  def pokeClks {
    println("------------------------------")
    println("| Poke clks: write(4, %4d) |".format(clks))
    println("------------------------------")
    // write (4, clks)
    while (peek(c.io.in.ready) == 0) {
      step(1)
    }
    poke(c.io.addr, 4)
    poke(c.io.in.bits, clks)
    poke(c.io.in.valid, 1)
    poke(c.io.out.ready, 0)
    for (signal <- Module.signals ; if signal.width > 1) {
      prevPeeks(signal) = peekBits(signal)
    }
    step(1)
  }

  def peekDaisyCopy = {
    println("--------------------------------")
    println("| Daisy copy: read(4 | 0 << 4) |")
    println("--------------------------------")
    // read (4 | 0 << 4)
    do {
      poke(c.io.addr, 4 | 0 << 4)
      poke(c.io.in.bits, 0)
      poke(c.io.in.valid, 0)
      poke(c.io.out.ready, 1)
      step(1)
    } while (peek(c.io.out.valid) == 0)

    peek(c.io.out.bits)
  }

  def peekDaisyRead = {
    println("--------------------------------")
    println("| Daisy read: read(4 | 1 << 4) |")
    println("--------------------------------")
    // read (4 | 1 << 4)
    do {
      poke(c.io.addr, 4 | 1 << 4)
      poke(c.io.in.bits, 0)
      poke(c.io.in.valid, 0)
      poke(c.io.out.ready, 1)
      step(1)
    } while (peek(c.io.out.valid) == 0)

    peek(c.io.out.bits)
  }

  def peekSignals {
    println("----------------------")
    println("|    Read signals    |")
    println("----------------------")
    step(1)
    for ((signal, i) <- Module.signals.zipWithIndex) {
      val curPeek = peekBits(signal)
      val cntrPeek = peek(signal.counter)
      if (signal.width == 1) {
        counts(signal) += curPeek
      } else {
        counts(signal) += calcHD(curPeek, prevPeeks(signal))
        prevPeeks(signal) = curPeek
      }
      println("  counts of counter_%d = %x".format(i, counts(signal)))
    }
  }

  var good = true
  var cycles = 0

  def loop {
    for (signal <- Module.signals) {
      counts(signal) = 0
    }

    pokeClks
    pokeClear

    while (peek(c.fire) == 1) {
      cycles += 1
      peekSignals
    }
    pokeClear
    pokeClear

    var ready: BigInt = 0
    var bits: BigInt = 0

    val read = peekDaisyCopy
    pokeClear
    pokeClear
    for (signal <- Module.signals) {
      peek(signal.shadow)
    }
    for ((signal, i) <- Module.signals.zipWithIndex) {
      val read = peekDaisyRead
      for (s <- Module.signals) {
        peek(s.shadow)
      }
      good &= expect(read == counts(signal), "Counter" + i)
      println("out bits: %x\tfrom signal: %x".format(
               read, counts(signal)))
    }
  }

  // initialization
  pokeReset
  for (signal <- Module.signals) {
    counts(signal) = 0
    prevPeeks(signal) = 0
  }
}
*/

case class CounterConfiguration(
  addrWidth: Int = 5,
  dataWidth: Int = 32,
  daisyCtrlWidth: Int = 1,
  counterWidth: Int = 32) 
{
  val n = pow(2, addrWidth - daisyCtrlWidth).toInt
}

class CounterWrapperIO(conf: CounterConfiguration) extends Bundle {
  val in = Decoupled(Bits(width = conf.dataWidth)).flip
  val out = Decoupled(Bits(width = conf.dataWidth))
  val addr = Bits(INPUT, conf.addrWidth)
}

abstract class CounterWrapper(val conf: CounterConfiguration) extends Module {
  val io = new CounterWrapperIO(conf)
  def top: Module

  def wen(i: Int) = io.in.valid && io.addr(log2Up(conf.n)-1, 0) === UInt(i)
  def ren(i: Int) = io.out.ready && io.addr(log2Up(conf.n)-1, 0) === UInt(i)
  val rdata = Vec.fill(conf.n){Bits(width = conf.dataWidth)}
  val rvalid = Vec.fill(conf.n){Bool()}
  val wready = Vec.fill(conf.n){Bool()}

  val clks = Reg(init = UInt(0))
  val fire = clks != UInt(0)

  clks.comp setName "clks"
  fire.getNode setName "fire"

  // debug(fire)
 
  io.in.ready := wready(io.addr)
  io.out.valid := rvalid(io.addr)
  io.out.bits := rdata(io.addr)

  // write(aar = 4) -> clks
  when(wen(4) && !fire) {
    clks := io.in.bits
  }.elsewhen(fire) {
    clks := clks - UInt(1)
  }
  wready(4) := !fire
}
