package com.revytechinc.honchoinspector;

import com.revytechinc.honchoinspector.cli.CliRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Arrays;

@SpringBootApplication
@EnableScheduling
public class DashboardApplication {

    public static void main(String[] args) {
        try {
            int rc = dispatch(args);
            if (rc != 0) {
                System.exit(rc);
            }
            // SpringApplication.run() returns when the context closes. With
            // Spring Boot 4.1.0 + Java 25 + Jetty 12.1.10 the context auto-closes
            // shortly after ApplicationReadyEvent fires (the embedded server
            // completes its smart-lifecycle and the main thread, if non-daemon
            // via the JDK's exit-on-completion logic, would let the JVM exit).
            // Block the main thread here so the JVM stays alive and the
            // embedded Jetty continues to serve requests. The SpringApplication
            // shutdown hook (registered with Runtime.addShutdownHook) handles
            // a real SIGTERM by closing the context, which unblocks this sleep
            // and lets the JVM exit cleanly via System.exit below.
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        } catch (Throwable t) {
            System.err.println("FATAL: main() threw: " + t);
            t.printStackTrace();
            System.exit(99);
        }
    }

    static boolean shouldRunCli(String[] args) {
        return args.length > 0 && CliRunner.isKnownCommand(args[0]);
    }

    static int dispatch(String[] args) {
        if (shouldRunCli(args)) {
            return runCli(args);
        }
        try {
            SpringApplication.run(DashboardApplication.class, args);
        } catch (Throwable t) {
            System.err.println("FATAL: SpringApplication.run() threw: " + t);
            t.printStackTrace();
            return 99;
        }
        return 0;
    }

    private static int runCli(String[] args) {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(DashboardApplication.class)
            .web(WebApplicationType.NONE)
            .logStartupInfo(false)
            .run(args);
        try {
            CliRunner runner = ctx.getBean(CliRunner.class);
            return runner.handle(args[0], Arrays.copyOfRange(args, 1, args.length));
        } finally {
            ctx.close();
        }
    }
}
