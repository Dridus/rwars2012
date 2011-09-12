package rwars2012.agent

import java.io.{BufferedReader, File, InputStream, InputStreamReader, StringWriter}
import java.util.concurrent.{BrokenBarrierException, CyclicBarrier, Semaphore}
import com.sun.jdi.{Bootstrap, LongValue, ThreadGroupReference, ThreadReference, VirtualMachine}
import com.sun.jdi.connect.Connector.{BooleanArgument, StringArgument}
import com.sun.jdi.event.EventSet
import com.sun.jdi.request.{StepRequest, ThreadDeathRequest, ThreadStartRequest}
import grizzled.slf4j.Logger
import net.lag.configgy.Configgy
import scala.collection.JavaConverters.{asScalaBufferConverter, asScalaSetConverter, mapAsScalaMapConverter}

object ProcessStream extends Enumeration {
    val STDIN = Value("stdin")
    val STDOUT = Value("stdout")
    val STDERR = Value("stderr")
}

class ChildVMSupervisor(val quantum: Long) {
    val log = Logger(getClass)

    private var barrier: CyclicBarrier = new CyclicBarrier(1)
    private var childVMs: Map[Int, ChildVM] = Map.empty
    private var nextId: Int = 0

    private val supervisorThread = new Thread("ChildVM Supervisor " + System.identityHashCode(this)) {
        setDaemon(true)

        override def run() =
            while (true) {
                val b = synchronized(barrier)
                try {
                    b.await()
                } catch {
                    case (_: BrokenBarrierException) => {
                        log.debug("barrier broken")
                        run()
                    }
                }

                log.debug("barrier converged")
            }
    }

    supervisorThread.start()

    protected[agent] def converge(): Unit = {
        val b = synchronized(barrier)
        try {
            b.await()
        } catch {
            case (_: BrokenBarrierException) => converge()
        }
    }

    private def rebuildBarrier(newChildCount: Int): Unit = {
        barrier.reset()
        barrier = new CyclicBarrier(newChildCount + 1)
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
                childVM.kill()
            }
        }
}

case class VMParameters(targetMainClass: String, targetJARs: Set[String], cpuShare: Int, maxHeap: Long, maxStack: Int)

class ChildVM protected[agent] (supervisor: ChildVMSupervisor, val id: Int, val label: String, val parameters: VMParameters) {
    val log = Logger(getClass.getName + "#" + id + "-" + label)

    val virtualMachine: VirtualMachine = {
        val agentJARPath = Configgy.config("agentJARPath")

        val connector = Bootstrap.virtualMachineManager.defaultConnector
        val arguments = connector.defaultArguments
        val args = arguments.asScala
        args("suspend").asInstanceOf[BooleanArgument].setValue(true)
        args("options").asInstanceOf[StringArgument].setValue (
            List (
                "-classpath " + (
                    (parameters.targetJARs + agentJARPath)
                    .map(new File(_)).map(_.getAbsolutePath)
                    .mkString(File.pathSeparator)
                ),
                "-Xmx" + parameters.maxHeap,
                "-Xss" + parameters.maxStack
            ).mkString(" ")
        )
        args("main").asInstanceOf[StringArgument].setValue("rwars2012.agent.rig.Rig " + parameters.targetMainClass)
        log.info("Launching VM with parameters: " + parameters + " and arguments:")
        args.values.foreach(arg => log.info("    " + arg.toString))
        connector.launch(arguments)
    }

    var vmThreads: Map[ThreadReference, String] = Map.empty

    protected[agent] def kill(): Unit = {
        virtualMachine.exit(0)
        log.info("Killed.")
    }

    private val stdoutStreamMonitor =
        new StreamLogger("stdout", virtualMachine.process.getInputStream, s => log.info("stdout: " + s))
    private val stderrStreamMonitor =
        new StreamLogger("stderr", virtualMachine.process.getErrorStream, s => log.error("stderr: " + s))

