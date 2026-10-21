package ir.msdehghan.plugins.ansible.runtime;

public final class FakeAnsibleDoc {
    private FakeAnsibleDoc() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("diagnostic")) {
            System.err.println("non-fatal diagnostic");
        }
        if (args.length > 0 && args[0].equals("wait")) {
            Thread.sleep(30_000);
        }
        System.out.println("cwd=" + System.getProperty("user.dir"));
        System.out.println("path=" + System.getenv("PATH"));
        System.out.println("args=" + String.join(" ", args));
    }
}
