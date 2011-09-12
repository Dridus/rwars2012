package rwars2012.agent

import net.lag.configgy.Configgy
import org.slf4j.LoggerFactory

object Test extends App {
    val log = LoggerFactory.getLogger(getClass)

    Configgy.configure(args(0))

    val supervisor = new ChildVMSupervisor(10)
    val childvms = List (
        supervisor.add("first",  VMParameters("foo", Set.empty, 10, 1024*1024, 256*1024)),
        supervisor.add("second", VMParameters("bar", Set.empty, 20, 1024*1024, 256*1024)),
        supervisor.add("third",  VMParameters("baz", Set.empty, 30, 1024*1024, 256*1024))
    )

    childvms.foreach(_.start())

    childvms.foreach(_.waitForDeath())

    log.info("Bye.")
}