    val vmDeathRequest = virtualMachine.eventRequestManager.createVMDeathRequest()
    val vmThreadStartRequest = virtualMachine.eventRequestManager.createThreadStartRequest()
    val vmThreadDeathRequest = virtualMachine.eventRequestManager.createThreadDeathRequest()
    val classPrepareRequest = virtualMachine.eventRequestManager.createClassPrepareRequest()

    classPrepareRequest.addClassFilter("rwars2012.agent.rig.Rig")
    
    vmThreadStartRequest.enable()
    vmThreadDeathRequest.enable()
    vmDeathRequest.enable()
    classPrepareRequest.enable()

    private var _stableTime: Long = 0
    private var _exitCode: Int = 0
    private var _monitor: Option[Monitor] = None

    private var _payloadThreadGroup: Option[ThreadGroupReference] = None

    def started: Boolean = _monitor.isDefined
    def stable: Boolean = _stableTime > 0

    def start(): Unit = synchronized {
        _monitor match {
            case None => {
                log.info("Starting VM")
                //virtualMachine.setDebugTraceMode(VirtualMachine.TRACE_ALL)
                _monitor = Some(new Monitor)
                _monitor.get.start()
                stdoutStreamMonitor.start()
                stderrStreamMonitor.start()
                notifyAll()
            }
            
            case Some(_) => sys.error("VM already started")
        }
    }

    def stop(): Unit = synchronized {
        _monitor match {
            case Some(th) => {
                _monitor = None
                notifyAll()
                th.interrupt()
                log.info("Stopping VM with parameters: " + parameters)
            }

            case None => sys.error("VM already stopped")
        }
    }

    def waitForDeath(): Unit = synchronized {
        _monitor match {
            case None => log.debug("VM finished")
            case Some(th) => {
                log.debug("Waiting for VM to finish")
                synchronized { wait() }
                waitForDeath()
            }
        }
    }

    private def clearMonitor(): Unit = synchronized {
        log.debug("clearing monitor")
        _monitor = None
        notifyAll()
        try { stdoutStreamMonitor.interrupt() } catch { case ex => log.debug("couldn't interrupt stdout:", ex) }
        try { stderrStreamMonitor.interrupt() } catch { case ex => log.debug("couldn't interrupt stderr:", ex) }
    }

    sealed abstract class EventProcessingResult
    final case object MonitorFinished extends EventProcessingResult
    final case class EventsProcessed(eventSet: EventSet) extends EventProcessingResult
    final case object NoEventsToProcess extends EventProcessingResult

    private class Monitor extends Thread(label + "-Monitor") {
        var cpuTimeThisSlice: Long = 0

