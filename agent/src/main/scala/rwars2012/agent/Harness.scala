package rwars2012.agent.harness

import java.io.File
import java.util.concurrent.{BrokenBarrierException, CyclicBarrier, Semaphore}
import com.sun.jdi.Bootstrap
import com.sun.jdi.connect.Connector.{BooleanArgument, StringArgument}
import net.lag.logging.Logger
import scala.collection.JavaConverters.mapAsScalaMapConverter

object RunningHarnesses {
    private var harnesses: List[Harness] = Nil

    var barrier: CyclicBarrier = null

    def += (harness: Harness): Unit =
        synchronized {
            if (!(harnesses contains harness)) {
                harnesses ::= harness
                reset
            }
        }
    
    def -= (harness: Harness): Unit =
        synchronized {
            if (harnesses contains harness) {
                harnesses = harnesses - harness
                reset
            }
        }

    private def reset = {
        barrier = new CyclicBarrier(harnesses.length)
    }

    reset
}

case class VMParameters(targetMainClass: String, targetJARs: Set[String], cpuShare: Int, maxHeap: Long, maxStack: Int)

class Harness(val label: String, val parameters: VMParameters) {
    val log = Logger(getClass)

    private class HarnessThread(name: String) extends Thread(name) {
        def run = {
            def waitForBarrier =
                try {
                    RunningHarnesses.barrier.await()
                } catch {
                    case (_: BrokenBarrierException) => waitForBarrier
                }
            
            waitForBarrier
        }
    }

    private var _thread: HarnessThread

    val virtualMachine = {
        val connector = Bootstrap.virtualMachineManager.defaultConnector
        val arguments = connector.defaultArguments.asScala
        arguments("suspend").asInstanceOf[BooleanArgument].setValue(true)
        arguments("options").asInstanceOf[StringArgument].setValue (
            "-classpath " + targetJARs.mkString(File.separator) +
            "-Xmx" + maxHeap +
            "-Xms" + maxStack
        )
        arguments("main").asInstanceOf[StringArgument].setValue("rwars2012.agent.harness.Rig")
        connector.launch(arguments)
    }

    
    def running: _running
    def running_= (value: Boolean): Unit = {
        (_running, value) match {
            case (false, true) => {
                log.info("Starting VM \"" + label + "\" with parameters: " + parameters)
                RunningHarnesses += this
                _thread
            }

            case (true, false) => {
                _thread = null
                RunningHarnesses -= this
                log.info("Stopping VM \"" + label + "\" with parameters: " + parameters)
            }

            case (_, _) => {}
        }

        _running = value
    }
}
