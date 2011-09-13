package rwars2012.agent

import com.sun.jdi.{Location, ReferenceType, ThreadReference}
import com.sun.jdi.event.{
    Event, 
    ClassPrepareEvent,
    MethodEntryEvent,
    StepEvent,
    ThreadDeathEvent, ThreadStartEvent,
    VMDeathEvent, VMStartEvent, VMDisconnectEvent
}

object Step {
    def unapply(in: Event): Option[(ThreadReference, Location)] = in match {
        case (step: StepEvent) => Some((step.thread, step.location))
        case _ => None
    }
}

object ClassPrepare {
    def unapply(in: Event): Option[ReferenceType] = in match {
        case (classPrepare: ClassPrepareEvent) => Some(classPrepare.referenceType)
        case _ => None
    }
}

object MethodEntry {
    def unapply(in: Event): Option[(ThreadReference, com.sun.jdi.Method)] = in match {
        case (methodEntry: MethodEntryEvent) => Some(methodEntry.thread, methodEntry.method)
        case _ => None
    }
}

object ThreadDeath {
    def unapply(in: Event): Option[ThreadReference] = in match {
        case (threadDeath: ThreadDeathEvent) => Some(threadDeath.thread)
        case _ => None
    }
}

object ThreadStart {
    def unapply(in: Event): Option[ThreadReference] = in match {
        case (threadStart: ThreadStartEvent) => Some(threadStart.thread)
        case _ => None
    }
}

object VMDeath {
    def unapply(in: Event): Boolean = in.isInstanceOf[VMDeathEvent]
}

object VMStart {
    def unapply(in: Event): Option[ThreadReference] = in match {
        case (vmStart: VMStartEvent) => Some(vmStart.thread)
        case _ => None
    }
}

object VMDisconnect {
    def unapply(in: Event): Boolean = in.isInstanceOf[VMDisconnectEvent]
}
