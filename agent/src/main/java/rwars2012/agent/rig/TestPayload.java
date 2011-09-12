package rwars2012.agent.rig;

public class TestPayload implements Runnable {
    public void run() {
        System.out.println("test payload up");

        for (int i = 0; i < 1000000; i++) {
            if (i % 1000 == 0) {
                System.out.println("test payload: " + i);
            }
        }

        System.out.println("test payload down");
    }
}
