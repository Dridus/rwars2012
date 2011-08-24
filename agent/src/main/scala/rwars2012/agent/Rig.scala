package rwars2012.agent.rig

object Rig extends App {
    println("rig up")

    var i = 0
    while (i < 1000000) {
        if (i % 1000 == 0) {
            println("rig at " + i)
        }
        i += 1
    }
    
    println("rig down")
}
