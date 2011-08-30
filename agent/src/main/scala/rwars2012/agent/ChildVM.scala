package rwars2012.agent

import java.io.{File, StringWriter}
import java.nio.{ByteBuffer, CharBuffer}
import java.nio.channels.{Channels, ReadableByteChannel, SelectableChannel, Selector}
import java.nio.charset.Charset
import java.util.concurrent.{BrokenBarrierException, CyclicBarrier, Semaphore}
import com.sun.jdi.{Bootstrap, ThreadReference, VirtualMachine}
import com.sun.jdi.connect.Connector.{BooleanArgument, StringArgument}
import com.sun.jdi.request.{StepRequest, ThreadDeathRequest, ThreadStartRequest}
import net.lag.configgy.Configgy
import net.lag.logging.Logger
import scala.collection.JavaConverters.{mapAsScalaMapConverter, asScalaSetConverter}

object ProcessStream extends Enumeration {
    val STDIN = Value("stdin")
    val STDOUT = Value("stdout")
    val STDERR = Value("stderr")
}

class ChildVMSupervisor {
    val log = Logger(getClass)

    private var barrier: CyclicBarrier = null
    private var childVMs: Map[Int, ChildVM] = Map.empty
    private var nextId: Int = 0
    protected[agent] val selector: Selector = Selector.open()

    protected[agent] def converge(): Unit = {
        val b = synchronized(barrier)
        try {
            b.await()
        } catch {
            case (_: BrokenBarrierException) => converge()
        }
    }

    private val thread = new Thread(toString + " Thread") {
        override def run(): Unit = {
            selector.select(0)
            val iter = selector.selectedKeys.iterator
            while (iter.hasNext) {
                val key = iter.next()
                val (id, which) = key.attachment.asInstanceOf[(Int, ProcessStream.Value)]

                childVMs.get(id) match {
                    case Some(childVM) if key.isReadable => childVM.acceptInput(which, key.channel)
                    case None if key.isReadable => log.debug("Ignoring input for child #" + id)
                    case _ => {}
                }

                iter.remove()
            }

            run()
        }
    }
    thread.start()

    private def rebuildBarrier(newSize: Int): Unit = {
        if (barrier != null) barrier.reset()
        barrier = new CyclicBarrier(newSize)
    }

    def add(label: String, parameters: VMParameters): ChildVM =
        synchronized {
            rebuildBarrier(childVMs.size+1)
            val newVM = new ChildVM(this, nextId, label, parameters)
            childVMs += nextId -> newVM
            nextId += 1
            newVM
        }

    def remove(childVM: ChildVM): Unit = 
        synchronized {
            if (childVMs contains childVM.id) {
                rebuildBarrier(childVMs.size - 1)
                childVMs -= childVM.id
                childVM.unregister()
            }
        }
}

case class VMParameters(targetMainClass: String, targetJARs: Set[String], cpuShare: Int, maxHeap: Long, maxStack: Int)

class ChildVM protected[agent] (supervisor: ChildVMSupervisor, val id: Int, val label: String, val parameters: VMParameters) {
    val log = Logger(getClass)

    val virtualMachine: VirtualMachine = {
        val agentJARPath = Configgy.config("agentJARPath")

        val connector = Bootstrap.virtualMachineManager.defaultConnector
        val arguments = connector.defaultArguments
        val args = arguments.asScala
        args("suspend").asInstanceOf[BooleanArgument].setValue(true)
        args("options").asInstanceOf[StringArgument].setValue (
            "-classpath " + (parameters.targetJARs + agentJARPath).mkString(File.pathSeparator) +
            "-Xmx" + parameters.maxHeap +
            "-Xms" + parameters.maxStack
        )
        args("main").asInstanceOf[StringArgument].setValue("rwars2012.agent.childVM.Rig")
        connector.launch(arguments)
    }

    val (stdoutSelectorKey, stderrSelectorKey) =
        (Channels.newChannel(virtualMachine.process.getInputStream).register(supervisor.selector, SelectionKey.OP_READ, (id, ProcessStream.STDOUT)),
         Channels.newChannel(virtualMachine.process.getErrorStream).register(supervisor.selector, SelectionKey.OP_READ, (id, ProcessStream.STDERR)));

    log.info("#" + id + " " + label + ": Registered.")

    protected[agent] def unregister(): Unit = {
        stdoutSelectorKey.cancel()
        stderrSelectorKey.cancel()
        virtualMachine.exit(0)
        log.info("#" + id + " " + label + ": Unregistered.")
    }

    val vmDeathRequest = virtualMachine.eventRequestManager.createVMDeathRequest()
    val vmThreadStartRequest = virtualMachine.eventRequestManager.createThreadStartRequest()
    val vmThreadDeathRequest = virtualMachine.eventRequestManager.createThreadDeathRequest()
    var vmThreads: Map[ThreadReference, StepRequest] = Map.empty

