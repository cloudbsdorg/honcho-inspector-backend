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
        if (args.length > 0 && CliRunner.isKnownCommand(args[0])) {
            runCli(args);
            return;
        }
        SpringApplication.run(DashboardApplication.class, args);
    }

    private static void runCli(String[] args) {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(DashboardApplication.class)
            .web(WebApplicationType.NONE)
            .logStartupInfo(false)
            .run(args);
        try {
            CliRunner runner = ctx.getBean(CliRunner.class);
            int exit = runner.handle(args[0], Arrays.copyOfRange(args, 1, args.length));
            System.exit(exit);
        } finally {
            ctx.close();
        }
    }
}
