package rwars2012.agent

import java.io.File
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
    private var barrier: CyclicBarrier = null
    private var childVMs: Map[Int, ChildVM] = Map.empty
    private var nextId: Int = 0
    private val selector: Selector = Selector.open()

    protected[agent] def converge: Unit = 
        try {
            barrier.await()
        } catch {
            case (_: BrokenBarrierException) => converge
        }

    private val thread = new Thread(toString + " Thread") {
        override def run(): Unit = {
            selector.select(0)
            val iter = selector.selectedKeys
            while (iter.hasNext) {
                val key = iter.next
                val (id, which) = key.attachment.asInstanceOf[(Int, ProcessStream#Value)]

                childVMs.get(id) match {
                    case Some(childVM) if key.isReadable => childVM.acceptInput(which, key.channel)
                    case None if key.isReadable => logger.debug("Ignoring input for child #" + id)
                    case _ => {}
                }

                iter.remove()
            }

            run()
        }
    }
    thread.start()

    def add(label: String, parameters: VMParameters): Unit =
        synchronized {
            val newVM = new ChildVM(this, id, label, parameters)
            childVMs ::= newVM
            if (barrier != null) barrier.reset()
            barrier = new CyclicBarrier(childVMs.length)
        }

    private def reset = {
    }

    reset
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
