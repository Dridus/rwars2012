package rwars2012.agent

import java.io.{BufferedReader, File, InputStream, InputStreamReader, StringWriter}
import java.util.concurrent.{BrokenBarrierException, CyclicBarrier, Semaphore}
import com.sun.jdi.{Bootstrap, ThreadReference, VirtualMachine}
import com.sun.jdi.connect.Connector.{BooleanArgument, StringArgument}
import com.sun.jdi.request.{StepRequest, ThreadDeathRequest, ThreadStartRequest}
import net.lag.configgy.Configgy
import org.slf4j.{Logger, LoggerFactory}
import scala.collection.JavaConverters.{mapAsScalaMapConverter, asScalaSetConverter}

object ProcessStream extends Enumeration {
    val STDIN = Value("stdin")
    val STDOUT = Value("stdout")
    val STDERR = Value("stderr")
}

class ChildVMSupervisor(val quantum: Long) {
    val log = LoggerFactory.getLogger(getClass)

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
    val log = LoggerFactory.getLogger(getClass.getName + "#" + id + "-" + label)

    val virtualMachine: VirtualMachine = {
        val agentJARPath = Configgy.config("agentJARPath")

        val connector = Bootstrap.virtualMachineManager.defaultConnector
        val arguments = connector.defaultArguments
        val args = arguments.asScala
        args("suspend").asInstanceOf[BooleanArgument].setValue(true)
        args("options").asInstanceOf[StringArgument].setValue (
            List (
                "-classpath " + (parameters.targetJARs + agentJARPath).mkString(File.pathSeparator),
                "-Xmx" + parameters.maxHeap,
                "-Xss" + parameters.maxStack
            ).mkString(" ")
        )
        args("main").asInstanceOf[StringArgument].setValue("rwars2012.agent.rig.Rig")
        log.info("Launching VM with arguments:")
        args.values.foreach(arg => log.info("    " + arg.toString))
        log.info("and parameters: " + parameters)
        connector.launch(arguments)
    }

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
    var vmThreads: Map[ThreadReference, String] = Map.empty

    private var _stable: Boolean = false

    private var _monitor: Option[Monitor] = None

    def started: Boolean = _monitor.isDefined
    def stable: Boolean = _stable

    def start(): Unit = synchronized {
        _monitor match {
            case None => {
                log.info("Starting VM")
                //virtualMachine.setDebugTraceMode(VirtualMachine.TRACE_ALL)
                vmThreadStartRequest.enable()
                vmThreadDeathRequest.enable()
                vmDeathRequest.enable()
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

    private class Monitor extends Thread(label + "-Monitor") {
        override def run(): Unit = {
            log.debug("Monitor starting")
            while (ChildVM.this.synchronized(_monitor).isDefined) {
                try {
                    val events = 
                        if (!_stable) virtualMachine.eventQueue.remove // pend until any event while waiting for VM stability
                        else virtualMachine.eventQueue.remove(supervisor.quantum)

                    for (event <- if (events != null) events.asScala else Nil) {
                        event match {
                            case ThreadStart(vmThread) => {
                                val name = vmThread.name
                                log.info("VM thread starting: " + name)
                                vmThreads += vmThread -> name
                            }

                            case ThreadDeath(vmThread) => {
                                val name = vmThreads.get(vmThread) getOrElse "unknown"
                                log.info("VM thread dying: " + name)
                                vmThreads -= vmThread
                            }

                            case VMDeath() => {
                                log.info("VM dying - monitor exiting")
                                clearMonitor()
                                return
                            }

                            case VMStart(vmThread) => {
                                log.info("VM started with initial thread: " + vmThread.name)
                                _stable = true
                            }

                            case _ => {}
                        }

                        supervisor.converge()

                        for ((vmThread, name) <- vmThreads if vmThread.isSuspended) {
                            log.debug("Thread " + name + " is suspended - resuming it")
                            vmThread.resume()
                        }

                        log.debug("Resuming the virtual machine")
                        virtualMachine.resume()

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
            log.debug("Monitor exiting")
            clearMonitor()
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
