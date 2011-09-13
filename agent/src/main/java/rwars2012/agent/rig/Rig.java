package rwars2012.agent.rig;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.IdentityHashMap;

class Rig {
    public static void main(String[] args) {
        try {
            new Rig().run(args);
        } catch (Throwable t) {
            System.err.println("throwable at top level:");
            t.printStackTrace(System.err);
            System.exit(2);
        }
    }
    
    public void run(String[] args) throws Exception {
        if (!supervisorPresent()) {
            System.err.println("supervisor not present");
            System.exit(1);
        }

        final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

        if (!threadMXBean.isThreadCpuTimeSupported()) {
            throw new UnsupportedOperationException("thread CPU time not supported by host VM");
        }

        threadMXBean.setThreadCpuTimeEnabled(true);

        if (args.length != 1) {
            System.err.println("missing payload");
            System.exit(1);
        }

        final String payloadClassName = args[0];

        long quantum = getSchedulingQuantum();
        ThreadGroup payloadThreads = new ThreadGroup("payload");
        registerPayloadThreadGroup(payloadThreads);

        new RigCPUMonitor(threadMXBean, payloadThreads, quantum).start();
        
        new Thread(payloadThreads, "Main Payload") {
            public void run() {
                try {
                    Class<? extends Runnable> payloadClass = Class.forName(payloadClassName).asSubclass(Runnable.class);

                    System.out.println("Rig loaded " + payloadClass.getName());

                    payloadClass.newInstance().run();
                } catch (Exception e) {
                    System.err.println("exception in payload:");
                    e.printStackTrace(System.err);
                }
            }
        }.start();
    }

    public boolean supervisorPresent() {
        return false;
    }

    public void registerPayloadThreadGroup(ThreadGroup threadGroup) {
        throw new UnsupportedOperationException("should have been handled by supervisor");
    }

    public long getSchedulingQuantum() {
        throw new UnsupportedOperationException("should have been handled by supervisor");
    }

    public void reportUserTimeConsumed(long nanos) {
    }

    private class RigCPUMonitor extends Thread {
        private ThreadMXBean threadMXBean;
        private ThreadGroup payloadThreads;
        private long sleepTime;
        private IdentityHashMap<Thread, Long> nanosByThread = new IdentityHashMap<Thread, Long>();
        private int runCount = 0;


        public RigCPUMonitor(ThreadMXBean threadMXBean, ThreadGroup payloadThreads, long quantum) {
            super("Rig CPU monitor");
            setDaemon(true);
            this.sleepTime = quantum / 2;
            this.threadMXBean = threadMXBean;
            this.payloadThreads = payloadThreads;
        }

        public void run() {
            Thread[] activeThreads = null;

            while (true) {
                //System.out.println("monitoring cpu");

                if (activeThreads == null) {
                    activeThreads = new Thread[payloadThreads.activeCount() + 5];
                }

                int threadsEnumerated;

                do {
                    threadsEnumerated = payloadThreads.enumerate(activeThreads);
                } while (threadsEnumerated >= activeThreads.length);

                IdentityHashMap<Thread, Long> newNanosByThread = null;
                if (++runCount % 1000 == 0) {
                    newNanosByThread = new IdentityHashMap<Thread, Long>(threadsEnumerated);
                } else {
                    newNanosByThread = nanosByThread;
                }

                long userNanosConsumed = 0;

                for (int i = 0; i < threadsEnumerated; ++i) {
                    Thread thread = activeThreads[i];
                    Long previousNanos = nanosByThread.get(thread);
                    long currentNanos = threadMXBean.getThreadUserTime(activeThreads[i].getId());
                    newNanosByThread.put(thread, currentNanos);
                    userNanosConsumed += currentNanos - (previousNanos != null ? previousNanos.longValue() : 0);
                }

                if (newNanosByThread != nanosByThread) {
                    nanosByThread = newNanosByThread;
                }

                reportUserTimeConsumed(userNanosConsumed);

                if (threadsEnumerated < activeThreads.length - 5) {
                    // Allocate a smaller array next time since so many threads died
                    activeThreads = null;
                }

                try {
                    if (sleepTime > 0) {
                        Thread.sleep(sleepTime);
                    } else {
                        Thread.yield();
                    }
                } catch (InterruptedException e) { }
            }
        }
    }
}
