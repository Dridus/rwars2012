package rwars2012.agent

import net.lag.configgy.Configgy
import grizzled.slf4j.Logger

object Test extends App {
    val log = Logger(getClass)

    Configgy.configure(args(0))

    val supervisor = new ChildVMSupervisor(10)
    val childvms = List (
        supervisor.add("first",  VMParameters("rwars2012.agent.rig.TestPayload", Set.empty, 10000000, 1024*1024, 256*1024)),
        supervisor.add("second", VMParameters("rwars2012.agent.rig.TestPayload", Set.empty, 20000000, 1024*1024, 256*1024)),
        supervisor.add("third",  VMParameters("rwars2012.agent.rig.TestPayload", Set.empty, 30000000, 1024*1024, 256*1024))
    )

    childvms.foreach(_.start())

    childvms.foreach(_.waitForDeath())

    log.info("Bye.")
}
