package com.guandan;

import com.guandan.cli.GuandanCli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GuandanApplication {

    public static void main(String[] args) {
        if (args.length > 0 && GuandanCli.isCommand(args[0])) {
            int status = GuandanCli.run(args);
            if (status != 0) System.exit(status);
            return;
        }
        SpringApplication.run(GuandanApplication.class, args);
    }
}
