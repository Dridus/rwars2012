package rwars2012.agent

import net.lag.configgy.Configgy

object Test extends App {
    Configgy.configure(args(0))

    val harnesses = List (
        new Harness("first",  VMParameters("foo", Set.empty, 10, 1024*1024, 256*1024)),
        new Harness("second", VMParameters("bar", Set.empty, 20, 1024*1024, 256*1024)),
        new Harness("third",  VMParameters("baz", Set.empty, 30, 1024*1024, 256*1024))
    )

    harnesses.foreach(_.start())

    harnesses.foreach(_.waitForDeath())
}
