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
        System.exit(dispatch(args));
    }

    static boolean shouldRunCli(String[] args) {
        return args.length > 0 && CliRunner.isKnownCommand(args[0]);
    }

    static int dispatch(String[] args) {
        if (shouldRunCli(args)) {
            return runCli(args);
        }
        SpringApplication.run(DashboardApplication.class, args);
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