        private def handleEvents(maxTimeout: Long): EventProcessingResult = {
            val events = 
                if (_stableTime == 0) virtualMachine.eventQueue.remove // pend until any event while waiting for VM stability
                else virtualMachine.eventQueue.remove(maxTimeout)

            if (events == null) log.trace("scheduling quantum expired")

            for (event <- if (events != null) events.asScala else Nil) {
                event match {
                    case ClassPrepare(klass) if klass.name == "rwars2012.agent.rig.Rig" => {
                        log.info("Rig class preparing")
                        classPrepareRequest.disable()
                        val methodEntryRequest = virtualMachine.eventRequestManager.createMethodEntryRequest()
                        methodEntryRequest.addClassFilter(klass)
                        methodEntryRequest.enable()
                    }

                    case MethodEntry(vmThread, method) => {
                        lazy val args = vmThread.frame(0).getArgumentValues.asScala
                        lazy val callText = method.name + args.mkString("(", ", ", ")")
                        method.name match {
                            case "supervisorPresent" => {
                                log.debug(callText + " => true")
                                vmThread.forceEarlyReturn(virtualMachine.mirrorOf(true))
                            }

                            case "registerPayloadThreadGroup" => {
                                log.debug(callText + " => {}")
                                _payloadThreadGroup = Some(args(0).asInstanceOf[ThreadGroupReference])
                                vmThread.forceEarlyReturn(virtualMachine.mirrorOfVoid)
                            }

                            case "getSchedulingQuantum" => {
                                log.debug(callText + " => " + supervisor.quantum)
                                vmThread.forceEarlyReturn(virtualMachine.mirrorOf(supervisor.quantum))
                            }

                            case "reportUserTimeConsumed" => {
                                log.trace(callText + " => {}")
                                cpuTimeThisSlice += args(0).asInstanceOf[LongValue].value();
                                vmThread.forceEarlyReturn(virtualMachine.mirrorOfVoid)
                            }

                            case _ => log.trace(callText + " => ignored")
                        }
                    }
                    
                    case ThreadStart(vmThread) => {
                        val name = vmThread.name
                        log.debug("VM thread starting: " + name)
                        vmThreads += vmThread -> name
                    }

                    case ThreadDeath(vmThread) => {
                        val name = vmThreads.get(vmThread) getOrElse "unknown"
                        log.debug("VM thread dying: " + name)
                        vmThreads -= vmThread
                    }

                    case VMDeath() => {
                        log.info("VM dying - monitor exiting")
                        clearMonitor()
                        events.resume()
                        return MonitorFinished
                    }

                    case VMStart(vmThread) => {
                        log.info("VM started with initial thread: " + vmThread.name)
                        _stableTime = System.currentTimeMillis
                    }

                    case _ => log.trace("ignoring " + event)
                }
            }

            if (_stableTime == 0) handleEvents(maxTimeout)
            else Option(events).map(EventsProcessed.apply) getOrElse NoEventsToProcess
        }
            
        override def run(): Unit = {
            log.debug("Monitor starting")
            while (ChildVM.this.synchronized(_monitor).isDefined) {
                try {
                    def convergeIfNeeded() =
                        if (cpuTimeThisSlice >= parameters.cpuShare) {
                            log.trace("CPU share exhausted, converging")
                            virtualMachine.suspend()
                            supervisor.converge()
                            cpuTimeThisSlice = 0
                            virtualMachine.resume()
                        }

                    handleEvents(supervisor.quantum) match {
                        case EventsProcessed(events) => {
                            convergeIfNeeded()
                            log.trace("Resuming via EventSet")
                            events.resume()
                        }
                        
                        case NoEventsToProcess => {
                            convergeIfNeeded()
                            log.trace("Resuming the virtual machine")
                            virtualMachine.resume()
                        }
                        
                        case MonitorFinished => {}
                    }
                } catch {
                    case (ie: InterruptedException) => {}
                    case (ex: Exception) => {
                        log.error("Monitor dying: ", ex)
                        clearMonitor()
                        return
                    }
                }
            }

            clearMonitor()

            log.debug("Waiting for VM process to exit")
            _exitCode = virtualMachine.process.waitFor()
            if (_exitCode == 0) {
                log.info("Virtual machine exited with success after running for " +
                         (System.currentTimeMillis - _stableTime) + "ms")
            } else {
                log.warn("Virtual machine exited with code " + _exitCode + " after running for " +
                         (System.currentTimeMillis - _stableTime) + "ms")
            }
        }
    }

    private class StreamLogger(name: String, inputStream: InputStream, output: String => Unit) extends Thread(label + " " + name) {
        setDaemon(true)

        private val input = new BufferedReader(new InputStreamReader(inputStream))

        override def run(): Unit =
            try {
                while (synchronized(_monitor).isDefined) {
                    val line = try { input.readLine() } catch { case (_: InterruptedException) => null }
                    if (line == null) return
                    else output(line)
                }
            } catch {
                case ex => log.info("Died:", ex)
            } finally {
                log.debug("Finished.")
            }
    }
}