    private var _stable: Boolean = false

    def started: Boolean = _monitor.isDefined
    def stable: Boolean = _stable

    def start(): Unit = _monitor match {
        case None => {
            log.info("Starting VM \"" + label + "\" with parameters: " + parameters)
            virtualMachine.setDebugTraceMode(VirtualMachine.TRACE_ALL)
            vmThreadStartRequest.enable()
            vmThreadDeathRequest.enable()
            vmDeathRequest.enable()
            _monitor = Some(new Monitor(label + " Monitor"))
        }
        
        case Some(_) => sys.error(label + ": virtual machine already started")
    }

    def stop(): Unit = _monitor match {
        case Some(th) => {
            _monitor = None
            th.interrupt()
            log.info("Stopping VM \"" + label + "\" with parameters: " + parameters)
        }

        case None => sys.error(label + ": virtual machine already stopped")
    }

    def waitForDeath(): Unit = _monitor match {
        case None => {}
        case Some(th) => {
            synchronized { wait() }
            waitForDeath()
        }
    }

    private val stdoutBuffer = new StringWriter()
    private val stderrBuffer = new StringWriter()
    private val byteBuffer = ByteBuffer.allocate(1024)
    private val chars = Array.ofDim[Char](1024)
    private val charBuffer = CharBuffer.wrap(chars)

    private val stdoutCharsetDecoder = Charset.defaultCharset.newDecoder()
    private val stderrCharsetDecoder = Charset.defaultCharset.newDecoder()
    
    protected[agent] def acceptInput(which: ProcessStream.Value, channel: SelectableChannel) = {
        val (buffer, decoder, output) = which match {
            case ProcessStream.STDOUT => (stdoutBuffer, stdoutCharsetDecoder, (s: String) => log.info(label + ": " + s));
            case ProcessStream.STDERR => (stderrBuffer, stderrCharsetDecoder, (s: String) => log.error(label + ": " + s));
        }

        channel match {
            case (rbc: ReadableByteChannel) => {
                def processInput(): Unit = {
                    byteBuffer.clear()
                    charBuffer.clear()
                    rbc.read(byteBuffer) match {
                        case -1 => {
                            decoder.decode(byteBuffer, charBuffer, true)
                            decoder.flush(charBuffer)
                            buffer.write(chars, 0, charBuffer.remaining)
                        }

                        case n => {
                            byteBuffer.rewind()
                            decoder.decode(byteBuffer, charBuffer, false)
                            buffer.write(chars, 0, charBuffer.remaining)
                            processInput()
                        }
                    }
                }
                processInput()

                def readLines(from: Int): Unit =
                    buffer.getBuffer.indexOf("\n", from) match {
                        case -1 => buffer.getBuffer.delete(0, from)
                        case n if n >= 0 => {
                            output(buffer.getBuffer.substring(0, n))
                            readLines(from + 1)
                        }
                    }
                readLines(0)
            }

            case other =>
                log.error("Unknown channel subclass with " + other)
        }
    }

    private class Monitor(name: String) extends Thread(name) {
        override def run(): Unit = loop(true)

        private def loop(first: Boolean): Unit =
            try {
                if (!_monitor.isDefined) return

                val events = if (first) virtualMachine.eventQueue.remove(10) else virtualMachine.eventQueue.remove
                
                for (event <- events.asScala) {
                    event match {
                        case Step(vmThread, location) => {
                            log.debug(name + ": Step finished for " + vmThread.name + " at " +
                                      location.sourceName + ":" + location.lineNumber)
                        }

                        case ThreadStart(vmThread) => {
                            log.info(name + ": Thread start " + vmThread.name)
                            val stepRequest = virtualMachine.eventRequestManager.createStepRequest (
                                vmThread, StepRequest.STEP_MIN, StepRequest.STEP_INTO
                            )
                            stepRequest.enable()
                            vmThreads += ((vmThread, stepRequest))
                        }

                        case ThreadDeath(vmThread) => {
                            log.info(name + ": Thread death " + vmThread.name)
                            vmThreads -= vmThread
                        }

                        case VMDeath() => {
                            log.info(name + ": VM Died.")
                            _monitor = None
                            return
                        }

                        case VMStart(vmThread) => {
                            log.info(name + ": VM Started - initial thread " + vmThread.name)
                            _stable = true
                        }
                    }

                    supervisor.converge()

                    for ((vmThread, _) <- vmThreads if vmThread.isSuspended) {
                        log.debug(name + ": Thread " + vmThread.name + " is suspended - resuming it")
                        vmThread.resume()
                    }

                    log.debug(name + ": Resuming the virtual machine")
                    virtualMachine.resume()

                }
                    
                loop(false)
            } catch {
                case (ie: InterruptedException) => {}
                case (ex: Exception) => 
                    log.error(name + ": Monitor died: ", ex)

            }
    }

    private var _monitor: Option[Monitor] = None
}
